package dev.narze.pood

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** How far a transcript must be swiped right before letting go beams it. */
private const val BEAM_THRESHOLD_DP = 72

/**
 * Swipe the content right past the threshold and let go to call [onBeam]; it then springs back. The revealed strip on
 * the left says what will happen and turns solid once the swipe is far enough.
 */
@Composable
fun BeamSwipe(onBeam: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { BEAM_THRESHOLD_DP.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    val armed = dragX >= threshold
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(
        modifier.draggable(
            orientation = Orientation.Horizontal,
            enabled = enabled,
            state = rememberDraggableState { delta -> dragX = (dragX + delta).coerceIn(0f, threshold * 1.5f) },
            onDragStopped = {
                if (dragX >= threshold) onBeam()
                animate(dragX, 0f) { value, _ -> dragX = value }
            },
        ),
    ) {
        if (dragX > 0f) Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { dragX.toDp() })
                    .clipToBounds()
                    .background(Magenta.copy(alpha = if (armed) 1f else 0.35f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Beam →", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        Box(Modifier.offset { IntOffset(dragX.roundToInt(), 0) }) { content() }
    }
}

/**
 * Where the overlay's swipeable rows are, in window coordinates. The overlay window moves when dragged, so
 * [OverlayFrame] asks this before treating a sideways drag as a window move.
 */
class SwipeZones {
    private val zones = mutableMapOf<String, Rect>()

    fun contains(x: Float, y: Float) = zones.values.any { it.contains(Offset(x, y)) }

    fun put(key: String, bounds: Rect) {
        zones[key] = bounds
    }

    fun remove(key: String) {
        zones.remove(key)
    }
}

/** Registers this element's bounds in [zones] while it is shown. */
@Composable
fun Modifier.swipeZone(zones: SwipeZones, key: String): Modifier {
    DisposableEffect(zones, key) { onDispose { zones.remove(key) } }
    return onGloballyPositioned { zones.put(key, it.boundsInWindow()) }
}
