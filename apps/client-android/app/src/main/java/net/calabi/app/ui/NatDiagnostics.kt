package net.calabi.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.calabi.app.R
import org.json.JSONObject

/** Result of GET /v1/netcheck from the embedded Go core. */
data class NetCheckReport(
    val natType: String,
    val holePunch: String,
    val overlayIp: String,
    val localIp: String,
    val localIps: List<String>,
    val publicIp: String,
    val publicPort: Int,
    val ipv6: String,
    val portVaries: Boolean?,
    val probeSource: String,
    val detail: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): NetCheckReport {
            val locals = buildList {
                val arr = obj.optJSONArray("local_ips")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) add(s)
                    }
                }
            }
            val varies = if (obj.has("port_varies") && !obj.isNull("port_varies")) obj.optBoolean("port_varies") else null
            return NetCheckReport(
                natType = obj.optString("nat_type", "unknown").ifBlank { "unknown" },
                holePunch = obj.optString("hole_punch", "unknown").ifBlank { "unknown" },
                overlayIp = obj.optString("overlay_ip"),
                localIp = obj.optString("local_ip"),
                localIps = locals,
                publicIp = obj.optString("public_ip"),
                publicPort = obj.optInt("public_port", 0),
                ipv6 = obj.optString("ipv6"),
                portVaries = varies,
                probeSource = obj.optString("probe_source"),
                detail = obj.optString("detail"),
            )
        }
    }
}

internal fun maskIpAddress(ip: String): String {
    if (ip.isBlank()) return ""
    if ('.' in ip) {
        val parts = ip.split('.')
        if (parts.size == 4) return "${parts[0]}.${parts[1]}.***.***"
    }
    if (':' in ip) {
        val parts = ip.split(':')
        return "${parts.take(2).joinToString(":")}:****:****"
    }
    return "***"
}

internal fun formatPublicEndpoint(ip: String, port: Int, masked: Boolean): String {
    if (ip.isBlank()) return "—"
    val base = if (masked) maskIpAddress(ip) else ip
    return if (!masked && port > 0) "$base:$port" else base
}

/** Underlying physical network transport (ignoring the app's own VPN transport). */
internal fun detectNetworkCarrier(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val active = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(active) ?: return null
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> null
    }
}

@Composable
internal fun natTypeLabel(natType: String): String = stringResource(
    when (natType) {
        "open" -> R.string.nat_type_open
        "full_cone" -> R.string.nat_type_full_cone
        "restricted_cone" -> R.string.nat_type_restricted_cone
        "port_restricted_cone" -> R.string.nat_type_port_restricted_cone
        "cone" -> R.string.nat_type_cone
        "symmetric" -> R.string.nat_type_symmetric
        "udp_blocked" -> R.string.nat_type_udp_blocked
        else -> R.string.nat_type_unknown
    },
)

@Composable
private fun natVerdictLabel(verdict: String): String = stringResource(
    when (verdict) {
        "direct" -> R.string.nat_verdict_direct
        "supported" -> R.string.nat_verdict_supported
        "limited" -> R.string.nat_verdict_limited
        "blocked" -> R.string.nat_verdict_blocked
        else -> R.string.nat_verdict_unknown
    },
)

@Composable
private fun natTypeDescription(natType: String): String = stringResource(
    when (natType) {
        "open" -> R.string.nat_desc_open
        "full_cone" -> R.string.nat_desc_full_cone
        "restricted_cone" -> R.string.nat_desc_restricted_cone
        "port_restricted_cone" -> R.string.nat_desc_port_restricted_cone
        "cone" -> R.string.nat_desc_cone
        "symmetric" -> R.string.nat_desc_symmetric
        "udp_blocked" -> R.string.nat_desc_udp_blocked
        else -> R.string.nat_desc_unknown
    },
)

@Composable
private fun carrierLabel(carrier: String?): String = when (carrier) {
    "wifi" -> stringResource(R.string.nat_carrier_wifi)
    "cellular" -> stringResource(R.string.nat_carrier_cellular)
    "ethernet" -> stringResource(R.string.nat_carrier_ethernet)
    else -> ""
}

private fun natBadgeColors(verdict: String): Pair<Color, Color> = when (verdict) {
    "direct", "supported" -> Palette.accent to Palette.accentSoft
    "limited" -> Palette.relayText to Color(0xFFFAF0DC)
    "blocked" -> Palette.danger to Palette.dangerSoft
    else -> Palette.muted to Palette.sunken
}

/** Compact local network summary inside the top-right connection sheet. */
@Composable
fun NatCompactCard(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var maskPublic by rememberSaveable { mutableStateOf(true) }
    val carrier = remember(model.connection, model.netCheck) { detectNetworkCarrier(context) }

    LaunchedEffect(Unit) {
        model.runNetCheck(force = false)
    }

    val report = model.netCheck
    val overlayIp = report?.overlayIp.orEmpty()
        .ifBlank { model.connection?.overlay.orEmpty() }
        .ifBlank { model.connection?.selfOverlay.orEmpty() }

    Group {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.nat_card_title), style = Styles.rowTitle, modifier = Modifier.weight(1f))
                val cLabel = carrierLabel(carrier)
                if (cLabel.isNotBlank()) {
                    Text(
                        cLabel,
                        style = Styles.hint.copy(color = Palette.label, fontWeight = FontWeight.Medium),
                        modifier = Modifier.background(Palette.sunken, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !model.netCheckLoading, role = Role.Button) {
                            scope.launch { model.runNetCheck(force = true) }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (model.netCheckLoading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.accent)
                    } else {
                        Glyph(R.drawable.ic_refresh, Palette.accent, 14.dp)
                    }
                    Text(stringResource(R.string.nat_recheck), style = Styles.hint.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold))
                }
            }

            if (report == null && model.netCheckLoading) {
                Text(stringResource(R.string.nat_probing), style = Styles.hint)
            } else if (model.netCheckError != null && report == null) {
                Text(model.netCheckError.orEmpty(), style = Styles.hint.copy(color = Palette.danger))
            } else if (report != null) {
                val (fg, bg) = natBadgeColors(report.holePunch)
                Column(
                    Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(natTypeLabel(report.natType), style = Styles.chip.copy(color = fg))
                    Text(natVerdictLabel(report.holePunch), style = Styles.hint.copy(color = fg))
                }
                IpValueRow(
                    label = stringResource(R.string.nat_overlay_ip),
                    displayValue = overlayIp.ifBlank { stringResource(R.string.nat_not_connected) },
                    copyValue = overlayIp.takeIf { it.isNotBlank() },
                )
                IpValueRow(
                    label = stringResource(R.string.nat_local_ip),
                    displayValue = report.localIp.ifBlank { "—" },
                    copyValue = report.localIp.takeIf { it.isNotBlank() },
                )
                IpValueRow(
                    label = stringResource(R.string.nat_public_ip),
                    displayValue = formatPublicEndpoint(report.publicIp, report.publicPort, maskPublic),
                    copyValue = report.publicIp.takeIf { it.isNotBlank() },
                    maskToggle = if (report.publicIp.isNotBlank()) {
                        Pair(
                            stringResource(if (maskPublic) R.string.nat_show_public else R.string.nat_hide_public),
                            { maskPublic = !maskPublic },
                        )
                    } else null,
                )
                if (report.ipv6.isNotBlank()) {
                    IpValueRow(
                        label = stringResource(R.string.nat_ipv6),
                        displayValue = if (maskPublic) maskIpAddress(report.ipv6) else report.ipv6,
                        copyValue = report.ipv6,
                    )
                }
            }
        }
    }
}

/** Full Network & NAT diagnostics detail page inside Settings -> Connection. */
@Composable
fun NatDiagnosticsDetail(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var maskPublic by rememberSaveable { mutableStateOf(true) }
    val carrier = remember(model.connection, model.netCheck) { detectNetworkCarrier(context) }

    LaunchedEffect(Unit) {
        model.runNetCheck(force = false)
    }

    val report = model.netCheck
    val overlayIp = report?.overlayIp.orEmpty()
        .ifBlank { model.connection?.overlay.orEmpty() }
        .ifBlank { model.connection?.selfOverlay.orEmpty() }

    Group {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report == null && model.netCheckLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.accent)
                    Text(stringResource(R.string.nat_probing), style = Styles.row)
                }
            } else if (model.netCheckError != null && report == null) {
                Text(model.netCheckError.orEmpty(), style = Styles.row.copy(color = Palette.danger))
            } else if (report != null) {
                val (fg, bg) = natBadgeColors(report.holePunch)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        natTypeLabel(report.natType),
                        style = Styles.chip.copy(color = fg),
                        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    val cLabel = carrierLabel(carrier)
                    if (cLabel.isNotBlank()) {
                        Text(
                            cLabel,
                            style = Styles.hint.copy(color = Palette.label, fontWeight = FontWeight.Medium),
                            modifier = Modifier.background(Palette.sunken, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(natVerdictLabel(report.holePunch), style = Styles.rowTitle)
                Text(natTypeDescription(report.natType), style = Styles.hint)
                if (report.detail == "single_stun_server") {
                    Text(stringResource(R.string.nat_single_stun_hint), style = Styles.hint)
                }
            }
            Pill(
                onClick = { if (!model.netCheckLoading) scope.launch { model.runNetCheck(force = true) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (model.netCheckLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
                } else {
                    Glyph(R.drawable.ic_refresh, Palette.ink, 16.dp)
                }
                Text(stringResource(R.string.nat_recheck), style = Styles.chip)
            }
        }
    }

    if (report != null) {
        SectionLabel(stringResource(R.string.nat_ip_section))
        Group {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IpValueRow(
                    label = stringResource(R.string.nat_overlay_ip),
                    displayValue = overlayIp.ifBlank { stringResource(R.string.nat_not_connected) },
                    copyValue = overlayIp.takeIf { it.isNotBlank() },
                )
                RowDivider(start = 0.dp)
                IpValueRow(
                    label = stringResource(R.string.nat_local_ip),
                    displayValue = report.localIp.ifBlank { "—" },
                    copyValue = report.localIp.takeIf { it.isNotBlank() },
                )
                RowDivider(start = 0.dp)
                IpValueRow(
                    label = stringResource(R.string.nat_public_ip),
                    displayValue = formatPublicEndpoint(report.publicIp, report.publicPort, maskPublic),
                    copyValue = report.publicIp.takeIf { it.isNotBlank() },
                    maskToggle = if (report.publicIp.isNotBlank()) {
                        Pair(
                            stringResource(if (maskPublic) R.string.nat_show_public else R.string.nat_hide_public),
                            { maskPublic = !maskPublic },
                        )
                    } else null,
                )
                if (report.ipv6.isNotBlank()) {
                    RowDivider(start = 0.dp)
                    IpValueRow(
                        label = stringResource(R.string.nat_ipv6),
                        displayValue = if (maskPublic) maskIpAddress(report.ipv6) else report.ipv6,
                        copyValue = report.ipv6,
                    )
                }
            }
        }
    }

    SectionLabel(stringResource(R.string.nat_matrix_title))
    Group {
        val rows = listOf(
            Triple(R.string.nat_type_full_cone, R.string.nat_matrix_nat1_punch, R.string.nat_matrix_nat1_note),
            Triple(R.string.nat_type_restricted_cone, R.string.nat_matrix_nat2_punch, R.string.nat_matrix_nat2_note),
            Triple(R.string.nat_type_port_restricted_cone, R.string.nat_matrix_nat3_punch, R.string.nat_matrix_nat3_note),
            Triple(R.string.nat_type_symmetric, R.string.nat_matrix_nat4_punch, R.string.nat_matrix_nat4_note),
        )
        rows.forEachIndexed { idx, (typeRes, punchRes, noteRes) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(typeRes), style = Styles.rowTitle, modifier = Modifier.weight(1f))
                    val isLimited = idx == 3
                    Text(
                        stringResource(punchRes),
                        style = Styles.hint.copy(
                            color = if (isLimited) Palette.relayText else Palette.accent,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                Text(stringResource(noteRes), style = Styles.hint)
            }
            if (idx < rows.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun IpValueRow(
    label: String,
    displayValue: String,
    copyValue: String?,
    maskToggle: Pair<String, () -> Unit>? = null,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = Styles.hint)
            Text(displayValue, style = Styles.monoValue.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (maskToggle != null) {
            Text(
                maskToggle.first,
                style = Styles.hint.copy(color = Palette.accent, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = maskToggle.second)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (copyValue != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { copyToClipboard(context, copyValue) },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(R.drawable.ic_copy, Palette.muted, 16.dp)
            }
        }
    }
}
