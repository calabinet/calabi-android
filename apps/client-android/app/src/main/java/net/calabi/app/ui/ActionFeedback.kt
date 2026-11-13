package net.calabi.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.calabi.app.ApiResult
import net.calabi.app.R

/** Owned by Home, so leaving a settings subpage does not cancel a submitted operation. */
class UiActionState(private val scope: CoroutineScope, private val context: Context) {
    private var successToast: Toast? = null
    private var feedbackPage = 0L
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    fun dismissSuccess() {
        feedbackPage++
        successToast?.cancel()
        successToast = null
    }
    fun clear() { message = null; dismissSuccess() }
    fun run(failure: String, success: String, operation: suspend () -> ApiResult, onSuccess: () -> Unit = {}) {
        if (busy) return
        dismissSuccess()
        val submittedPage = feedbackPage
        busy = true
        message = null
        scope.launch {
            try {
                val result = operation()
                failed = !result.ok
                message = if (result.ok) null else result.error(failure)
                if (result.ok) {
                    onSuccess()
                    // Do not show a late completion on a different page.
                    if (submittedPage == feedbackPage) {
                        successToast = Toast.makeText(context, success, Toast.LENGTH_SHORT).also { it.show() }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; message = failure }
            finally { busy = false }
        }
    }
}

@Composable
fun ActionFeedback(state: UiActionState) {
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent, trackColor = Palette.line)
        Text(stringResource(R.string.mobile_saving), style = Styles.hint, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.message?.let { message ->
        InlineNotice(message, tone = if (state.failed) NoticeTone.Error else NoticeTone.Info,
            actionIcon = R.drawable.ic_close, actionLabel = stringResource(R.string.mobile_dismiss), onAction = state::clear)
    }
}
