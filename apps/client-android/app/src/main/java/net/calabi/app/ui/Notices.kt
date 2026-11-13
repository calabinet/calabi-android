package net.calabi.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.R

enum class NoticeTone { Info, Warning, Error }

/** Contextual feedback, with wrapping text and a fixed, accessible action target. */
@Composable
fun InlineNotice(message: String, modifier: Modifier = Modifier, tone: NoticeTone = NoticeTone.Info,
    title: String? = null, onDark: Boolean = false, @DrawableRes actionIcon: Int? = null, actionLabel: String = "", onAction: (() -> Unit)? = null) {
    val tint = when (tone) {
        NoticeTone.Info -> if (onDark) Color.White else Palette.muted
        NoticeTone.Warning -> Palette.relayText
        NoticeTone.Error -> Palette.danger
    }
    val background = if (onDark) Color.White.copy(alpha = 0.12f) else when (tone) {
        NoticeTone.Info -> Color.Transparent
        NoticeTone.Warning -> Palette.sunken
        NoticeTone.Error -> Palette.dangerSoft
    }
    Row(modifier.fillMaxWidth().background(background, RoundedCornerShape(12.dp))
        .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 12.dp, vertical = if (onAction == null) 10.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (tone == NoticeTone.Info) R.drawable.ic_info else R.drawable.ic_warning, tint, 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = Styles.rowTitle, color = if (onDark) Color.White else Palette.ink)
            Text(message, style = Styles.hint.copy(fontSize = 13.sp, color = if (onDark) Color.White else Palette.ink))
        }
        if (actionIcon != null && onAction != null) CircleButton(actionIcon, actionLabel, onAction)
    }
}

/** Short, non-interactive state beside the device or control it describes. */
@Composable
fun StatusBadge(label: String, modifier: Modifier = Modifier, description: String? = null) {
    Text(label, style = Styles.hint, modifier = modifier.background(Palette.sunken, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 6.dp).semantics { if (description != null) contentDescription = description })
}
