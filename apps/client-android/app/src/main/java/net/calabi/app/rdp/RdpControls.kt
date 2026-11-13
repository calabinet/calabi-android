package net.calabi.app.rdp

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import net.calabi.app.ui.Palette

/** A single recognizer distinguishes a short action from a spoken-name hint. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RdpIconControl(@DrawableRes icon: Int, label: String, enabled: Boolean = true,
    selected: Boolean? = null, tint: Color = Palette.ink, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.size(48.dp).clip(CircleShape)
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick,
            onLongClick = { android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show() })
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = label,
            tint = if (!enabled) Palette.muted else if (selected == true) Palette.accent else tint,
            modifier = Modifier.size(22.dp))
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

internal data class RdpToolAction(val icon: Int, val label: String, val enabled: Boolean = true,
    val selected: Boolean = false, val action: () -> Unit)

/** Labels wrap naturally at large font scales; the grid changes to two columns. */
@Composable
internal fun RdpToolGrid(actions: List<RdpToolAction>) {
    val columns = if (LocalDensity.current.fontScale > 1.2f) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Surface(modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(14.dp),
                        color = if (item.selected) Palette.accentSoft else Palette.surface) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 84.dp)
                            .clickable(enabled = item.enabled, role = Role.Button, onClick = item.action)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
