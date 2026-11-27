package net.calabi.app

import net.calabi.app.rdp.RdpRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RdpRatesTest {
    @Test fun ratesUsePresentedFramesAndReceivedBytesOverActualTime() {
        val previous = sample(1_000, 100, 500_000)
        val current = sample(2_250, 130, 1_125_000)
        assertEquals(RdpRates(24.0, 4.0), RdpRates.between(previous, current))
        assertEquals(RdpRates(0.0, 0.0), RdpRates.between(current, sample(3_250, 130, 1_125_000)))
    }

    @Test fun unavailableOrResetSamplesDoNotShowSpikes() {
        val previous = sample(2_000, 20, 50_000)
        assertNull(RdpRates.between(longArrayOf(), previous))
        assertNull(RdpRates.between(previous, sample(2_000, 21, 50_010)))
        assertNull(RdpRates.between(previous, sample(3_000, 1, 10)))
    }

    private fun sample(timeMs: Long, presented: Long, bytes: Long): LongArray =
        longArrayOf(1920, 1080, 500, 450, 100, 200, 999_999_999, timeMs, presented, bytes)
}
