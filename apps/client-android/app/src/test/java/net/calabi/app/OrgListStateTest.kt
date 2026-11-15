package net.calabi.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.calabi.app.ui.Org
import net.calabi.app.ui.OrgListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgListStateTest {
    private val choices = listOf(Org(1, "Personal", true), Org(2, "Team", false))

    @Test fun failedStartupCanRetryAndKeepsKnownChoicesOnLaterFailure() = runBlocking {
        var response: List<Org>? = null
        val state = OrgListState { response }
        state.refresh()
        assertTrue(state.failed)
        assertTrue(state.items.isEmpty())
        response = choices
        state.refresh()
        assertEquals(choices, state.items)
        assertFalse(state.failed)
        response = null
        state.refresh()
        assertTrue(state.failed)
        assertEquals(choices, state.items)
        response = emptyList()
        state.refresh()
        assertFalse(state.failed)
        assertTrue(state.items.isEmpty())
    }

    @Test fun overlappingRequestsDoNotDuplicateAndCancellationAllowsRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val state = OrgListState { requests++; started.complete(Unit); finish.await(); choices }
        val job = launch { state.refresh() }
        started.await()
        assertTrue(state.loading)
        state.refresh()
        assertEquals(1, requests)
        job.cancelAndJoin()
        assertFalse(state.loading)
        finish.complete(Unit)
        state.refresh()
        assertEquals(choices, state.items)
    }

    @Test fun unexpectedReadFailureIsVisibleAndRetryRecovers() = runBlocking {
        var offline = true
        val state = OrgListState { if (offline) error("unavailable") else choices }
        state.refresh()
        assertTrue(state.failed)
        assertFalse(state.loading)
        offline = false
        state.refresh()
        assertEquals(choices, state.items)
        assertFalse(state.failed)
    }
}
