use crate::config::Config;
use futures_util::{
    future::{select, Either},
    StreamExt,
};
use serde::Deserialize;
use std::{sync::Arc, time::Duration};
use tokio::sync::Notify;
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, http::HeaderValue, Message};

#[derive(Clone, Debug)]
pub enum Conn {
    Connecting,
    Connected { user: String },
    Disconnected { reason: String },
}

#[derive(Deserialize, Debug)]
#[serde(tag = "type", rename_all = "lowercase")]
enum ServerMsg {
    Hello { user: String },
    Transcript { text: String, device: String },
    #[serde(other)]
    Unknown,
}

/// The server pings idle sockets about once a minute; silence longer than this means the link is dead.
const READ_TIMEOUT: Duration = Duration::from_secs(150);

/// Keeps a connection to the gateway's desktop channel forever, reconnecting with backoff (1→30 s).
/// The config is re-read before every attempt, so edits apply on the next reconnect. `reconnect` cuts the current
/// connection (or the backoff wait) short.
pub async fn run(
    load_config: impl Fn() -> Result<Config, String>,
    reconnect: Arc<Notify>,
    on_state: impl Fn(Conn),
    on_transcript: impl Fn(String, String),
) {
    let mut backoff = Duration::from_secs(1);
    loop {
        on_state(Conn::Connecting);
        match connect_once(&load_config, &reconnect, &on_state, &on_transcript).await {
            Ok(()) => backoff = Duration::from_secs(1), // was connected; retry promptly
            Err(reason) => on_state(Conn::Disconnected { reason }),
        }
        let wait = select(Box::pin(tokio::time::sleep(backoff)), Box::pin(reconnect.notified())).await;
        if let Either::Right(_) = wait {
            backoff = Duration::from_secs(1);
        }
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

async fn connect_once(
    load_config: &impl Fn() -> Result<Config, String>,
    reconnect: &Notify,
    on_state: &impl Fn(Conn),
    on_transcript: &impl Fn(String, String),
) -> Result<(), String> {
    let config = load_config()?;
    let mut request = config.socket_url()?.into_client_request().map_err(|e| e.to_string())?;
    if let Some(token) = config.token.as_deref().filter(|t| !t.is_empty()) {
        let value = HeaderValue::from_str(&format!("Bearer {token}")).map_err(|e| e.to_string())?;
        request.headers_mut().insert("Authorization", value);
    }
    let (mut ws, _) = tokio_tungstenite::connect_async(request).await.map_err(describe)?;
    let mut connected = false;
    loop {
        let next = tokio::time::timeout(READ_TIMEOUT, ws.next());
        let msg = match select(Box::pin(next), Box::pin(reconnect.notified())).await {
            Either::Left((msg, _)) => msg.map_err(|_| "Connection timed out".to_string())?,
            Either::Right(_) => return Ok(()),
        };
        match msg {
            Some(Ok(Message::Text(text))) => match serde_json::from_str::<ServerMsg>(&text) {
                Ok(ServerMsg::Hello { user }) => {
                    connected = true;
                    on_state(Conn::Connected { user });
                }
                Ok(ServerMsg::Transcript { text, device }) => on_transcript(text, device),
                Ok(ServerMsg::Unknown) | Err(_) => {}
            },
            Some(Ok(_)) => {} // pings are answered by tungstenite
            Some(Err(e)) => return if connected { Ok(()) } else { Err(e.to_string()) },
            None => return if connected { Ok(()) } else { Err("Connection closed".into()) },
        }
    }
}

fn describe(e: tokio_tungstenite::tungstenite::Error) -> String {
    use tokio_tungstenite::tungstenite::Error;
    match e {
        Error::Http(res) if res.status() == 401 => "Not authorized: is this machine on your tailnet as you?".into(),
        Error::Http(res) => format!("Gateway answered HTTP {}", res.status()),
        e => e.to_string(),
    }
}
