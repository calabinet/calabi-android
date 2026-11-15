package net.calabi.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** Refreshable choices; a failed request must not erase an already loaded list. */
internal class OrgListState(private val fetch: suspend () -> List<Org>?) {
    var items by mutableStateOf(emptyList<Org>())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun refresh() {
        if (loading) return
        loading = true
        failed = false
        try {
            val result = fetch()
            if (result == null) failed = true else items = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
}
