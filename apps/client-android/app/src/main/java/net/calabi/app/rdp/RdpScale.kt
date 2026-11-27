package net.calabi.app.rdp

import net.calabi.app.R

/** Windows desktop DPI scaling transmitted in MCS Core Data and DISP monitor layout. */
internal enum class RdpScale(val title: Int, val percent: Int) {
    AUTO(R.string.rdp_scale_auto, 0),
    SCALE_100(R.string.rdp_scale_100, 100),
    SCALE_125(R.string.rdp_scale_125, 125),
    SCALE_150(R.string.rdp_scale_150, 150),
    SCALE_200(R.string.rdp_scale_200, 200);

    fun resolve(density: Float, quality: RdpQuality, maxViewportPx: Int = 0): Int = when {
        percent in 100..500 -> percent
        quality == RdpQuality.ECONOMY -> 100
        quality == RdpQuality.BALANCED -> if (density >= 2.5f) 125 else 100
        quality == RdpQuality.DETAIL -> if (density >= 2.25f) 150 else 125
        else -> when {
            maxViewportPx >= 2100 || density >= 2.75f -> 200
            density >= 2.0f -> 150
            else -> 125
        }
    }

    companion object {
        fun from(value: Int): RdpScale = entries.getOrElse(value) { AUTO }

        fun clampDesktopScale(percent: Int): Int = percent.coerceIn(100, 500)

        /** MS-RDPBCGR 2.2.1.3.2 / MS-RDPEDISP 2.2.2.2.1 only allow 100, 140, or 180. */
        fun deviceScaleFactor(desktopPercent: Int): Int = when {
            desktopPercent <= 100 -> 100
            desktopPercent <= 150 -> 140
            else -> 180
        }
    }
}
