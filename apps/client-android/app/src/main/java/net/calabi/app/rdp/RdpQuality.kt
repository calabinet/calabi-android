package net.calabi.app.rdp

import net.calabi.app.R

/** Fixed modes preserve their stored ordinals. Adaptive starts at 720p until DISP is ready. */
internal enum class RdpQuality(val title: Int, val width: Int, val height: Int, val depth: Int, val frameMs: Long) {
    ECONOMY(R.string.rdp_quality_economy, 960, 540, 16, 66),
    BALANCED(R.string.rdp_quality_balanced, 1280, 720, 32, 33),
    DETAIL(R.string.rdp_quality_detail, 1920, 1080, 32, 16),
    ADAPTIVE(R.string.rdp_quality_adaptive, 1280, 720, 32, 33);

    companion object {
        fun from(value: Int) = entries.getOrElse(value) { BALANCED }
    }
}
