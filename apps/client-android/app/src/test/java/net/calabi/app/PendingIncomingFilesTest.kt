package net.calabi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingIncomingFilesTest {
    @Test fun badgeTracksTheRemainingFileCountAndClearsWhenTheNetworkChanges() {
        val session = SessionExpiryState()
        val original = session.requestGeneration()
        session.recordPendingIncomingFileCount(original, 3)
        assertEquals(3, session.pendingIncomingFileCount.value)
        session.recordPendingIncomingFileCount(original, 2)
        assertEquals(2, session.pendingIncomingFileCount.value)
        session.recordPendingIncomingFileCount(original, 0)
        assertEquals(0, session.pendingIncomingFileCount.value)

        session.recordPendingIncomingFileCount(original, 2)
        session.record(original, "POST", "/v1/orgs/switch", 200)
        assertEquals(0, session.pendingIncomingFileCount.value)
        session.recordPendingIncomingFileCount(original, 2)
        assertEquals(0, session.pendingIncomingFileCount.value)
        session.recordPendingIncomingFileCount(session.requestGeneration(), 1)
        assertEquals(1, session.pendingIncomingFileCount.value)
    }

    @Test fun expiredSessionClearsBadgeAndIgnoresLatePolls() {
        val session = SessionExpiryState()
        val generation = session.requestGeneration()
        session.recordPendingIncomingFileCount(generation, 1)
        session.record(generation, "GET", "/v1/me", 401)
        assertEquals(0, session.pendingIncomingFileCount.value)
        session.recordPendingIncomingFileCount(generation, 1)
        assertEquals(0, session.pendingIncomingFileCount.value)
    }
}
