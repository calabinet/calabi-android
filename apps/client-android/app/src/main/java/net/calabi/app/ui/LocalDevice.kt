package net.calabi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.R

internal data class LocalDeviceInfo(val name: String, val address: String, val lastAddress: String) {
    val connected get() = address.isNotBlank()
}

internal fun localDeviceInfo(name: String, state: String, overlay: String, remembered: String): LocalDeviceInfo {
    val current = overlay.takeIf { state == "connected" }.orEmpty()
    return LocalDeviceInfo(name, current, if (current.isBlank()) remembered.ifBlank { overlay } else "")
}

internal val AppModel.localDevice: LocalDeviceInfo get() = localDeviceInfo(
    connection?.name.orEmpty().ifBlank { deviceName }, if (connected) connection?.state.orEmpty() else "stopped",
    connection?.overlay.orEmpty(), connection?.selfOverlay.orEmpty(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalDeviceRow(model: AppModel, last: Boolean) {
    val local = model.localDevice
    val context = LocalContext.current
    var details by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().padding(top = 8.dp)
        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
            bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp))
        .background(Palette.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { details = true }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.accentSoft), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_phone, Palette.accent, 24.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(local.name.ifBlank { stringResource(R.string.local_device_label) }, style = Styles.rowTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(stringResource(R.string.local_device_label), fontSize = 11.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold, color = Palette.accent,
                        modifier = Modifier.background(Palette.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (local.address.isNotBlank()) Text(local.address, style = Styles.mono.copy(lineHeight = 16.sp),
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.mobile_copy_address)) {
                            copyToClipboard(context, local.address)
                        })
                    Text(connectionTitle(model), style = Styles.hint.copy(color = if (local.connected) Palette.accent else Palette.muted))
                }
            }
            Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp)
        }
        if (!last) RowDivider(start = 68.dp)
    }
    if (details) LocalDeviceSheet(model) { details = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalDeviceSheet(model: AppModel, onDismiss: () -> Unit) {
    val local = model.localDevice
    val context = LocalContext.current
    val org = when {
        model.selfHosted -> model.server
        model.activeOrg?.personal == true -> stringResource(R.string.settings_org_personal)
        else -> model.activeOrg?.name.orEmpty().ifBlank { "—" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.ground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(local.name.ifBlank { stringResource(R.string.local_device_label) }, style = Styles.section)
            Text(stringResource(R.string.local_device_label), style = Styles.hint)
            Group {
                LocalInfoRow(stringResource(if (model.selfHosted) R.string.settings_server else R.string.settings_org), org)
                RowDivider()
                LocalInfoRow(stringResource(R.string.local_device_status), connectionTitle(model))
                if (local.address.isNotBlank() || local.lastAddress.isNotBlank()) {
                    RowDivider()
                    val address = local.address.ifBlank { local.lastAddress }
                    LocalInfoRow(stringResource(if (local.address.isNotBlank()) R.string.local_device_address else R.string.local_device_last_address),
                        address, mono = true, onCopy = { copyToClipboard(context, address) })
                }
                RowDivider()
                LocalInfoRow(stringResource(R.string.settings_exit), model.exitNode.ifBlank { stringResource(R.string.settings_exit_none) })
                RowDivider()
                LocalInfoRow(stringResource(R.string.settings_accept_routes), stringResource(if (model.acceptRoutes) R.string.local_device_enabled else R.string.local_device_disabled))
            }
        }
    }
}

@Composable
private fun LocalInfoRow(title: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (onCopy != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.mobile_copy_address), onClick = onCopy) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Styles.hint)
            Text(value, style = if (mono) Styles.mono else Styles.row)
        }
        if (onCopy != null) Glyph(R.drawable.ic_copy, Palette.muted, 18.dp)
    }
}
