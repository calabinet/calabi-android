package net.calabi.app

import net.calabi.app.ui.isFileReconnectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAvailabilityTest {
    @Test fun freshOfflineInstallIsAnEmptyState() {
        assertTrue(isFileReconnectState(false, "connect to the mesh first", false))
        assertFalse(isFileReconnectState(true, "connect to the mesh first", false))
        assertFalse(isFileReconnectState(false, "connect to the mesh first", true))
    }

    @Test fun existingOfflineHistoryRemainsAvailableWithoutAWarning() {
        assertTrue(isFileReconnectState(false,
            "Reconnect to send or receive files. Local records and completed files remain available.", true))
    }

    @Test fun genuineFailuresAreNeverHiddenByAnEmptyList() {
        for (error in listOf("permission denied", "invalid private file history", "no space left on device",
                "file transfer is disabled in this build", "connection refused", "")) {
            assertFalse(error, isFileReconnectState(false, error, false))
        }
    }
}
