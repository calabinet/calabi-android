package net.calabi.app.rdp

import android.media.MediaCodecList
import android.os.Build

/** Pick a named hardware AVC decoder, never treat MediaCodec software as hardware. */
internal object RdpHardware {
    val decoder: String = runCatching {
        // Earlier Android releases cannot reliably distinguish vendor software codecs.
        if (Build.VERSION.SDK_INT < 29) return@runCatching ""
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", true) } &&
                codec.isHardwareAccelerated && !codec.isSoftwareOnly &&
                runCatching { codec.getCapabilitiesForType("video/avc").videoCapabilities.isSizeSupported(1920, 1080) }.getOrDefault(false)
        }?.name.orEmpty()
    }.getOrDefault("")
}
