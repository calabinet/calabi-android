package net.calabi.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.calabi.app.BuildConfig
import net.calabi.app.R
import net.calabi.app.rdp.RdpActivity
import org.json.JSONObject

/** Opened by the whole device row; addresses have their own explicit copy action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActionsSheet(device: Device, fileDisabledReason: Int?, onDismiss: () -> Unit, onFiles: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.ground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(device.name, style = Styles.section)
            Text(stringResource(if (device.online) R.string.device_online else R.string.device_offline), style = Styles.row)
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.overlay, style = Styles.monoValue)
                    if (device.ownerEmail.isNotBlank()) Text(device.ownerEmail, style = Styles.hint)
                }
                ActionRow(stringResource(R.string.mobile_copy_address), icon = R.drawable.ic_copy,
                    onClick = { copyToClipboard(context, device.overlay) })
            }
            if (device.os == "windows") {
                PrimaryButton(stringResource(R.string.rdp_preview_title), enabled = device.online, onClick = {
                    onDismiss()
                    context.startActivity(Intent(context, RdpActivity::class.java).putExtra("overlay", device.overlay))
                })
            }
            if (BuildConfig.FILE_TRANSFER_ENABLED) {
                val label = stringResource(R.string.files_new_send)
                OutlinedButton(onClick = onFiles, enabled = fileDisabledReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (fileDisabledReason == null) label else stringResource(R.string.files_send_unavailable, label, stringResource(fileDisabledReason)))
                }
            }
        }
    }
}

/** Shared by Home and Settings so saving, errors and repeat-tap protection agree. */
@Composable
fun SubnetRoutesSwitch(model: AppModel) {
    val failure = stringResource(R.string.settings_save_failed)
    val onMessage = stringResource(R.string.mobile_saved)
    val offMessage = stringResource(R.string.mobile_saved)
    SwitchRow(stringResource(R.string.settings_accept_routes), stringResource(R.string.settings_accept_routes_hint),
        checked = model.acceptRoutes, enabled = !model.actions.busy, onCheckedChange = { next ->
            model.actions.run(failure, if (next) onMessage else offMessage,
                operation = { model.saveSettings(JSONObject().put("accept_routes", next)) })
        })
}
