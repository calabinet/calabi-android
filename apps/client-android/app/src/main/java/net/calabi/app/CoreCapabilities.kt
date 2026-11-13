package net.calabi.app

/** Shipping capabilities must remain enabled in release builds; only experiments depend on debug. */
internal fun coreCapabilities(debug: Boolean): Map<String, Boolean> = mapOf(
    "file_transfer_p0" to debug,
    "file_transfer_v1" to BuildConfig.FILE_TRANSFER_ENABLED,
    "remote_desktop_p0" to BuildConfig.REMOTE_DESKTOP_ENABLED,
)
