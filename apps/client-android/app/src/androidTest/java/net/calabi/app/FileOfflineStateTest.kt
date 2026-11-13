package net.calabi.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import net.calabi.app.ui.CalabiTheme
import net.calabi.app.ui.FileTransfersScreen

/** Uses a disposable app installation, never the user's signed-in app. */
class FileOfflineStateTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".offlinecheck")) { "Disposable test app required" }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            val snapshot = CoreClient.callBlocking("GET", "/v1/files")
            check(snapshot.ok && !snapshot.json().optBoolean("available"))
            check(snapshot.json().optString("error") == "connect to the mesh first")
            runOnMainSync {
                activity.setContent {
                    CalabiTheme {
                        FileTransfersScreen(null, emptyList(), {}, {}, emptyList(), false, {})
                    }
                }
            }
            fun texts(node: AccessibilityNodeInfo?, values: MutableSet<String>) {
                if (node == null) return
                node.text?.toString()?.let(values::add)
                for (i in 0 until node.childCount) texts(node.getChild(i), values)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var visible: Set<String>
            do {
                SystemClock.sleep(250)
                visible = mutableSetOf<String>().also { texts(uiAutomation.rootInActiveWindow, it) }
            } while (targetContext.getString(R.string.files_empty_records) !in visible && SystemClock.uptimeMillis() < deadline)
            // Wait across multiple refreshes, including the first completed API response.
            SystemClock.sleep(1500)
            visible = mutableSetOf<String>().also { texts(uiAutomation.rootInActiveWindow, it) }
            check(targetContext.getString(R.string.files_center) in visible)
            check(targetContext.getString(R.string.files_empty_records) in visible)
            check(targetContext.getString(R.string.files_incoming) in visible)
            check(targetContext.getString(R.string.files_error_generic) !in visible)
            check(targetContext.getString(R.string.files_unavailable) !in visible)
            result.putString("stream", "Real offline core response and rendered empty transfer page passed after polling; no warning.\n")
            finish(0, result)
        } catch (e: Throwable) {
            result.putString("stream", e.stackTraceToString())
            finish(1, result)
        }
    }
}
