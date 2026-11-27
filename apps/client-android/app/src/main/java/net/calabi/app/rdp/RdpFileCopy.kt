package net.calabi.app.rdp

import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.calabi.app.CalabiApp
import net.calabi.app.R
import org.json.JSONObject

private data class ClipboardDocument(val offer: String, val index: Int, val name: String)

@Composable
internal fun RdpFileCopy(session: RdpSession, visible: Boolean, dismiss: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val core = CalabiApp.instance.core
    val scope = rememberCoroutineScope()
    val interactive by session.interactive.collectAsState()
    var files by remember(session) { mutableStateOf(emptyList<ClipboardDocument>()) }
    var ready by remember(session) { mutableStateOf(false) }
    var busy by remember(session) { mutableStateOf(false) }
    var message by remember(session) { mutableIntStateOf(0) }
    var destination by remember(session) { mutableStateOf<ClipboardDocument?>(null) }

    // Renew even when the file dialog is closed so Windows can paste an offered file.
    LaunchedEffect(session) {
        while (isActive) {
            val snapshot = if (session.state.value.first == 1) withContext(Dispatchers.IO) {
                runCatching { JSONObject(core.rdpCopyPoll(session.ticket)) }.getOrNull()
            } else null
            ready = snapshot != null
            val offer = snapshot?.optString("offer").orEmpty()
            val entries = snapshot?.optJSONArray("files")
            files = if (offer.isEmpty() || entries == null) emptyList() else buildList {
                for (index in 0 until entries.length()) {
                    val name = entries.optJSONObject(index)?.optString("name").orEmpty()
                    if (name.isNotEmpty()) add(ClipboardDocument(offer, index, name))
                }
            }
            delay(if (ready) 2000 else 4000)
        }
    }

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            message = 0
            scope.launch {
                val sent = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: error("Missing document name")
                        val file = resolver.openFileDescriptor(uri, "r") ?: error("Cannot open document")
                        file.use { core.rdpCopyOffer(session.ticket, name, it.detachFd()) }
                    }.isSuccess
                }
                message = if (sent) R.string.rdp_file_copy_offered else R.string.rdp_file_copy_failed
                busy = false
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = destination
        destination = null
        if (uri != null && selected != null && !busy) {
            busy = true
            message = 0
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    val result = runCatching {
                        val file = resolver.openFileDescriptor(uri, "wt") ?: error("Cannot open destination")
                        file.use { core.rdpCopySave(session.ticket, selected.offer, selected.index, it.detachFd()) }
                    }.isSuccess
                    if (!result) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    result
                }
                message = if (saved) R.string.rdp_file_copy_saved else R.string.rdp_file_copy_failed
                busy = false
            }
        }
    }

    if (visible) AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.rdp_file_copy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (ready) R.string.rdp_file_copy_hint else R.string.rdp_file_copy_waiting))
                OutlinedButton(enabled = ready && interactive && !busy, onClick = { choose.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.rdp_file_copy_choose))
                }
                for (file in files) OutlinedButton(enabled = ready && interactive && !busy, onClick = {
                    destination = file
                    save.launch(file.name)
                }) { Text(file.name) }
                if (busy) Text(stringResource(R.string.rdp_file_copy_busy))
                else if (message != 0) Text(stringResource(message))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
