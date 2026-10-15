use serde::{Deserialize, Serialize};
use std::{fs, path::Path};

/// `config.json` in the app config dir. Edited by hand until the settings window lands (#6).
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Config {
    /// Gateway URL, the same one the phone uses, e.g. `https://server.tailnet.ts.net:8790`.
    #[serde(default)]
    pub url: String,
    /// Shown on the phone as "→ <name> ✓". Defaults to the hostname.
    #[serde(default)]
    pub name: String,
    /// Only needed when not going through `tailscale serve`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

impl Config {
    /// Loads the config, writing a template on first run so there is something to edit.
    pub fn load(path: &Path) -> Result<Config, String> {
        if !path.exists() {
            let template = Config { name: default_name(), ..Default::default() };
            if let Some(dir) = path.parent() {
                fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            fs::write(path, serde_json::to_string_pretty(&template).unwrap()).map_err(|e| e.to_string())?;
        }
        let raw = fs::read_to_string(path).map_err(|e| e.to_string())?;
        let mut config: Config = serde_json::from_str(&raw).map_err(|e| format!("config.json: {e}"))?;
        if config.name.trim().is_empty() {
            config.name = default_name();
        }
        Ok(config)
    }

    /// `https://host:8790` → `wss://host:8790/v1/desktop?name=…`
    pub fn socket_url(&self) -> Result<String, String> {
        let url = self.url.trim().trim_end_matches('/');
        let rest = if let Some(r) = url.strip_prefix("https://") {
            format!("wss://{r}")
        } else if let Some(r) = url.strip_prefix("http://") {
            format!("ws://{r}")
        } else if url.starts_with("wss://") || url.starts_with("ws://") {
            url.to_string()
        } else if url.is_empty() {
            return Err("Set the gateway URL in config.json".into());
        } else {
            return Err(format!("Gateway URL must start with https:// or http:// (got {url})"));
        };
        Ok(format!("{rest}/v1/desktop?name={}", encode(&self.name)))
    }
}

fn default_name() -> String {
    let host = gethostname::gethostname().to_string_lossy().into_owned();
    host.trim_end_matches(".local").to_string()
}

fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn with_url(url: &str) -> Config {
        Config { url: url.into(), name: "Mac Book".into(), token: None }
    }

    #[test]
    fn builds_socket_url() {
        assert_eq!(with_url("https://h.ts.net:8790/").socket_url().unwrap(), "wss://h.ts.net:8790/v1/desktop?name=Mac%20Book");
        assert_eq!(with_url("http://127.0.0.1:8790").socket_url().unwrap(), "ws://127.0.0.1:8790/v1/desktop?name=Mac%20Book");
        assert!(with_url("").socket_url().is_err());
        assert!(with_url("h.ts.net").socket_url().is_err());
    }
}
