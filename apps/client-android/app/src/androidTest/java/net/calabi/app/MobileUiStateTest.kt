package net.calabi.app

import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.*
import net.calabi.app.ui.UiActionState

/** Verifies observable operation outcomes without changing a real account or network setting. */
class MobileUiStateTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val state = UiActionState(this)
                val gate = CompletableDeferred<Unit>()
                var calls = 0
                var successes = 0
                state.run("failed", "saved", operation = { calls++; gate.await(); ApiResult(200, "{}") }, onSuccess = { successes++ })
                state.run("failed", "saved", operation = { calls++; ApiResult(200, "{}") })
                check(state.busy)
                yield()
                check(calls == 1 && successes == 0) { "Repeat submission must be rejected while pending" }
                gate.complete(Unit)
                yield()
                check(!state.busy && state.message == "saved" && !state.failed && successes == 1)
                state.run("failed", "saved", operation = { ApiResult(500, "{}") }, onSuccess = { successes++ })
                yield()
                check(!state.busy && state.failed && !state.message.isNullOrEmpty() && successes == 1) { "Failure must not navigate or close the editor" }
                state.clear()
                check(state.message == null)
                state.run("exception", "saved", operation = { error("simulated transport failure") }, onSuccess = { successes++ })
                yield()
                check(!state.busy && state.failed && state.message == "exception" && successes == 1)
                val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val pending = UiActionState(owner)
                pending.run("failed", "saved", operation = { CompletableDeferred<Unit>().await(); ApiResult(200, "{}") })
                check(pending.busy)
                owner.cancel()
                check(!pending.busy && pending.message == null)
            }
            result.putString("stream", "Operation gate, success, server failure, exception, dismissal and owner cancellation passed.\n")
            finish(0, result)
        } catch (e: Throwable) {
            result.putString("stream", e.stackTraceToString())
            finish(1, result)
        }
    }
}
