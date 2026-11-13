package net.calabi.app.rdp

import net.calabi.app.R

/** Map stable core reasons and FreeRDP codes; no credentials or raw server messages enter the UI. */
internal object RdpErrors {
    fun reason(value: String): Int = when (value) {
        "same_owner_required" -> R.string.rdp_same_owner
        "certificate_unavailable" -> R.string.rdp_certificate_missing
        "identity_unavailable", "remote_identity_changed" -> R.string.rdp_identity_changed
        "windows_required" -> R.string.rdp_windows_required
        else -> R.string.remote_target_unavailable
    }
    fun session(state: Int, message: String): Int {
        if (state == 9) return R.string.rdp_repair_timeout
        if (state == 10) return R.string.rdp_connect_timeout
        if (state == 4) return R.string.rdp_background_expired
        if (state == 5 || state == 8) return R.string.rdp_identity_changed
        val code = Regex("0x([0-9a-fA-F]{8})").find(message)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
        return when (code) {
            0x20009, 0x20014, 0x20015, 0x2001b -> R.string.rdp_auth_failed
            0x2000a, 0x2000e, 0x2000f, 0x20010, in 0x20012..0x20013,
            in 0x20016..0x2001a -> R.string.rdp_account_restricted
            0x20004, 0x20005, 0x20006, 0x20007, 0x20008, 0x2000c, 0x2000d,
            0x20011, 0x2001c, 0x2001d, 0x2001e -> R.string.rdp_network_failed
            else -> R.string.rdp_session_ended
        }
    }
}
