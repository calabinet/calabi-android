package net.calabi.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.calabi.app.BuildConfig
import net.calabi.app.CoreClient
import net.calabi.app.R
import net.calabi.app.rdp.*
import org.json.JSONObject
import java.time.Instant

/** History is useful while disconnected, but opening a device requires current core authority. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(model: AppModel) {
    val context = LocalContext.current
    val store = remember { RdpRecentStore(context) }
    var records by remember { mutableStateOf(emptyList<RdpRecent>()) }
    var targets by remember { mutableStateOf(emptyList<JSONObject>()) }
    var identity by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var storageFailed by remember { mutableStateOf(false) }
    var serviceFailed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RdpRecent?>(null) }
    var removing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(model.mode, model.server, model.activeOrgId, model.email, revision) {
        loading = true; records = emptyList(); targets = emptyList(); identity = null
        while (true) {
            val state = CoreClient.call("GET", "/v1/state")
            val current = if (state.ok) RdpCredentialStore.scope(state.json(), BuildConfig.BFF_URL) else null
            if (identity != current) { pending = null; records = emptyList(); targets = emptyList() }
            identity = current
            val loaded = withContext(Dispatchers.IO) { runCatching { if (current != null) store.load(current) else emptyList() } }
            records = loaded.getOrDefault(emptyList())
            val response = if (current != null && state.json().optBoolean("connected")) CoreClient.call("GET", "/v1/remote/rdp/targets") else null
            val items = response?.json()?.optJSONArray("items")
            targets = if (response?.ok == true && items != null) (0 until items.length()).map { items.getJSONObject(it) } else emptyList()
            storageFailed = loaded.isFailure
            serviceFailed = !state.ok || response?.ok == false || (response?.ok == true && items == null)
            loading = false
            delay(2000)
        }
    }
    val problem = when {
        storageFailed -> R.string.remote_recent_failed
        serviceFailed -> R.string.remote_service_failed
        else -> null
    }
    FixedPage(stringResource(R.string.remote_tab), actions = {
        if (!model.connected) StatusBadge(stringResource(R.string.state_stopped), description = stringResource(R.string.remote_recent_connect_hint))
    }) {
        LazyVerticalGrid(columns = GridCells.Fixed(if (LocalDensity.current.fontScale >= 1.3f) 1 else 2),
            modifier = Modifier.fillMaxSize(), state = gridState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.remote_recent)) }
            if (problem != null) item(span = { GridItemSpan(maxLineSpan) }) {
                InlineNotice(stringResource(problem), tone = NoticeTone.Error, actionIcon = R.drawable.ic_refresh,
                    actionLabel = stringResource(R.string.rdp_retry), onAction = { revision++ })
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            else if (records.isEmpty() && problem == null) item(span = { GridItemSpan(maxLineSpan) }) {
                Group { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Glyph(R.drawable.ic_monitor, Palette.muted, 28.dp)
                    Text(stringResource(R.string.remote_recent_empty), style = Styles.hint)
                } }
            }
            items(records, key = { "${it.deviceKey}/${it.port}" }) { record ->
                val target = targets.find { it.optString("device_key") == record.deviceKey }
                val available = model.connected && target?.optBoolean("available") == true && identity != null
                val name = target?.optString("name")?.ifBlank { null } ?: record.name
                val overlay = target?.optString("overlay")?.ifBlank { null } ?: record.overlay
                val open = {
                    context.startActivity(Intent(context, RdpActivity::class.java).putExtra("overlay", overlay)
                        .putExtra("device_key", record.deviceKey).putExtra("credential_scope", identity).putExtra("port", record.port))
                }
                Group {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Palette.ink)
                        .clickable(enabled = available, role = Role.Button, onClick = open), contentAlignment = Alignment.Center) {
                        RemotePreview(store, identity, record, name)
                        Box(Modifier.align(Alignment.TopEnd).size(48.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(28.dp).background(Palette.surface, CircleShape))
                            CircleButton(R.drawable.ic_close, stringResource(R.string.remote_recent_remove)) { pending = record }
                        }
                    }
                    Column(Modifier.fillMaxWidth().clickable(enabled = available, role = Role.Button, onClick = open).padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = Styles.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(overlay, style = Styles.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.rdp_port) + " " + record.port, style = Styles.hint, maxLines = 1)
                            }
                            Text(formatRecent(context, Instant.ofEpochMilli(record.usedAt)), style = Styles.hint)
                            if (model.connected && !available) Text(stringResource(RdpErrors.reason(target?.optString("reason").orEmpty())), style = Styles.hint)
                    }
                }
            }
        }
    }
    val operationScope = rememberCoroutineScope()
    pending?.let { record ->
        AlertDialog(onDismissRequest = { if (!removing) pending = null },
            title = { Text(stringResource(R.string.remote_recent_remove)) },
            text = { Text(stringResource(R.string.remote_recent_remove_hint)) },
            confirmButton = { TextButton(enabled = !removing, onClick = {
                val scope = identity ?: return@TextButton
                removing = true
                operationScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { store.remove(scope, record) } }
                    removing = false
                    if (result.isSuccess) { pending = null; records = records.filterNot { it.deviceKey == record.deviceKey && it.port == record.port } }
                    else storageFailed = true
                }
            }) { Text(stringResource(R.string.files_remove)) } },
            dismissButton = { TextButton(enabled = !removing, onClick = { pending = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}


@Composable
private fun RemotePreview(store: RdpRecentStore, identity: String?, record: RdpRecent, name: String) {
    var image by remember(identity, record) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(identity, record) {
        image = withContext(Dispatchers.IO) { runCatching { identity?.let { store.loadPreview(it, record) } }.getOrNull() }
    }
    val preview = image
    if (preview != null) Image(preview.asImageBitmap(), contentDescription = stringResource(R.string.remote_recent_preview, name),
        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else Column(Modifier.fillMaxSize().background(Palette.sunken), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(R.drawable.ic_monitor, Palette.muted, 36.dp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.remote_recent_no_preview), style = Styles.hint)
    }
}
