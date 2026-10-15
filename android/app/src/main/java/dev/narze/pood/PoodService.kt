package dev.narze.pood

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

enum class Phase { Idle, Recording, Transcribing }

data class UiState(
    val conn: Conn = Conn.Disconnected,
    val phase: Phase = Phase.Idle,
    val partial: String = "",
    val stash: String = "",
    val text: String = "",
    val copied: Boolean = false,
    val delivered: List<String> = emptyList(),
    val error: String? = null,
    val level: Float = 0f,
    val recent: List<Transcript> = emptyList(),
    val expanded: Boolean = false,
)

/**
 * Foreground service (type microphone) that owns the overlay, the gateway socket and the mic.
 * Must be started while the app is in the foreground so Android grants while-in-use mic access.
 */
class PoodService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var settings: Settings
    private lateinit var socket: PoodSocket
    private lateinit var api: PoodApi
    private lateinit var windowManager: WindowManager
    private var overlay: OverlayFrame? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val capture = AudioCapture(
        onFrame = { socket.sendAudio(it) },
        onLevel = { level -> _state.update { it.copy(level = level) } },
    )
    private var utteranceId: String? = null

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        settings = Settings(this)
        api = PoodApi(settings.serverUrl)
        windowManager = getSystemService(WindowManager::class.java)
        startInForeground()
        _running.value = true

        socket = PoodSocket(socketUrl(), lifecycleScope)
        lifecycleScope.launch { socket.conn.collect { c -> _state.update { it.copy(conn = c) } } }
        lifecycleScope.launch { socket.events.collect(::onEvent) }
        socket.connect()

        if (AndroidSettings.canDrawOverlays(this)) showOverlay()
        else _state.update { it.copy(error = "Overlay permission missing") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_QUIT -> stopSelf()
            ACTION_TOGGLE -> toggle()
        }
        return START_NOT_STICKY // a restarted service would lack while-in-use mic access
    }

    override fun onDestroy() {
        capture.stop()
        socket.close()
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        _state.value = UiState()
        _running.value = false
        super.onDestroy()
    }

    // --- recording ---

    private fun toggle() {
        when (_state.value.phase) {
            Phase.Idle -> startRecording()
            Phase.Recording -> stopRecording()
            Phase.Transcribing -> Unit
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Microphone permission missing") }
            return
        }
        val id = UUID.randomUUID().toString()
        utteranceId = id
        socket.send(JSONObject().put("type", "start").put("utteranceId", id))
        try {
            capture.start()
        } catch (e: Exception) {
            socket.send(JSONObject().put("type", "cancel").put("utteranceId", id))
            utteranceId = null
            _state.update { it.copy(error = e.message) }
            return
        }
        _state.update { it.copy(phase = Phase.Recording, partial = "", stash = "", text = "", copied = false, delivered = emptyList(), error = null) }
    }

    private fun stopRecording() {
        val id = utteranceId ?: return
        capture.stop() // flushes the last frame before `stop` is queued
        socket.send(JSONObject().put("type", "stop").put("utteranceId", id))
        _state.update { it.copy(phase = Phase.Transcribing) }
    }

    private fun abort(message: String) {
        capture.stop()
        utteranceId = null
        _state.update { it.copy(phase = Phase.Idle, error = message) }
    }

    private fun onEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> lifecycleScope.launch {
                runCatching { api.history(limit = 3) }.onSuccess { list -> _state.update { it.copy(recent = list) } }
            }
            is ServerEvent.Partial -> if (event.utteranceId == utteranceId)
                _state.update { it.copy(partial = event.text, stash = event.stash) }
            is ServerEvent.Final -> if (event.utteranceId == utteranceId) {
                utteranceId = null
                val copied = event.text.isNotEmpty() && settings.autoCopy && copy(event.text)
                _state.update { s ->
                    s.copy(
                        phase = Phase.Idle,
                        text = event.text,
                        copied = copied,
                        delivered = event.delivered,
                        error = if (event.text.isEmpty()) "Didn't catch that" else null,
                        recent = if (event.id == null) s.recent
                        else (listOf(Transcript(event.id, System.currentTimeMillis(), event.text, Build.MODEL)) + s.recent).take(3),
                    )
                }
            }
            is ServerEvent.Error -> when {
                event.code == "connection" && utteranceId != null -> abort("Connection lost: ${event.message}")
                event.code == "connection" -> _state.update { it.copy(error = "Disconnected: ${event.message}") }
                event.code == "too_long" -> { // the server already stopped the utterance; a final follows
                    capture.stop()
                    _state.update { it.copy(phase = Phase.Transcribing, error = event.message) }
                }
                event.utteranceId == utteranceId -> abort(event.message)
                else -> _state.update { it.copy(error = event.message) }
            }
        }
    }

    private fun copy(text: String): Boolean {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pood", text))
        return true
    }

    // --- overlay ---

    private fun showOverlay() {
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_DP * metrics.density).toInt()
        params.x = if (settings.bubbleX < 0) metrics.widthPixels - bubblePx else settings.bubbleX
        params.y = settings.bubbleY
        val compose = ComposeView(this).apply {
            setContent {
                OverlayContent(
                    state = state,
                    onExpand = { setExpanded(true) },
                    onCollapse = { setExpanded(false) },
                    onToggle = ::toggle,
                    onCopy = { text -> if (copy(text)) _state.update { it.copy(copied = it.text == text) } },
                )
            }
        }
        val frame = OverlayFrame(this, onDrag = ::moveBy, onDragEnd = ::dragEnded).apply {
            addView(compose)
            setViewTreeLifecycleOwner(this@PoodService)
            setViewTreeSavedStateRegistryOwner(this@PoodService)
        }
        windowManager.addView(frame, params)
        overlay = frame
    }

    private fun setExpanded(expanded: Boolean) {
        val metrics = resources.displayMetrics
        val margin = (8 * metrics.density).toInt()
        val bubblePx = (BUBBLE_DP * metrics.density).toInt()
        val panelPx = (PANEL_DP * metrics.density).toInt()
        if (expanded) {
            val onRight = settings.bubbleX < 0 || settings.bubbleX + bubblePx / 2 > metrics.widthPixels / 2
            params.x = if (onRight) metrics.widthPixels - panelPx - margin else margin
            val panelHeightPx = (PANEL_MAX_HEIGHT_DP * metrics.density).toInt()
            params.y = settings.bubbleY.coerceAtMost(metrics.heightPixels - panelHeightPx).coerceAtLeast(margin)
        } else {
            params.x = if (settings.bubbleX < 0) metrics.widthPixels - bubblePx else settings.bubbleX
            params.y = settings.bubbleY
        }
        _state.update { it.copy(expanded = expanded) }
        overlay?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val metrics = resources.displayMetrics
        params.x = (params.x + dx.toInt()).coerceIn(0, metrics.widthPixels - 1)
        params.y = (params.y + dy.toInt()).coerceIn(0, metrics.heightPixels - 1)
        overlay?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun dragEnded() {
        if (_state.value.expanded) return
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_DP * metrics.density).toInt()
        params.x = if (params.x + bubblePx / 2 > metrics.widthPixels / 2) metrics.widthPixels - bubblePx else 0
        settings.bubbleX = params.x
        settings.bubbleY = params.y
        overlay?.let { windowManager.updateViewLayout(it, params) }
    }

    // --- plumbing ---

    private fun socketUrl(): String {
        val base = settings.serverUrl.replaceFirst(Regex("^http"), "ws")
        return "$base/v1/ws?device=${android.net.Uri.encode(Build.MODEL)}"
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(
            this, 1, Intent(this, PoodService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, PoodService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_speaking)
            .setContentTitle("Pood is ready")
            .setContentText("Tap the bubble to speak")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Record / stop", toggle)
            .addAction(0, "Quit", quit)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    companion object {
        const val BUBBLE_DP = 56
        const val PANEL_DP = 300
        private const val PANEL_MAX_HEIGHT_DP = 440
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_QUIT = "dev.narze.pood.QUIT"
        private const val ACTION_TOGGLE = "dev.narze.pood.TOGGLE"

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) = context.startForegroundService(Intent(context, PoodService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, PoodService::class.java))
    }
}
