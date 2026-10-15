package dev.narze.pood

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

private val Recording = Color(0xFFE11D48)

@Composable
fun OverlayContent(
    state: StateFlow<UiState>,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val s by state.collectAsStateWithLifecycle()
    PoodTheme {
        if (s.expanded) Panel(s, onCollapse, onToggle, onCopy) else Bubble(s, onExpand)
    }
}

@Composable
private fun Bubble(s: UiState, onExpand: () -> Unit) {
    val recording = s.phase == Phase.Recording
    val pulse by animateFloatAsState(if (recording) 1f + s.level * 0.25f else 1f, label = "pulse")
    Box(
        Modifier
            .size(PoodService.BUBBLE_DP.dp)
            .padding(4.dp)
            .scale(pulse)
            .background(if (recording) Recording else Magenta, CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        if (s.phase == Phase.Transcribing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_speaking), contentDescription = "Pood", tint = Color.White)
        ConnDot(s.conn, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Panel(s: UiState, onCollapse: () -> Unit, onToggle: () -> Unit, onCopy: (String) -> Unit) {
    Surface(
        Modifier.width(PoodService.PANEL_DP.dp).padding(4.dp),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Magenta.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnDot(s.conn)
                Spacer(Modifier.width(6.dp))
                Text("พูด Pood", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCollapse) { Text("Hide") }
            }

            Transcript(s)
            if (s.phase == Phase.Idle && s.delivered.isNotEmpty()) Text(
                "→ ${s.delivered.distinct().joinToString(", ")} ✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordButton(s, onToggle)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (s.phase) {
                            Phase.Idle -> if (s.conn == Conn.Connected) "Tap to speak" else "Connecting…"
                            Phase.Recording -> "Listening… tap to finish"
                            Phase.Transcribing -> "Transcribing…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2) }
                }
                if (s.text.isNotEmpty()) TextButton(onClick = { onCopy(s.text) }) { Text(if (s.copied) "Copied ✓" else "Copy") }
            }

            if (s.recent.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent · tap to copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.recent.forEach { t ->
                    Text(
                        t.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable { onCopy(t.text) }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Transcript(s: UiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        when {
            s.phase != Phase.Idle && (s.partial + s.stash).isNotEmpty() -> Text(
                buildAnnotatedString {
                    append(s.partial)
                    withStyle(SpanStyle(color = muted)) { append(s.stash) }
                },
                fontSize = 17.sp,
            )
            s.text.isNotEmpty() -> Text(s.text, fontSize = 17.sp)
            else -> Text("Speak Thai, English, or both.", color = muted, fontSize = 15.sp)
        }
    }
}

@Composable
private fun RecordButton(s: UiState, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val recording = s.phase == Phase.Recording
    val ring by animateFloatAsState(if (recording) 1f + s.level * 0.3f else 1f, label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        if (recording) Box(Modifier.size(64.dp).scale(ring).background(Recording.copy(alpha = 0.25f), CircleShape))
        Box(
            Modifier
                .size(56.dp)
                .background(if (recording) Recording else Magenta, CircleShape)
                .clickable(enabled = s.phase != Phase.Transcribing) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            contentAlignment = Alignment.Center,
        ) {
            when (s.phase) {
                Phase.Idle -> Icon(painterResource(R.drawable.ic_mic), contentDescription = "Record", tint = Color.White)
                Phase.Recording -> Box(Modifier.size(18.dp).background(Color.White, RoundedCornerShape(3.dp)))
                Phase.Transcribing -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ConnDot(conn: Conn, modifier: Modifier = Modifier) {
    val color = when (conn) {
        Conn.Connected -> Color(0xFF22C55E)
        Conn.Connecting -> Color(0xFFF59E0B)
        Conn.Disconnected -> Color(0xFF9CA3AF)
    }
    Box(modifier.size(10.dp).background(color, CircleShape).border(1.dp, Color.White, CircleShape))
}
