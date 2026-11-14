package net.calabi.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import net.calabi.app.AppPrefs
import net.calabi.app.BackgroundRun
import net.calabi.app.BuildConfig
import net.calabi.app.CoreClient
import net.calabi.app.R
import org.json.JSONObject

private enum class SettingsPage { Home, Account, Connection, Device, Usage, Support, About, Organizations, Exit }

@Composable
fun SettingsTab(model: AppModel, onSignedOut: () -> Unit, onReplace: () -> Unit, onFileTest: () -> Unit = {}, onFiles: () -> Unit = {}, onDetailChanged: (Boolean) -> Unit = {}, initialAccount: Boolean = false, onRootBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(if (initialAccount) SettingsPage.Account else SettingsPage.Home) }
    var coreInfo by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(Unit) { coreInfo = CoreClient.call("GET", "/v1/version").json() }
    LaunchedEffect(page) {
        model.actions.dismissSuccess()
        onDetailChanged(page != SettingsPage.Home)
    }
    DisposableEffect(Unit) { onDispose { onDetailChanged(false) } }
    val pageState = rememberSaveableStateHolder()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var connectOnBoot by remember { mutableStateOf(AppPrefs.connectOnBoot(context)) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val backgroundRow = remember(lifecycle) { BackgroundRun.mayNeedAllowing(context) }
    val failed = stringResource(R.string.settings_save_failed)
    val saved = stringResource(R.string.mobile_saved)
    fun back() {
        if (initialAccount && page == SettingsPage.Account) { onRootBack(); return }
        page = when (page) {
        SettingsPage.Organizations -> SettingsPage.Account
        SettingsPage.Exit -> SettingsPage.Connection
        else -> SettingsPage.Home
    } }
    BackHandler(enabled = page != SettingsPage.Home && !renaming && !confirmSignOut, onBack = ::back)
    val title = stringResource(when (page) {
        SettingsPage.Home -> R.string.settings_title
        SettingsPage.Account -> R.string.mobile_settings_account
        SettingsPage.Connection -> R.string.mobile_settings_connection
        SettingsPage.Device -> R.string.mobile_settings_device
        SettingsPage.Usage -> R.string.usage_title
        SettingsPage.Support -> R.string.settings_support
        SettingsPage.About -> R.string.mobile_settings_about
        SettingsPage.Organizations -> R.string.settings_org
        SettingsPage.Exit -> R.string.settings_exit
    })
    FixedPage(title, onBack = if (page != SettingsPage.Home) ::back else null) {
        pageState.SaveableStateProvider(page.name) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page != SettingsPage.Organizations && page != SettingsPage.Exit) ActionFeedback(model.actions)
                when (page) {
                    SettingsPage.Home -> {
                        Group {
                            IconLinkRow(stringResource(R.string.mobile_settings_account), if (model.selfHosted) model.server else model.email, R.drawable.ic_user) { page = SettingsPage.Account }
                            RowDivider(start = 58.dp)
                            IconLinkRow(stringResource(R.string.mobile_settings_connection), stringResource(R.string.settings_exit) + " · " + model.exitNode.ifBlank { stringResource(R.string.settings_exit_none) }, R.drawable.ic_globe) { page = SettingsPage.Connection }
                            RowDivider(start = 58.dp)
                            IconLinkRow(stringResource(R.string.mobile_settings_device), model.deviceName, R.drawable.ic_phone) { page = SettingsPage.Device }
                            RowDivider(start = 58.dp)
                            IconLinkRow(stringResource(R.string.files_notification_settings), "", R.drawable.ic_notifications) {
                                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        }
                        Group {
                            IconLinkRow(stringResource(R.string.usage_title), stringResource(R.string.mobile_usage_hint), R.drawable.ic_chart) { page = SettingsPage.Usage }
                            RowDivider(start = 58.dp)
                            IconLinkRow(stringResource(R.string.settings_support), stringResource(R.string.settings_export_logs), R.drawable.ic_share) { page = SettingsPage.Support }
                            RowDivider(start = 58.dp)
                            IconLinkRow(stringResource(R.string.mobile_settings_about), BuildConfig.VERSION_NAME, R.drawable.ic_info) { page = SettingsPage.About }
                        }
                    }
                    SettingsPage.Account -> {
                        if (model.selfHosted) ServerGroup(model) else Group {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(model.email, style = Styles.rowTitle)
                            }
                            if (model.orgs.size > 1) {
                                RowDivider()
                                LinkRow(stringResource(R.string.settings_org),
                                    model.activeOrg?.let { if (it.personal) stringResource(R.string.settings_org_personal) else it.name }.orEmpty()) {
                                    page = SettingsPage.Organizations
                                }
                            }
                        }
                        Group {
                            ActionRow(stringResource(if (model.selfHosted) R.string.settings_leave_server else R.string.settings_sign_out),
                                onClick = { confirmSignOut = true }, icon = R.drawable.ic_logout, color = Palette.danger)
                        }
                    }
                    SettingsPage.Organizations -> {
                        Text(stringResource(R.string.settings_org_hint), style = Styles.hint)
                        Group { OrgChoices(model) { if (page == SettingsPage.Organizations) page = SettingsPage.Account } }
                    }
                    SettingsPage.Connection -> {
                        Group {
                            SubnetRoutesSwitch(model)
                            RowDivider()
                            SwitchRow(stringResource(R.string.settings_block_incoming), stringResource(R.string.settings_block_incoming_hint),
                                checked = model.blockIncoming, enabled = !model.actions.busy) { on ->
                                model.actions.run(failed, saved, operation = { model.saveSettings(JSONObject().put("block_incoming", on)) })
                            }
                            RowDivider()
                            LinkRow(stringResource(R.string.settings_exit), model.exitNode.ifBlank { stringResource(R.string.settings_exit_none) }) { page = SettingsPage.Exit }
                        }
                    }
                    SettingsPage.Exit -> {
                        Text(stringResource(R.string.settings_exit_hint), style = Styles.hint)
                        ExitChoices(model) { if (page == SettingsPage.Exit) page = SettingsPage.Connection }
                    }
                    SettingsPage.Device -> {
                        Group {
                            LinkRow(stringResource(R.string.settings_device_name), model.deviceName) { model.actions.clear(); renaming = true }
                            if (model.replaceable.isNotEmpty()) {
                                RowDivider()
                                ActionRow(stringResource(R.string.settings_replace), onClick = onReplace) {
                                    Text(model.replaceable.size.toString(), style = Styles.mono)
                                    Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp)
                                }
                            }
                            RowDivider()
                            SwitchRow(stringResource(R.string.settings_connect_on_boot), stringResource(R.string.settings_connect_on_boot_hint), checked = connectOnBoot) { on ->
                                AppPrefs.setConnectOnBoot(context, on); connectOnBoot = on
                            }
                            RowDivider()
                            QuickTileRow()
                            if (backgroundRow) {
                                RowDivider()
                                LinkRow(stringResource(R.string.settings_background), stringResource(BackgroundRun.hint())) { BackgroundRun.allow(context) }
                                BackgroundSteps(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                            }
                        }
                    }
                    SettingsPage.Usage -> UsageSection(model, showTitle = false)
                    SettingsPage.Support -> Group {
                        if (BuildConfig.FILE_TRANSFER_ENABLED) {
                            ActionRow(stringResource(R.string.files_title), onClick = onFiles) { Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp) }
                            RowDivider()
                        }
                        if (BuildConfig.DEBUG) {
                            ActionRow(stringResource(R.string.filetest_title), onClick = onFileTest) { Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp) }
                            RowDivider()
                        }
                        ActionRow(stringResource(R.string.settings_export_logs), icon = R.drawable.ic_share, onClick = {
                            scope.launch {
                                val logs = CoreClient.call("GET", "/v1/logs").body
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, logs), null))
                            }
                        })
                    }
                    SettingsPage.About -> Group {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${stringResource(R.string.product_android_version)}: ${BuildConfig.VERSION_NAME}", style = Styles.row)
                            Text("${stringResource(R.string.product_core_version)}: ${coreInfo.optString("version", "—")}", style = Styles.mono)
                            Text("${stringResource(R.string.product_core_commit)}: ${coreInfo.optString("commit", "—")}", style = Styles.hint)
                        }
                    }
                }
            }
        }
    }
    if (renaming) RenameDialog(model.deviceName, model.actions, onDismiss = { renaming = false }) { name ->
        model.actions.run(failed, saved, operation = { model.saveSettings(JSONObject().put("device_name", name)) }, onSuccess = { renaming = false })
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, containerColor = Palette.surface,
        title = { Text(stringResource(if (model.selfHosted) R.string.settings_leave_server_confirm else R.string.settings_sign_out_confirm), style = Styles.section) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(if (model.selfHosted) R.string.settings_leave_server_body else R.string.settings_sign_out_body), style = Styles.row); ActionFeedback(model.actions) } },
        confirmButton = { TextButton(enabled = !model.actions.busy, onClick = {
            model.actions.run(failed, saved, operation = { CoreClient.call("POST", "/v1/auth/logout") }, onSuccess = {
                confirmSignOut = false; onSignedOut()
            })
        }) { Text(stringResource(if (model.selfHosted) R.string.settings_leave_server else R.string.settings_sign_out), color = Palette.danger) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.settings_cancel)) } })
}

@Composable
private fun RenameDialog(current: String, actions: UiActionState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    val valid = !actions.busy && draft.isNotBlank() && draft.trim() != current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.ground,
        title = { Text(stringResource(R.string.settings_device_name), style = Styles.section) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionFeedback(actions)
            LabeledField(
                label = null, value = draft, onValueChange = { draft = it },
                imeAction = androidx.compose.ui.text.input.ImeAction.Done, onImeAction = { if (valid) onSave(draft.trim()) },
            )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(draft.trim()) }) {
                Text(stringResource(R.string.settings_save), color = if (valid) Palette.accent else Palette.muted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel), color = Palette.ink) }
        },
    )
}

/** Where the account would be: the self-hosted server this phone joined. */
@Composable
private fun ServerGroup(model: AppModel) {
    Group {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).background(Palette.avatar, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_server, Palette.ink, 22.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_server), style = Styles.rowTitle)
                Text(model.server, style = Styles.mono.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
