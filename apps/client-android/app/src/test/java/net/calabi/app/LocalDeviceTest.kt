package net.calabi.app

import net.calabi.app.ui.localDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class LocalDeviceTest {
    @Test fun connectedUsesAssignedAddress() {
        val local = localDeviceInfo("phone", "connected", "100.64.0.45", "100.64.0.12")
        assertTrue(local.connected)
        assertEquals("100.64.0.45", local.address)
        assertEquals("", local.lastAddress)
    }
    @Test fun disconnectedOrRetryingNeverClaimsRememberedAddressIsCurrent() {
        for (state in listOf("stopped", "retrying", "connecting", "disabled")) {
            val local = localDeviceInfo("phone", state, "", "100.64.0.45")
            assertFalse(local.connected)
            assertEquals("", local.address)
            assertEquals("100.64.0.45", local.lastAddress)
        }
    }
    @Test fun neverJoinedHasNoAddress() {
        val local = localDeviceInfo("phone", "stopped", "", "")
        assertEquals("phone", local.name)
        assertEquals("", local.address)
        assertEquals("", local.lastAddress)
    }
}
