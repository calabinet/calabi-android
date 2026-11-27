package net.calabi.app.rdp

internal data class RdpRates(val framesPerSecond: Double, val megabitsPerSecond: Double) {
    companion object {
        fun between(previous: LongArray, current: LongArray): RdpRates? {
            if (previous.size < 10 || current.size < 10) return null
            val elapsedMs = current[7] - previous[7]
            val presentedFrames = current[8] - previous[8]
            val receivedBytes = current[9] - previous[9]
            if (elapsedMs <= 0 || presentedFrames < 0 || receivedBytes < 0) return null
            return RdpRates(presentedFrames * 1000.0 / elapsedMs, receivedBytes * 0.008 / elapsedMs)
        }
    }
}
