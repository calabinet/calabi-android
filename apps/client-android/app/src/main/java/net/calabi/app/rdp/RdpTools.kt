package net.calabi.app.rdp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.ui.InlineNotice
import net.calabi.app.ui.NoticeTone
import net.calabi.app.R

internal data class RdpShortcut(val label: String, val modifiers: IntArray, val scan: Int)
internal object RdpShortcuts {
    val items = listOf(
        RdpShortcut("Ctrl+A", intArrayOf(0x1d), 0x1e), RdpShortcut("Ctrl+C", intArrayOf(0x1d), 0x2e),
        RdpShortcut("Ctrl+V", intArrayOf(0x1d), 0x2f), RdpShortcut("Ctrl+X", intArrayOf(0x1d), 0x2d),
        RdpShortcut("Ctrl+Z", intArrayOf(0x1d), 0x2c), RdpShortcut("Ctrl+Y", intArrayOf(0x1d), 0x15),
        RdpShortcut("Ctrl+F", intArrayOf(0x1d), 0x21), RdpShortcut("Ctrl+S", intArrayOf(0x1d), 0x1f),
        RdpShortcut("Ctrl+Shift+Z", intArrayOf(0x1d, 0x2a), 0x2c),
        RdpShortcut("Ctrl+Home", intArrayOf(0x1d), 0x147), RdpShortcut("Ctrl+End", intArrayOf(0x1d), 0x14f),
        RdpShortcut("Shift+Tab", intArrayOf(0x2a), 0x0f),
        RdpShortcut("Shift+←", intArrayOf(0x2a), 0x14b), RdpShortcut("Shift+→", intArrayOf(0x2a), 0x14d),
        RdpShortcut("Shift+Home", intArrayOf(0x2a), 0x147), RdpShortcut("Shift+End", intArrayOf(0x2a), 0x14f),
        RdpShortcut("Ctrl+←", intArrayOf(0x1d), 0x14b), RdpShortcut("Ctrl+→", intArrayOf(0x1d), 0x14d),
        RdpShortcut("Alt+Tab", intArrayOf(0x38), 0x0f), RdpShortcut("Alt+F4", intArrayOf(0x38), 0x3e),
        RdpShortcut("Win+D", intArrayOf(0x15b), 0x20), RdpShortcut("Win+E", intArrayOf(0x15b), 0x12),
        RdpShortcut("Ctrl+Shift+Esc", intArrayOf(0x1d, 0x2a), 0x01),
        RdpShortcut("Ctrl+Alt+End", intArrayOf(0x1d, 0x38), 0x14f),
        RdpShortcut("←", intArrayOf(), 0x14b), RdpShortcut("↑", intArrayOf(), 0x148),
        RdpShortcut("→", intArrayOf(), 0x14d), RdpShortcut("↓", intArrayOf(), 0x150),
        RdpShortcut("Home", intArrayOf(), 0x147), RdpShortcut("End", intArrayOf(), 0x14f),
        RdpShortcut("PgUp", intArrayOf(), 0x149), RdpShortcut("PgDn", intArrayOf(), 0x151),
        RdpShortcut("Delete", intArrayOf(), 0x153), RdpShortcut("F2", intArrayOf(), 0x3c), RdpShortcut("F5", intArrayOf(), 0x3f),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RdpShortcutDialog(session: RdpSession, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.rdp_shortcuts)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RdpShortcuts.items.forEach { shortcut ->
                    TextButton(onClick = {
                        session.view?.shortcut(shortcut.modifiers, shortcut.scan)
                        dismiss()
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(shortcut.label, fontSize = 12.sp) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
}

internal object RdpClipboardText {
    const val MAX = 32768
    fun windows(text: String): String {
        val result = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
        require(result.length <= MAX && '\u0000' !in result)
        var i = 0
        while (i < result.length) {
            val c = result[i++]
            require(!c.isLowSurrogate())
            if (c.isHighSurrogate()) require(i < result.length && result[i++].isLowSurrogate())
        }
        return result
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RdpClipboardDialog(session: RdpSession, dismiss: () -> Unit) {
    val manager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val ready by session.clipboardReady.collectAsState()
    val result by session.clipboardResult.collectAsState()
    var text by remember { mutableStateOf("") }
    var request by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(0) }
    DisposableEffect(session) { onDispose { session.cancelClipboard() } }
    LaunchedEffect(result.id) {
        if (result.id != 0 && result.id == request) {
            busy = false
            message = when (result.error) {
                0 -> if (result.sending) R.string.rdp_clip_sent else R.string.rdp_clip_received
                2 -> R.string.rdp_clip_no_text
                3 -> R.string.rdp_clip_invalid
                4 -> R.string.rdp_clip_timeout
                5 -> R.string.rdp_clip_changed
                else -> R.string.rdp_clip_unavailable
            }
            if (result.error == 0 && result.text != null) text = result.text!!
        }
    }
    LaunchedEffect(ready) { if (!ready && busy) { session.cancelClipboard(); busy = false; message = R.string.rdp_clip_unavailable } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.rdp_clipboard)) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rdp_clip_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = text, onValueChange = { if (it.length <= RdpClipboardText.MAX) text = it else message = R.string.rdp_clip_invalid },
                enabled = !busy, label = { Text(stringResource(R.string.rdp_clip_text)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            FlowRow {
                TextButton(enabled = !busy, onClick = {
                    val clip = manager.primaryClip
                    val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    if (value == null) message = R.string.rdp_clip_no_text
                    else if (value.length > RdpClipboardText.MAX) message = R.string.rdp_clip_invalid
                    else { text = value; message = 0 }
                }) { Text(stringResource(R.string.rdp_clip_paste_phone)) }
                TextButton(enabled = !busy, onClick = {
                    manager.setPrimaryClip(ClipData.newPlainText("Calabi", text)); message = R.string.rdp_clip_copied
                }) { Text(stringResource(R.string.rdp_clip_copy_phone)) }
            }
            Button(enabled = ready && !busy, onClick = {
                val outgoing = runCatching { RdpClipboardText.windows(text) }.getOrNull()
                if (outgoing == null) message = R.string.rdp_clip_invalid
                else { busy = true; message = 0; request = session.clipboard(outgoing) }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rdp_clip_send)) }
            OutlinedButton(enabled = ready && !busy, onClick = {
                busy = true; message = 0; request = session.clipboard()
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rdp_clip_get)) }
            if (!ready && message == 0) InlineNotice(stringResource(R.string.rdp_clip_unavailable), tone = NoticeTone.Warning)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message != 0) InlineNotice(stringResource(message), tone = if (message in setOf(R.string.rdp_clip_sent, R.string.rdp_clip_received, R.string.rdp_clip_copied)) NoticeTone.Info else NoticeTone.Warning)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
}
