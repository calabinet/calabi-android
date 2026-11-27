package net.calabi.app.rdp

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.R
import net.calabi.app.ui.Palette
import kotlin.math.roundToInt

/** A single recognizer distinguishes a short action from a spoken-name hint. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RdpIconControl(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    selected: Boolean? = null,
    tint: Color = Palette.ink,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = { android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show() },
            )
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = if (!enabled) Palette.muted else if (selected == true) Palette.accent else tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Floating chrome must not forward taps in its padding to the remote desktop. */
internal fun Modifier.consumeControlTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A child button owns a consumed down. Leave that entire gesture alone,
        // including the icon's long press and release handling.
        if (!down.isConsumed) {
            down.consume()
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                pressed = event.changes.any { it.pressed }
            }
        }
    }
}

/** Draggable, edge-snapping translucent handle so it never permanently covers Windows system tray. */
@Composable
internal fun RdpFloatingHandle(
    containerWidthPx: Float,
    containerHeightPx: Float,
    snapRight: Boolean,
    yFraction: Float,
    onOpen: () -> Unit,
    onPositionSaved: (snapRight: Boolean, yFraction: Float) -> Unit,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { 48.dp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    val maxX = (containerWidthPx - sizePx - marginPx).coerceAtLeast(marginPx)
    val maxY = (containerHeightPx - sizePx - marginPx).coerceAtLeast(marginPx)
    val targetX = if (snapRight) maxX else marginPx
    val targetY = (marginPx + (maxY - marginPx) * yFraction.coerceIn(0f, 1f)).coerceIn(marginPx, maxY)

    var dragging by remember { mutableStateOf(false) }
    var dragX by remember(targetX, maxX) { mutableFloatStateOf(targetX) }
    var dragY by remember(targetY, maxY) { mutableFloatStateOf(targetY) }

    val displayX = if (dragging) dragX else targetX
    val displayY = if (dragging) dragY else targetY

    Surface(
        modifier = Modifier
            .offset { IntOffset(displayX.roundToInt(), displayY.roundToInt()) }
            .size(48.dp)
            .alpha(if (dragging) 1f else 0.72f)
            .pointerInput(maxX, maxY, targetX, targetY) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragX = targetX
                        dragY = targetY
                    },
                    onDragEnd = {
                        dragging = false
                        val right = dragX + sizePx / 2f >= containerWidthPx / 2f
                        val frac = if (maxY > marginPx) ((dragY - marginPx) / (maxY - marginPx)).coerceIn(0f, 1f) else 0.82f
                        onPositionSaved(right, frac)
                    },
                    onDragCancel = { dragging = false },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX = (dragX + amount.x).coerceIn(marginPx, maxX)
                        dragY = (dragY + amount.y).coerceIn(marginPx, maxY)
                    },
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = Palette.surface,
        shadowElevation = 6.dp,
    ) {
        RdpIconControl(R.drawable.ic_rdp_controls, stringResource(R.string.mobile_show_controls), onClick = onOpen)
    }
}

/** Compact single-row scrollable toolbar shown directly above the soft keyboard or modifier bar. */
@Composable
internal fun RdpKeyboardBar(
    modifiers: Int,
    keyboardVisible: Boolean,
    clipboardReady: Boolean,
    onToggleModifier: (Int) -> Unit,
    onPress: (Int) -> Unit,
    onPasteFromPhone: () -> Unit,
    onHide: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Palette.ground)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Ctrl" to 1, "Alt" to 2, "Shift" to 4, "Win" to 8).forEach { (label, bit) ->
                val selected = modifiers and bit != 0
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) Palette.accentSoft else Palette.surface,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onToggleModifier(bit) },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, color = if (selected) Palette.accent else Palette.ink)
                    }
                }
            }
            listOf(
                "Esc" to 0x01,
                "Tab" to 0x0f,
                "Enter" to 0x1c,
                "⌫" to 0x0e,
                "←" to 0x14b,
                "↑" to 0x148,
                "↓" to 0x150,
                "→" to 0x14d,
                "Del" to 0x153,
            ).forEach { (label, scan) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Palette.surface,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onPress(scan) },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, color = Palette.ink)
                    }
                }
            }
            if (clipboardReady) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Palette.surface,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onPasteFromPhone),
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.rdp_quick_paste), fontSize = 12.sp, color = Palette.accent)
                    }
                }
            }
        }
        RdpIconControl(
            R.drawable.ic_chevron_down,
            stringResource(if (keyboardVisible) R.string.rdp_hide_keyboard else R.string.mobile_hide_controls),
            onClick = onHide,
        )
    }
}

internal data class RdpToolAction(
    val icon: Int,
    val label: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val action: () -> Unit,
)

/** Labels wrap naturally at large font scales; the grid changes to two columns. */
@Composable
internal fun RdpToolGrid(actions: List<RdpToolAction>) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = when {
        LocalDensity.current.fontScale > 1.2f -> 2
        actions.size == 4 -> if (landscape) 4 else 2
        else -> 3
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (item.selected) Palette.accentSoft else Palette.surface,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 84.dp)
                                .clickable(enabled = item.enabled, role = Role.Button, onClick = item.action)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val color = if (!item.enabled) Palette.muted else if (item.selected) Palette.accent else Palette.ink
                            Icon(painterResource(item.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                            Text(item.label, color = color, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
