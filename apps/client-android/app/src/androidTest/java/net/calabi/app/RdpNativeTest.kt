package net.calabi.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import net.calabi.app.rdp.RdpNative
import net.calabi.app.rdp.RdpSession
import org.json.JSONObject

/** Verifies the real packaged JNI/ABI on a phone without opening a network connection. */
class RdpNativeTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            check(RdpNative.available) { "Packaged RDP libraries could not load" }
            val callback = RdpSession(JSONObject().put("token", "native-self-test"))
            repeat(3) {
                val handle = RdpNative.create(callback, "100.64.0.8", "100.64.0.7", "ab".repeat(32), "test-user", "", "test-only")
                check(handle != 0L) { "JNI initialization failed" }
                val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                check(!RdpNative.blit(handle, bitmap)) { "Uninitialized frame should not render" }
                bitmap.recycle()
                RdpNative.destroy(handle)
            }
            check(RdpNative.create(callback, "not-an-address", "100.64.0.7", "ab".repeat(32), "test-user", "", "test-only") == 0L)
            check(RdpNative.create(callback, "100.64.0.8", "100.64.0.7", "wrong-pin", "test-user", "", "test-only") == 0L)
            callback.close()
            results.putString("stream", "\nAndroid RDP JNI load/lifecycle/invalid-target checks passed (no network)\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
