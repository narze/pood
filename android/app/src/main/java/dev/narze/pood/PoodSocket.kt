package dev.narze.pood

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

enum class Conn { Disconnected, Connecting, Connected }

sealed interface ServerEvent {
    data class Ready(val user: String, val model: String) : ServerEvent
    data class Partial(val utteranceId: String, val text: String, val stash: String) : ServerEvent
    data class Final(
        val utteranceId: String,
        val id: Long?,
        val text: String,
        val latencyMs: Long,
        /** Desktops that received the transcript; empty from gateways that predate desktop push. */
        val delivered: List<String> = emptyList(),
    ) : ServerEvent
    /** `code == "connection"` means the socket dropped (not a protocol error). */
    data class Error(val utteranceId: String?, val code: String, val message: String) : ServerEvent
}

/**
 * The `/v1/ws` connection. Messages sent before the server's `ready` are queued and flushed on ready.
 * Reconnects with backoff until [close] is called.
 */
class PoodSocket(private val url: String, private val scope: CoroutineScope) {
    private val _conn = MutableStateFlow(Conn.Disconnected)
    val conn: StateFlow<Conn> = _conn
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events

    private var ws: WebSocket? = null
    private var ready = false
    private var closed = false
    private var backoffMs = 1000L
    private val pending = ArrayDeque<Any>()

    @Synchronized
    fun connect() {
        closed = false
        if (ws != null) return
        _conn.value = Conn.Connecting
        ws = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    @Synchronized
    fun close() {
        closed = true
        ws?.close(1000, null)
        reset()
    }

    fun send(json: JSONObject) = enqueue(json.toString())

    fun sendAudio(pcm: ByteArray) = enqueue(pcm.toByteString())

    @Synchronized
    private fun enqueue(msg: Any) {
        connect()
        if (ready) write(msg) else pending.addLast(msg)
    }

    private fun write(msg: Any) {
        when (msg) {
            is String -> ws?.send(msg)
            is ByteString -> ws?.send(msg)
        }
    }

    private fun reset() {
        ws = null
        ready = false
        pending.clear()
        _conn.value = Conn.Disconnected
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            val event = when (m.optString("type")) {
                "ready" -> {
                    synchronized(this@PoodSocket) {
                        if (webSocket != ws) return
                        ready = true
                        backoffMs = 1000L
                        _conn.value = Conn.Connected
                        while (pending.isNotEmpty()) write(pending.removeFirst())
                    }
                    ServerEvent.Ready(m.getString("user"), m.getString("model"))
                }
                "partial" -> ServerEvent.Partial(m.getString("utteranceId"), m.optString("text"), m.optString("stash"))
                "final" -> ServerEvent.Final(
                    m.getString("utteranceId"),
                    if (m.isNull("id")) null else m.getLong("id"),
                    m.getString("text"),
                    m.optLong("latencyMs"),
                    m.optJSONArray("delivered")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                )
                "error" -> ServerEvent.Error(
                    if (m.has("utteranceId")) m.getString("utteranceId") else null,
                    m.optString("code"),
                    m.optString("message"),
                )
                else -> return
            }
            _events.tryEmit(event)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            dropped(webSocket, response?.let { "HTTP ${it.code}" } ?: t.message ?: "connection failed")

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            dropped(webSocket, "closed ($code)")

        private fun dropped(webSocket: WebSocket, why: String) {
            synchronized(this@PoodSocket) {
                if (webSocket != ws) return
                reset()
                if (closed) return
            }
            _events.tryEmit(ServerEvent.Error(null, "connection", why))
            scope.launch {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                synchronized(this@PoodSocket) { if (!closed) connect() }
            }
        }
    }
}
