package net.calabi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCapabilitiesTest {
    @Test fun releaseRegistersRemoteDesktopWithoutEnablingExperiments() {
        val config = coreCapabilities(debug = false)
        assertTrue(config.getValue("remote_desktop_p0"))
        assertTrue(config.getValue("file_transfer_v1"))
        assertFalse(config.getValue("file_transfer_p0"))
    }

    @Test fun debugOnlyChangesTheExperimentalCapability() {
        val release = coreCapabilities(debug = false)
        val debug = coreCapabilities(debug = true)
        assertTrue(debug.getValue("file_transfer_p0"))
        assertEquals(release.getValue("remote_desktop_p0"), debug.getValue("remote_desktop_p0"))
        assertEquals(release.getValue("file_transfer_v1"), debug.getValue("file_transfer_v1"))
    }
}
