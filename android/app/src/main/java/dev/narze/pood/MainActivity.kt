package dev.narze.pood

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PoodTheme { MainScreen() } }
    }
}

private data class Perms(val mic: Boolean, val overlay: Boolean, val notifications: Boolean) {
    val all get() = mic && overlay && notifications
}

private fun Context.perms() = Perms(
    mic = granted(Manifest.permission.RECORD_AUDIO),
    overlay = AndroidSettings.canDrawOverlays(this),
    notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
)

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val running by PoodService.running.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(settings.serverUrl) }
    var me by remember { mutableStateOf<String?>(null) }
    var autoCopy by remember { mutableStateOf(settings.autoCopy) }
    var perms by remember { mutableStateOf(context.perms()) }
    val history = remember { mutableStateListOf<Transcript>() }
    var hasMore by remember { mutableStateOf(false) }

    fun api() = PoodApi(settings.serverUrl)
    fun say(msg: String) = scope.launch { snackbar.showSnackbar(msg) }
    fun loadHistory(reset: Boolean) = scope.launch {
        runCatching { api().history(before = if (reset) null else history.lastOrNull()?.id, limit = 50) }
            .onSuccess { page ->
                if (reset) history.clear()
                history.addAll(page)
                hasMore = page.size == 50
            }
            .onFailure { say("History: ${it.message}") }
    }
    fun testConnection() = scope.launch {
        settings.serverUrl = url
        me = runCatching { api().me() }.fold({ "Connected as ${it.user} · ${it.model}" }, { "Failed: ${it.message}" })
        if (me!!.startsWith("Connected")) loadHistory(reset = true)
    }

    LifecycleResumeEffect(Unit) {
        perms = context.perms()
        if (settings.serverUrl.isNotBlank()) loadHistory(reset = true)
        onPauseOrDispose {}
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perms = context.perms() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perms = context.perms() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("พูด Pood") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Server") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Gateway URL") },
                        placeholder = { Text("https://server.tailnet.ts.net:8790") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { testConnection() }) { Text("Save & test") }
                        Spacer(Modifier.width(12.dp))
                        me?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Section("Permissions") {
                    PermRow("Microphone", perms.mic) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    PermRow("Display over other apps", perms.overlay) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33)
                        PermRow("Notifications", perms.notifications) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            item {
                Section("Bubble") {
                    ToggleRow("Show bubble", running) { on ->
                        when {
                            !on -> PoodService.stop(context)
                            settings.serverUrl.isBlank() -> say("Set the gateway URL first")
                            !perms.all -> say("Grant all permissions first")
                            else -> PoodService.start(context)
                        }
                    }
                    ToggleRow("Copy transcript automatically", autoCopy) { autoCopy = it; settings.autoCopy = it }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { loadHistory(reset = true) }) { Text("Refresh") }
                }
            }
            items(history, key = { it.id }) { t ->
                HistoryItem(
                    t,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pood", t.text))
                        if (Build.VERSION.SDK_INT < 33) say("Copied")
                    },
                    onDelete = {
                        scope.launch {
                            runCatching { api().delete(t.id) }.onSuccess { history.remove(t) }.onFailure { say("Delete: ${it.message}") }
                        }
                    },
                )
            }
            if (hasMore) item { OutlinedButton(onClick = { loadHistory(reset = false) }, Modifier.fillMaxWidth()) { Text("Load more") } }
            if (history.isEmpty()) item { Text("No transcripts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (granted) Text("Granted ✓", color = MaterialTheme.colorScheme.primary)
        else OutlinedButton(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HistoryItem(t: Transcript, onCopy: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onCopy)) {
        Text(t.text, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${DateUtils.getRelativeTimeSpanString(t.createdAt)} · ${t.device}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) { Text("Copy", fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
    }
}
