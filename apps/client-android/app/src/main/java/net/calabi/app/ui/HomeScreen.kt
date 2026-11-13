package net.calabi.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.calabi.app.R
import net.calabi.app.BuildConfig
import net.calabi.app.CoreClient
import android.net.Uri
import org.json.JSONObject

private enum class Sheet { Exit, Connection, Account, Replace }

/** A screen opened from a tab, drawn in its place with a back button. */
private sealed interface Route : java.io.Serializable {
    data object Account : Route
    data object FileTest : Route
    data class TunnelDetail(val tunnel: Tunnel) : Route
    data class TunnelAccess(val tunnel: Tunnel) : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onConnect: () -> Unit, onDisconnect: () -> Unit, onSignedOut: () -> Unit, shared: List<Uri> = emptyList(), onShareTaken: () -> Unit = {}, showFiles: Boolean = false, onFilesTaken: () -> Unit = {}) {
    val operationScope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val model = remember { AppModel(UiActionState(operationScope, context)) }
    var initialLoading by remember { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var route by rememberSaveable { mutableStateOf<Route?>(null) }
    var filePeer by rememberSaveable { mutableStateOf<String?>(null) }
    var fileReview by rememberSaveable { mutableStateOf(false) }
    var fileSending by remember { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    var settingsDetail by remember { mutableStateOf(false) }
    LaunchedEffect(tab, route, sheet, fileSending, model.mode, model.server, model.activeOrgId) {
        model.actions.dismissSuccess()
    }
    DisposableEffect(model) { onDispose { model.actions.dismissSuccess() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { model.actions.dismissSuccess() }
    LaunchedEffect(shared) { if (BuildConfig.FILE_TRANSFER_ENABLED && shared.isNotEmpty()) { tab = 3; route = null; filePeer = "" } }
    LaunchedEffect(showFiles) { if (BuildConfig.FILE_TRANSFER_ENABLED && showFiles) { tab = 3; route = null; fileReview = true; onFilesTaken() } }

    // Live state every 2s while the screen is up; the org's device list less often.
    LaunchedEffect(Unit) {
        // Keep partial initial data out of the composition, including the
        // navigation bar. Later polling must not replace an already usable page.
        coroutineScope {
            launch { model.refreshAccount() }
            launch { model.refreshLive() }
            launch { model.refreshNodes() }
        }
        initialLoading = false
        launch { model.refreshReplaceable() }
        var tick = 1
        while (true) {
            delay(2000)
            model.refreshLive()
            if (tick % 5 == 0) model.refreshNodes()
            if (tick % 30 == 0) model.refreshReplaceable()
            tick++
        }
    }

    if (initialLoading) {
        AppLoadingScreen()
        return
    }

    BackHandler(enabled = route != null) {
        route = when (val r = route) {
            is Route.TunnelAccess -> Route.TunnelDetail(r.tunnel)
            else -> null
        }
    }

    BackHandler(enabled = route == null && tab != 0) { tab = 0 }
    LaunchedEffect(route) { if (route is Route.TunnelDetail || route is Route.TunnelAccess) model.refreshTunnels() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(containerColor = Palette.ground, topBar = {
        Column(Modifier.background(Palette.surface).statusBarsPadding()) {
            Header(model, onAccount = { if (model.selfHosted) { tab = 2; route = Route.Account } else sheet = Sheet.Account },
                onConnection = { sheet = Sheet.Connection })
        }
    }, bottomBar = { if (!imeVisible && route == null && !(tab == 3 && fileSending) && !(tab == 2 && settingsDetail)) BottomNav(tab) { tab = it } }) { padding ->
        Box(Modifier.padding(padding).imePadding().fillMaxSize()) {
            val screenKey = when (val r = route) {
                Route.Account -> "account"
                Route.FileTest -> "file-test"
                is Route.TunnelDetail -> "detail-${r.tunnel.id}"
                is Route.TunnelAccess -> "access-${r.tunnel.id}"
                null -> "tab-$tab"
            }
            screenState.SaveableStateProvider("${model.mode}|${model.server}|${model.activeOrgId}|$screenKey") {
            when (val r = route) {
                Route.Account -> SettingsTab(model, onSignedOut = { onDisconnect(); onSignedOut() }, onReplace = { sheet = Sheet.Replace }, initialAccount = true, onRootBack = { route = null })
                Route.FileTest -> FileTransferTestScreen(model, onBack = { route = null })
                is Route.TunnelDetail -> TunnelDetailScreen(
                    model, r.tunnel, onBack = { route = null }, onAccess = { route = Route.TunnelAccess(r.tunnel) },
                )
                is Route.TunnelAccess -> TunnelAccessScreen(r.tunnel, onBack = { route = Route.TunnelDetail(r.tunnel) })
                null -> when (tab) {
                    0 -> DevicesTab(
                        model,
                        onReplace = { sheet = Sheet.Replace },
                        onFiles = { filePeer = it; tab = 3 },
                    )
                    1 -> TunnelsTab(model) { route = Route.TunnelDetail(it) }
                    4 -> RemoteScreen(model)
                    3 -> if (BuildConfig.FILE_TRANSFER_ENABLED) FileTransfersScreen(
                        initialPeer = filePeer, shared = shared, onShareTaken = onShareTaken,
                        onSendOpened = { filePeer = null },
                        devices = model.devices,
                        reviewIncoming = fileReview, onReviewOpened = { fileReview = false },
                        onSendPageChanged = { fileSending = it },
                    )
                    else -> SettingsTab(model, onSignedOut = {
                        // Logout stops the core; also retire the Android VPN
                        // service, notification and Quick Settings state.
                        onDisconnect()
                        onSignedOut()
                    }, onReplace = { sheet = Sheet.Replace }, onFileTest = { route = Route.FileTest }, onFiles = { tab = 3 }, onDetailChanged = { settingsDetail = it })
                }
            }
            }
        }
    }

    if (sheet != null) {
        ModalBottomSheet(modifier = Modifier.statusBarsPadding(), onDismissRequest = { sheet = null }, containerColor = Palette.ground,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (sheet) {
                    Sheet.Exit -> {
                        TextButton(onClick = { sheet = Sheet.Connection }) { Text(stringResource(R.string.back)) }
                        SheetTitle(stringResource(R.string.settings_exit), stringResource(R.string.settings_exit_hint))
                        ExitChoices(model) { if (sheet == Sheet.Exit) sheet = Sheet.Connection }
                    }
                    Sheet.Connection -> {
                        SheetTitle(stringResource(R.string.mesh_network_settings), stringResource(R.string.mobile_settings_connection))
                        ActionFeedback(model.actions)
                        ConnectionSummary(model)
                        Group {
                            LinkRow(stringResource(R.string.settings_exit), model.exitNode.ifBlank { stringResource(R.string.settings_exit_none) }) { sheet = Sheet.Exit }
                            RowDivider()
                            SubnetRoutesSwitch(model)
                        }
                        if (model.connected) DisconnectControl(onDisconnect = { sheet = null; onDisconnect() })
                        else PrimaryButton(stringResource(R.string.action_connect), enabled = !model.actions.busy, onClick = { sheet = null; onConnect() })
                    }
                    Sheet.Account -> {
                        SheetTitle(stringResource(R.string.mesh_my), model.email)
                        Text(stringResource(R.string.mesh_current_org), style = Styles.sectionLabel, modifier = Modifier.padding(horizontal = 8.dp))
                        Group { OrgChoices(model) { sheet = null } }
                        Group {
                            ActionRow(stringResource(R.string.account), icon = R.drawable.ic_settings, onClick = { sheet = null; route = Route.Account })
                        }
                    }
                    Sheet.Replace -> {
                        SheetTitle(stringResource(R.string.replace_title), stringResource(R.string.replace_hint))
                        ReplaceChoices(model) { sheet = null }
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = Styles.section)
        Text(hint, style = Styles.hint.copy(fontSize = 13.sp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DevicesTab(
    model: AppModel,
    onReplace: () -> Unit,
    onFiles: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var receivingPeers by remember(model.connected, model.activeOrgId, model.mode, model.server) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(model.connected, model.activeOrgId, model.mode, model.server) {
        if (BuildConfig.FILE_TRANSFER_ENABLED && model.connected) {
            while (true) {
                val result = CoreClient.call("GET", "/v1/files")
                val snapshot = result.json()
                val peers = if (result.ok && snapshot.optBoolean("available")) snapshot.optJSONArray("peers") else null
                receivingPeers = buildSet {
                    if (peers != null) for (i in 0 until peers.length()) {
                        val p = peers.optJSONObject(i) ?: continue
                        if (p.optBoolean("receiving") && p.optString("ip").isNotBlank()) add(p.optString("ip"))
                    }
                }
                delay(2000)
            }
        }
    }
    val devices = model.devices
    val visibleDevices = devices.filter { (filter == 1 || it.online == (filter == 0)) && matchesListSearch(search, it.name, it.overlay, it.ownerEmail) }
    val inUseExit = model.connection?.exitNode.orEmpty().ifBlank { model.exitNode }
    FixedPage(stringResource(R.string.devices_title), actions = {
        if (devices.isNotEmpty()) Text(stringResource(R.string.devices_online, devices.count { it.online }, devices.size), style = Styles.hint)
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { ActionFeedback(model.actions) }
            item { StoppedPrompt(model) }
            item { ReplacePrompt(model, onReplace) }
            item { CertChangedPrompt(model) }
            item { ApprovalPrompt(model) }
            item { ShieldsPrompt(model) }
            item { UnreachablePrompt(model) }
            item { TilePrompt(model) }
            item("device-search") {
                Column(Modifier.padding(horizontal = 22.dp)) {
                    ListSearchInput(search, { search = it }, stringResource(R.string.list_search_devices))
                    ListFilterTabs(listOf(stringResource(R.string.device_online), stringResource(R.string.mesh_all), stringResource(R.string.device_offline)), filter) { filter = it }
                }
            }

            if (model.nodesNeedConnection) {
                item {
                    Group(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.devices_connect_to_see), style = Styles.hint.copy(fontSize = 14.sp),
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
            model.nodesError?.let { err ->
                item { InlineNotice(err, tone = NoticeTone.Error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (model.nodes != null && visibleDevices.isEmpty()) {
                item {
                    Group(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(if (search.isNotBlank() || devices.isNotEmpty()) R.string.list_search_no_matches else R.string.devices_empty), style = Styles.hint.copy(fontSize = 14.sp),
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
            itemsIndexed(visibleDevices, key = { _, d -> d.overlay + d.name }) { i, d ->
                DeviceRow(d, first = i == 0, last = i == visibleDevices.lastIndex, isExit = d.name == inUseExit || d.overlay == inUseExit,
                    fileDisabledReason = when {
                        !model.connected -> R.string.state_stopped
                        !d.online -> R.string.device_offline
                        d.overlay !in receivingPeers -> R.string.files_peer_disabled
                        else -> null
                    }, onFiles = { onFiles(d.overlay) })
            }
        }
    }
}

/** Shared chrome stays outside each tab's scrolling content. */
@Composable
private fun Header(model: AppModel, onAccount: () -> Unit, onConnection: () -> Unit) {
    val org = model.activeOrg
    val orgLabel = when {
        model.selfHosted -> model.server
        org == null -> stringResource(R.string.settings_org)
        org.personal -> stringResource(R.string.settings_org_personal)
        else -> org.name
    }
    // The account API has an email, without a separate display-name field.
    val username = if (model.selfHosted) model.deviceName.ifBlank { stringResource(R.string.settings_server) }
        else model.email.substringBefore('@').ifBlank { stringResource(R.string.account) }
    val initials = remember(orgLabel) { organizationInitials(orgLabel) }
    val accountDescription = stringResource(if (model.selfHosted) R.string.settings_server else R.string.mesh_my)
    val status = connectionTitle(model)
    val networkDescription = stringResource(R.string.mesh_network_settings)
    val phase = connectionPhase(model)
    val statusColor = when (phase) {
        Phase.Off -> Palette.muted
        Phase.Problem -> Palette.danger
        else -> Palette.accent
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onAccount)
            .semantics(mergeDescendants = true) { contentDescription = accountDescription },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).background(Palette.accentSoft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(initials, style = Styles.chip.copy(color = Palette.accent), maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(username, style = Styles.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(orgLabel, style = Styles.hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.widthIn(max = 132.dp).heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp)).background(when (phase) {
                Phase.Off -> Palette.sunken
                Phase.Problem -> Palette.dangerSoft
                else -> Palette.accentSoft
            }).clickable(role = Role.Button, onClick = onConnection)
            .semantics(mergeDescendants = true) { contentDescription = "$status · $networkDescription" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(statusColor, 6.dp)
            Text(status, style = Styles.hint.copy(color = statusColor, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Initials from words, camel-case capitals, or the first two Unicode code points. */
internal fun organizationInitials(label: String): String {
    val words = label.trim().split(Regex("[\\s._:/-]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val points = label.trim().codePoints().toArray()
    val initials = if (words.size > 1) words.take(2).map { it.codePointAt(0) }
        else points.filter { Character.isUpperCase(it) }.take(2).takeIf { it.size > 1 } ?: points.take(2)
    return initials.joinToString("") { String(Character.toChars(it)) }.uppercase(java.util.Locale.ROOT)
}

private enum class Phase { Off, Busy, On, Problem }

private fun connectionPhase(model: AppModel): Phase = when {
    !model.connected -> Phase.Off
    model.connection?.state == "connected" -> Phase.On
    model.connection?.state in setOf("not_enrolled", "signed_out", "needs_invite", "disabled", "cert_changed") -> Phase.Problem
    else -> Phase.Busy
}

@Composable
private fun connectionTitle(model: AppModel): String = stringResource(when (connectionPhase(model)) {
    Phase.Off -> R.string.state_stopped
    Phase.On -> R.string.state_connected
    Phase.Busy -> if (model.connection?.state == "retrying") R.string.state_retrying else R.string.state_connecting
    Phase.Problem -> when (model.connection?.state) {
        "signed_out" -> R.string.state_signed_out_short
        "needs_invite" -> R.string.state_needs_invite_short
        "disabled" -> R.string.state_disabled_short
        "cert_changed" -> R.string.state_cert_changed_short
        else -> R.string.state_not_enrolled_short
    }
})

@Composable
private fun ConnectionSummary(model: AppModel) {
    val c = model.connection
    val subtitle = when (connectionPhase(model)) {
        Phase.Off -> stringResource(R.string.hero_tap_to_connect)
        Phase.On -> "${c?.name.orEmpty()} · ${c?.overlay.orEmpty()}"
        Phase.Problem -> stringResource(when (c?.state) {
            "signed_out" -> R.string.state_signed_out
            "needs_invite" -> R.string.state_needs_invite
            "disabled" -> R.string.state_disabled
            "cert_changed" -> R.string.state_cert_changed
            else -> R.string.state_not_enrolled
        })
        Phase.Busy -> c?.error.orEmpty()
    }
    Column(Modifier.fillMaxWidth().background(Palette.accentSoft, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(connectionTitle(model), style = Styles.rowTitle.copy(color = if (connectionPhase(model) == Phase.Problem) Palette.danger else Palette.accent))
        if (subtitle.isNotBlank()) Text(subtitle, style = Styles.hint)
        if (!model.selfHosted) Text(model.activeOrg?.let { if (it.personal) stringResource(R.string.settings_org_personal) else it.name }.orEmpty(), style = Styles.hint)
    }
}

@Composable
private fun DisconnectControl(onDisconnect: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_disconnect), color = Palette.danger) }
    if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.action_disconnect)) }, text = { Text(stringResource(R.string.mesh_disconnect_hint)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDisconnect() }) { Text(stringResource(R.string.action_disconnect)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) } })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(d: Device, first: Boolean, last: Boolean, isExit: Boolean, fileDisabledReason: Int?, onFiles: () -> Unit) {
    var actionsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .padding(top = if (first) 8.dp else 0.dp)
            .clip(RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
                bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp))
            .background(Palette.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button) { actionsOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.sunken),
                contentAlignment = Alignment.Center,
            ) { Glyph(osIcon(d.os), if (d.online) Palette.ink else Palette.muted, 20.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        d.name, style = Styles.rowTitle.copy(color = if (d.online) Palette.ink else Palette.muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isExit) {
                        Text(
                            stringResource(R.string.badge_exit), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.accent,
                            modifier = Modifier.background(Palette.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.overlay, style = Styles.mono.copy(lineHeight = 16.sp), maxLines = 1)
                    Trailing(d)
                }
            }
            if (d.online && d.os == "windows") CircleButton(R.drawable.ic_monitor, stringResource(R.string.mesh_desktop)) {
                context.startActivity(android.content.Intent(context, net.calabi.app.rdp.RdpActivity::class.java).putExtra("overlay", d.overlay))
            } else Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp)
        }
        if (!last) RowDivider(start = 68.dp)
    }
    if (actionsOpen) DeviceActionsSheet(d, fileDisabledReason, onDismiss = { actionsOpen = false }, onFiles = {
        actionsOpen = false
        onFiles()
    })
}

@Composable
private fun Trailing(d: Device) {
    if (!d.online) {
        Text(stringResource(R.string.device_offline), fontSize = 12.sp, color = Palette.muted)
        return
    }
    val relay = d.path == "relay"
    val label = when (d.path) {
        "direct" -> stringResource(R.string.path_direct)
        "relay" -> stringResource(R.string.path_relay)
        else -> stringResource(R.string.device_online)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Dot(if (relay) Palette.relayDot else Palette.accent, 6.dp)
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (relay) Palette.relayText else if (d.path == null) Palette.muted else Palette.accent)
        if (d.path != null && d.rttMicros > 0) Text(stringResource(R.string.rtt_ms, (d.rttMicros / 1000).toInt()),
            style = Styles.mono.copy(fontSize = 11.sp, lineHeight = 16.sp))
    }
}

/** The exit-device choices, shared by the sheet and the settings tab. */
@Composable
fun ExitChoices(model: AppModel, onPicked: () -> Unit = {}) {
    val failed = stringResource(R.string.settings_save_failed)
    val saved = stringResource(R.string.mobile_saved)
    val candidates = model.devices.filter { it.offersExit }
    fun pick(value: String) {
        if (value == model.exitNode) { onPicked(); return }
        model.actions.run(failed, saved, operation = { model.saveSettings(JSONObject().put("exit_node", value)) }, onSuccess = onPicked)
    }
    ActionFeedback(model.actions)
    Group {
        ChoiceRow(stringResource(R.string.settings_exit_none), selected = model.exitNode.isBlank(), enabled = !model.actions.busy) { pick("") }
        candidates.forEach { d ->
            RowDivider()
            ChoiceRow(d.name, selected = model.exitNode == d.name || model.exitNode == d.overlay,
                detail = pathDetail(d), enabled = !model.actions.busy) { pick(d.name) }
        }
    }
    if (candidates.isEmpty()) {
        // "Nobody offers an exit" is a fact about a list we HAVE. With no list
        // there is nothing to conclude — saying "this server has none" after
        // failing to reach that server is stating what we could not read.
        val empty = when {
            model.nodes != null && model.selfHosted -> R.string.settings_exit_empty_server
            model.nodes != null -> R.string.settings_exit_empty
            // A self-hosted server's devices come from the live session.
            model.selfHosted && model.nodesNeedConnection -> R.string.settings_exit_connect
            else -> R.string.settings_exit_unknown
        }
        Text(stringResource(empty), style = Styles.hint, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** The organization choices, shared by the sheet and the settings tab. */
@Composable
fun OrgChoices(model: AppModel, onPicked: () -> Unit = {}) {
    val failed = stringResource(R.string.settings_save_failed)
    val saved = stringResource(R.string.mobile_saved)
    val personal = stringResource(R.string.settings_org_personal)
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    ActionFeedback(model.actions)
    model.orgs.forEach { org ->
        ChoiceRow(if (org.personal) personal else org.name, selected = org.id == model.activeOrgId, enabled = !model.actions.busy) {
            if (org.id == model.activeOrgId) onPicked()
            else pendingId = org.id
        }
    }
    model.orgs.find { it.id == pendingId }?.let { target ->
        val from = model.activeOrg?.let { if (it.personal) personal else it.name }.orEmpty()
        val to = if (target.personal) personal else target.name
        androidx.compose.material3.AlertDialog(onDismissRequest = { if (!model.actions.busy) pendingId = null },
            title = { Text(stringResource(R.string.mesh_switch_org)) },
            text = { Column { Text(stringResource(R.string.mesh_switch_confirm, from, to)); ActionFeedback(model.actions) } },
            confirmButton = { TextButton(enabled = !model.actions.busy, onClick = {
                model.actions.run(failed, saved, operation = { model.switchOrg(target.id) }, onSuccess = { pendingId = null; onPicked() })
            }) { Text(stringResource(R.string.mesh_switch_org)) } },
            dismissButton = { TextButton(enabled = !model.actions.busy, onClick = { pendingId = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
private fun pathDetail(d: Device): String? {
    val ms = (d.rttMicros / 1000).toInt()
    return when {
        !d.online -> stringResource(R.string.device_offline)
        d.path == "direct" -> if (ms > 0) stringResource(R.string.path_rtt, stringResource(R.string.path_direct), ms) else stringResource(R.string.path_direct)
        d.path == "relay" -> if (ms > 0) stringResource(R.string.path_rtt, stringResource(R.string.path_relay), ms) else stringResource(R.string.path_relay)
        else -> null
    }
}

internal fun osIcon(os: String): Int = when (os) {
    "windows" -> R.drawable.ic_monitor
    "darwin" -> R.drawable.ic_laptop
    "android", "ios" -> R.drawable.ic_phone
    else -> R.drawable.ic_server
}

private fun osName(os: String): String = when (os) {
    "linux" -> "Linux"
    "windows" -> "Windows"
    "darwin" -> "macOS"
    "android" -> "Android"
    "ios" -> "iOS"
    else -> os
}

/**
 * The phone cannot reach what it reads from.
 *
 * This is the ONE place that explains it. The tunnels list and the usage card
 * used to print their own copy of the same failure, so switching tabs taught
 * the same lesson three times — and what they printed was the gRPC dialer's
 * error string, in English, at a person holding a phone.
 */
@Composable
private fun UnreachablePrompt(model: AppModel) {
    if (!model.unreachable) return
    Group(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineNotice(stringResource(if (model.selfHosted) R.string.unreachable_body_server else R.string.unreachable_body_platform),
                title = stringResource(R.string.unreachable_title), tone = NoticeTone.Warning)
            if (model.selfHosted && model.server.isNotBlank()) {
                Text(model.server, style = Styles.mono, maxLines = 1)
            }
        }
    }
}

/**
 * This phone is refusing every inbound connection.
 *
 * A phone with the switch on looks exactly like a healthy one on this screen —
 * connected, addressed, the whole device list present — and the switch itself is
 * two taps away on another tab. A setting whose effect is invisible on the page
 * people actually look at is a support call.
 */
@Composable
private fun ShieldsPrompt(model: AppModel) {
    if (!model.blockIncoming) return
    InlineNotice(stringResource(R.string.shields_body), title = stringResource(R.string.shields_title), tone = NoticeTone.Warning,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
}

/** Joined, and waiting for the server's administrator to approve this phone. */
@Composable
private fun ApprovalPrompt(model: AppModel) {
    if (!model.selfHosted || !model.awaitingApproval) return
    InlineNotice(stringResource(R.string.approval_body), title = stringResource(R.string.approval_title), tone = NoticeTone.Warning,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
}
