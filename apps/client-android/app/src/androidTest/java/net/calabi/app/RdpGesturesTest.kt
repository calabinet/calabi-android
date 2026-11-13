package net.calabi.app

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import net.calabi.app.rdp.RdpActivity
import net.calabi.app.rdp.RdpGestures
import net.calabi.app.rdp.RdpViewport
import net.calabi.app.rdp.RdpView
import net.calabi.app.rdp.RdpInputQueue
import net.calabi.app.rdp.RdpKeyboard
import net.calabi.app.rdp.RdpClipboardText
import net.calabi.app.rdp.RdpShortcuts
import net.calabi.app.rdp.RdpSession
import net.calabi.app.rdp.RdpMouse
import net.calabi.app.rdp.RdpScan
import net.calabi.app.rdp.RdpErrors
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Device regression plus optional ADB-driven, real-window multi-touch against the saved target. */
class RdpGesturesTest : Instrumentation() {
    private var live = false
    private var quality = 1
    private var classic = false
    private var referenceCodec = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); live = arguments?.getString("live") == "true"; quality = arguments?.getString("quality")?.toIntOrNull() ?: 1; classic = arguments?.getString("classic") == "true"; referenceCodec = arguments?.getString("referenceCodec") == "true"; start() }

    private fun motion(action: Int, time: Long, points: List<Pair<Float, Float>>, down: Long): MotionEvent {
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 1f; size = 1f } }.toTypedArray()
        return MotionEvent.obtain(down, time, action, points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun regression() {
        val viewport = RdpViewport().apply { configure(1000, 800, 1280, 720) }
        val center = viewport.remote(500f, 400f)!!
        check(abs(center.x - 640) < 0.01f && abs(center.y - 360) < 0.01f)
        check(viewport.remote(500f, 5f) == null)
        val anchor = viewport.remote(400f, 300f)!!
        viewport.scale(2f, 400f, 300f)
        val same = viewport.remote(400f, 300f)!!
        check(abs(anchor.x - same.x) < 0.01f && abs(anchor.y - same.y) < 0.01f)
        viewport.pan(100000f, -100000f)
        check(viewport.bounds.left <= 0.01f && viewport.bounds.right >= 999.99f)
        check(viewport.bounds.top <= 0.01f && viewport.bounds.bottom >= 799.99f)
        viewport.configure(500, 1200, 1280, 720)
        check(viewport.zoom == 2f && viewport.remote(250f, 600f) != null)
        viewport.scale(100f); check(viewport.zoom == 5f)
        viewport.scale(0.001f); check(viewport.zoom == 1f)
        viewport.configure(1000, 800, 1280, 720); viewport.reset()
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        var consumed = 0
        val gestures = RdpGestures(viewport, 10f, 50f, { f, x, y -> sent.add(Triple(f, x, y)) }, {}, { consumed++ })
        var time = 1000L
        fun event(action: Int, vararg points: Pair<Float, Float>) {
            motion(action, time, points.toList(), 1000).also { gestures.touch(it); it.recycle() }; time += 20
        }
        val down = MotionEvent.ACTION_DOWN; val move = MotionEvent.ACTION_MOVE; val up = MotionEvent.ACTION_UP
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(secondUp, 450f to 400f, 550f to 400f); event(up, 450f to 400f)
        check(sent.map { it.first } == listOf(0xa000, 0x2000)) { "Two-finger tap must only right-click" }
        sent.clear()
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(move, 450f to 300f, 550f to 300f); event(secondUp, 450f to 300f, 550f to 300f); event(up, 450f to 300f)
        check(sent.any { it.first and 0x0300 == 0x0300 }) { "Upward swipe must send negative wheel" }
        check(sent.none { it.first and 0xf000 != 0 }) { "Scrolling must not click" }
        sent.clear()
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(move, 350f to 400f, 650f to 400f); event(secondUp, 350f to 400f, 650f to 400f); event(up, 350f to 400f)
        check(viewport.zoom == 3f && sent.isEmpty()) { "Pinch must zoom locally without remote input" }
        gestures.panMode = true
        val before = viewport.bounds.left
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(move, 510f to 430f, 610f to 430f); event(secondUp, 510f to 430f, 610f to 430f); event(up, 510f to 430f)
        check(viewport.bounds.left > before && sent.isEmpty())
        viewport.reset(); gestures.panMode = false
        event(down, 400f to 400f); event(move, 500f to 400f); event(MotionEvent.ACTION_CANCEL, 500f to 400f)
        check(sent.map { it.first } == listOf(0x9000, 0x0800, 0x1000)) { "Cancelled drag must release the left button" }
        sent.clear(); gestures.rightClick = true
        event(down, 500f to 400f); event(up, 500f to 400f)
        check(sent.map { it.first } == listOf(0xa000, 0x2000) && consumed == 1 && !gestures.rightClick)
        sent.clear(); gestures.wheel(-1f, 1f, 500f, 400f)
        check(sent.map { it.first } == listOf(0x0800, 0x0388, 0x0478))
        sent.clear()
        event(down, 500f to 5f); event(up, 500f to 400f)
        check(sent.isEmpty()) { "A gesture starting in letterbox must not click" }
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(MotionEvent.ACTION_CANCEL, 450f to 400f, 550f to 400f)
        check(sent.isEmpty()) { "Cancelled multi-touch must not click" }
        event(down, 450f to 400f); event(secondDown, 450f to 400f, 550f to 400f)
        event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 450f to 400f, 550f to 400f, 600f to 400f)
        event(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 450f to 400f, 550f to 400f, 600f to 400f)
        event(secondUp, 450f to 400f, 550f to 400f); event(up, 450f to 400f)
        check(sent.isEmpty()) { "A third finger must cancel remote input" }
        viewport.scale(2f); viewport.pan(300f, 0f)
        val expected = viewport.remote(300f, 400f)!!
        event(down, 300f to 400f); event(up, 300f to 400f)
        check(sent.size == 2 && sent.all { it.second == expected.x.toInt() && it.third == expected.y.toInt() })
        // Trackpad moves are relative, may start in letterbox, and do not hold the left key.
        viewport.reset(); sent.clear()
        var dragConsumed = 0
        val pad = RdpGestures(viewport, 10f, 50f, { f, x, y -> sent.add(Triple(f, x, y)) }, {}, {}, { dragConsumed++ }).apply { trackpad = true }
        fun padEvent(action: Int, vararg points: Pair<Float, Float>) {
            motion(action, time, points.toList(), 1000).also { pad.touch(it); it.recycle() }; time += 20
        }
        padEvent(down, 10f to 5f); padEvent(move, 110f to 5f); padEvent(up, 110f to 5f)
        check(sent.map { it.first } == listOf(0x0800) && abs(pad.cursorX - 768f) < 0.01f && pad.cursorY == 360f)
        sent.clear()
        padEvent(down, 700f to 700f); padEvent(up, 700f to 700f)
        check(sent.map { it.first } == listOf(0x9000, 0x1000) && sent.all { it.second == 768 && it.third == 360 })
        sent.clear()
        padEvent(down, 700f to 700f); padEvent(move, 750f to 700f); padEvent(up, 750f to 700f)
        check(sent.map { it.first } == listOf(0x9000, 0x0800, 0x1000)) { "Double-tap drag must hold then release left" }
        sent.clear(); pad.dragArmed = true
        padEvent(down, 20f to 20f); padEvent(move, 120f to 20f); padEvent(MotionEvent.ACTION_CANCEL, 120f to 20f)
        check(sent.map { it.first } == listOf(0x9000, 0x0800, 0x1000) && dragConsumed == 1)
        sent.clear(); pad.placeCursor(20f, 40f)
        padEvent(down, 450f to 400f); padEvent(secondDown, 450f to 400f, 550f to 400f)
        padEvent(secondUp, 450f to 400f, 550f to 400f); padEvent(up, 450f to 400f)
        check(sent.map { it.first } == listOf(0xa000, 0x2000) && sent.all { it.second == 20 && it.third == 40 })
        viewport.scale(2f); pad.placeCursor(1200f, 650f); sent.clear()
        padEvent(down, 10f to 10f); padEvent(move, 1010f to 1010f); padEvent(up, 1010f to 1010f)
        check(pad.cursorX == 1279f && pad.cursorY == 719f)
        check(viewport.local(pad.cursorX, pad.cursorY).let { it.x in 0f..1000f && it.y in 0f..800f })
        keyboardAndClipboardRegression()
        p1Regression()
        val emitted = mutableListOf<Int>()
        val blocked = CountDownLatch(1); val unblock = CountDownLatch(1); val done = CountDownLatch(1)
        val queue = RdpInputQueue { kind, flags, x, _ ->
            if (kind == 2 && flags == 0) synchronized(emitted) { emitted.add(x) }
            if (kind == 2 && flags == 0 && x == 'A'.code) { blocked.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            if (kind == 2 && flags != 0 && x == 'N'.code) done.countDown()
        }
        queue.text("AB"); check(blocked.await(3, TimeUnit.SECONDS)); queue.text("OLD")
        queue.setEnabled(false); queue.setEnabled(true); queue.text("N"); unblock.countDown()
        check(done.await(3, TimeUnit.SECONDS)); queue.close()
        check(emitted == listOf('A'.code, 'N'.code)) { "Background must cancel queued and partially sent text without replay" }
    }

    private fun keyboardAndClipboardRegression() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val typed = StringBuilder()
        val keyboard = RdpKeyboard({ scan, down -> keys.add(scan to down) }, { typed.append(it) })
        val mixed = "ORDER_0123456789-Calabi"
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(mixed.toCharArray())!!.forEach { check(keyboard.dispatch(it)) }
        check(typed.toString() == mixed && keys.isEmpty())
        fun key(code: Int, down: Boolean, meta: Int = 0) {
            check(keyboard.dispatch(KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, meta)))
        }
        key(KeyEvent.KEYCODE_CTRL_LEFT, true); key(KeyEvent.KEYCODE_C, true, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_C, false, KeyEvent.META_CTRL_ON); key(KeyEvent.KEYCODE_CTRL_LEFT, false)
        check(keys == listOf(0x1d to true, 0x2e to true, 0x2e to false, 0x1d to false))
        keys.clear()
        key(KeyEvent.KEYCODE_CTRL_LEFT, true); key(KeyEvent.KEYCODE_SHIFT_LEFT, true)
        key(KeyEvent.KEYCODE_Z, true, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        keyboard.releaseAll()
        check(keys == listOf(0x1d to true, 0x2a to true, 0x2c to true, 0x2c to false, 0x2a to false, 0x1d to false))
        val events = mutableListOf<List<Int>>()
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val done = CountDownLatch(1)
        val queue = RdpInputQueue { kind, flags, x, y ->
            if (events.isEmpty()) { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) }
            events.add(listOf(kind, flags, x, y)); if (kind == 0) done.countDown()
        }
        try {
            queue.text("中文")
            check(entered.await(5, TimeUnit.SECONDS))
            queue.chord(intArrayOf(0x1d, 0x2a), 0x2c); queue.mouse(0x0800, 10, 20); unblock.countDown()
            check(done.await(5, TimeUnit.SECONDS))
            check(events.drop(4) == listOf(listOf(1, 0, 0x1d, 0), listOf(1, 0, 0x2a, 0), listOf(1, 0, 0x2c, 0),
                listOf(1, 0x8000, 0x2c, 0), listOf(1, 0x8000, 0x2a, 0), listOf(1, 0x8000, 0x1d, 0), listOf(0, 0x0800, 10, 20)))
        } finally { unblock.countDown(); queue.close() }
        check(RdpShortcuts.items.map { it.label }.toSet().size == RdpShortcuts.items.size)
        RdpShortcuts.items.forEach { check(it.modifiers.distinct().size == it.modifiers.size && it.scan in 1..0x1ff) }
        check(RdpClipboardText.windows("中文😀\nline2\r\nline3") == "中文😀\r\nline2\r\nline3")
        check(RdpClipboardText.windows("") == "")
        check(runCatching { RdpClipboardText.windows("x".repeat(RdpClipboardText.MAX + 1)) }.isFailure)
        check(runCatching { RdpClipboardText.windows("\u0000") }.isFailure)
        check(runCatching { RdpClipboardText.windows("\ud800") }.isFailure)
        val session = RdpSession(JSONObject().put("token", "input-fixture-only"))
        try {
            runOnMainSync {
                val id = session.clipboard()
                session.cancelClipboard()
                session.onNativeClipboard(id, "late-fixture", 0)
            }
            waitForIdleSync()
            check(session.clipboardResult.value.id == 0 && session.clipboardResult.value.text == null)
        } finally { session.close() }
    }

    private fun findView(view: View): RdpView? {
        if (view is RdpView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun p1Regression() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val text = StringBuilder()
        val keyboard = RdpKeyboard({ scan, down -> keys.add(scan to down) }, { text.append(it) })
        keyboard.setLatch(1); keyboard.commit("a")
        check(keys == listOf(0x1d to true, 0x1e to true, 0x1e to false, 0x1d to false) && keyboard.latch == 0)
        keys.clear(); keyboard.commit("中文😀"); check(text.toString() == "中文😀" && keys.isEmpty())
        keyboard.setLatch(4); keyboard.press(0x147)
        check(keys == listOf(0x2a to true, 0x147 to true, 0x147 to false, 0x2a to false))
        keys.clear(); keyboard.setLatch(3); keyboard.pointerModifiers(); keyboard.pointerReleased()
        check(keys == listOf(0x1d to true, 0x38 to true, 0x38 to false, 0x1d to false) && keyboard.latch == 0)
        keyboard.setLatch(7); keyboard.releaseAll(); check(keyboard.latch == 0)
        check(RdpScan.key(KeyEvent.KEYCODE_NUMPAD_ENTER) == 0x11c && RdpScan.key(KeyEvent.KEYCODE_NUMPAD_7) == 0x147)
        check(RdpErrors.session(2, "RDP 0x00020009") == R.string.rdp_auth_failed)
        check(RdpErrors.session(2, "RDP 0x00020018") == R.string.rdp_account_restricted)
        check(RdpErrors.session(2, "RDP 0x0002000d") == R.string.rdp_network_failed)
        check(RdpErrors.session(8, "RDP 0x00020008") == R.string.rdp_identity_changed)
        check(RdpErrors.reason("certificate_unavailable") == R.string.rdp_certificate_missing)
        val viewport = RdpViewport().apply { configure(1000, 800, 1280, 720); actualPixels() }
        check(viewport.pixelScale == 1f && viewport.bounds.width() == 1280f)
        viewport.configure(2000, 1000, 1280, 720)
        check(viewport.pixelScale == 1f && viewport.bounds.width() == 1280f)
        viewport.reset(); check(!viewport.originalSize && viewport.bounds.height() == 1000f)
        val flags = mutableListOf<Int>(); val wheels = mutableListOf<Pair<Float, Float>>()
        var releases = 0
        val mouse = RdpMouse(viewport, { f, _, _ -> flags.add(f) }, {}, { releases++ }, { _, _ -> }, {}, { v, h, _, _ -> wheels.add(v to h) })
        fun event(action: Int, buttons: Int, v: Float = 0f, h: Float = 0f) {
            val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val c = MotionEvent.PointerCoords().apply { x = 1000f; y = 500f; setAxisValue(MotionEvent.AXIS_VSCROLL, v); setAxisValue(MotionEvent.AXIS_HSCROLL, h) }
            MotionEvent.obtain(0, 0, action, 1, arrayOf(p), arrayOf(c), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0).also { mouse.event(it); it.recycle() }
        }
        event(MotionEvent.ACTION_HOVER_MOVE, 0)
        check(releases == 0) { "Hover must preserve a one-shot modifier until the next click" }
        event(MotionEvent.ACTION_DOWN, 1)
        event(MotionEvent.ACTION_BUTTON_PRESS, 3)
        event(MotionEvent.ACTION_BUTTON_RELEASE, 2)
        event(MotionEvent.ACTION_UP, 2) // Releasing primary must preserve secondary.
        check(releases == 0)
        event(MotionEvent.ACTION_CANCEL, 0)
        event(MotionEvent.ACTION_DOWN, 4); event(MotionEvent.ACTION_UP, 0)
        event(MotionEvent.ACTION_SCROLL, 0, 1f, -1f)
        check(flags == listOf(0x0800, 0x9000, 0xa000, 0x1000, 0x2000, 0xc000, 0x4000))
        check(wheels == listOf(1f to -1f))
        check(releases == 2) { "Only cancel and the last button release consume modifiers" }
        status("P1 modifier, physical mouse buttons/wheels, keypad, error and original-pixel regression passed")
    }

    private fun fileSmoke() {
        val fixture = java.io.File(targetContext.cacheDir, "calabi-p1-file-fixture.txt")
        var id: String? = null
        try {
            val bytes = "Calabi P1 file staging 中文\n".toByteArray()
            fixture.writeBytes(bytes)
            android.os.ParcelFileDescriptor.open(fixture, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {
                id = CalabiApp.instance.core.stageFile(it.detachFd(), fixture.name, bytes.size.toLong())
            }
            val snapshot = CoreClient.callBlocking("GET", "/v1/files").json()
            check(snapshot.optBoolean("available"))
            val sources = snapshot.getJSONArray("sources")
            check((0 until sources.length()).any { sources.getJSONObject(it).let { s -> s.optString("id") == id && s.optLong("size") == bytes.size.toLong() } })
            check(CoreClient.callBlocking("GET", "/v1/mesh").ok)
            status("FILE_STAGING_AND_MESH_PASSED (own fixture only; no remote file sent)")
        } finally {
            if (id != null) check(CoreClient.callBlocking("POST", "/v1/files/action", JSONObject().put("action", "forget_source").put("id", id)).ok)
            fixture.delete()
        }
    }

    private fun failureSmoke(kind: String) {
        val targets = CoreClient.callBlocking("GET", "/v1/remote/rdp/targets").json().getJSONArray("items")
        val target = (0 until targets.length()).map { targets.getJSONObject(it) }.first { it.optString("overlay") == "100.64.0.42" }
        val response = CoreClient.callBlocking("POST", "/v1/remote/rdp/prepare", JSONObject().put("device_key", target.getString("device_key")))
        check(response.ok)
        val prepared = response.json()
        if (kind == "certificate") {
            val pin = prepared.getString("certificate_sha256")
            prepared.put("certificate_sha256", (if (pin[0] == '0') "1" else "0") + pin.substring(1))
        }
        if (kind == "network") prepared.put("local", "192.0.2.123") // Unassigned source must fail bind without fallback.
        val session = RdpSession(prepared)
        try {
            session.start("calabi-p1-invalid", ".", "P1-Nonexistent-Account-Fixture")
            repeat(60) { if (session.state.value.first < 2) SystemClock.sleep(250) }
            val result = session.state.value
            check(result.first >= 2)
            val expected = when (kind) { "certificate" -> R.string.rdp_identity_changed; "network" -> R.string.rdp_network_failed; else -> R.string.rdp_auth_failed }
            check(RdpErrors.session(result.first, result.second) == expected) { "Failure category mismatch: ${result.first}, ${result.second}" }
            status("FAILURE_${kind.uppercase()}_PASSED")
        } finally { session.close() }
    }

    private fun status(message: String) {
        android.util.Log.i("CalabiRdpTest", message)
        sendStatus(1, Bundle().apply { putString("stream", "\n$message\n") })
    }

    private fun liveSession() {
        if (referenceCodec) {
            val native = net.calabi.app.rdp.RdpNative
            check(native.available)
            val init = native.javaClass.getDeclaredMethod("initialize", String::class.java, String::class.java).apply { isAccessible = true }
            check(init.invoke(native, targetContext.filesDir.absolutePath, "OMX.google.h264.decoder") == true)
            status("SOFTWARE_REFERENCE_CODEC_ONLY: not a hardware acceptance run")
        }
        check(VpnService.prepare(targetContext) == null) { "VPN consent is required" }
        CalabiVpnService.connect(targetContext)
        var ready = false
        repeat(60) {
            if (!ready) {
                val items = CoreClient.callBlocking("GET", "/v1/remote/rdp/targets").json().optJSONArray("items")
                ready = (0 until (items?.length() ?: 0)).any { items!!.getJSONObject(it).let { t -> t.optString("overlay") == "100.64.0.42" && t.optBoolean("available") } }
                if (!ready) SystemClock.sleep(500)
            }
        }
        check(ready) { "Authorized test target is not available" }
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = startActivitySync(Intent(targetContext, RdpActivity::class.java).putExtra("overlay", "100.64.0.42").putExtra("quality", quality).putExtra("classic", classic).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        status("READY: connect using the saved credentials in the phone UI")
        var canvas: RdpView? = null
        repeat(120) {
            if (canvas == null) {
                runOnMainSync { canvas = findView(activity.window.decorView)?.takeIf { it.viewport.remoteWidth > 0 } }
                if (canvas == null) SystemClock.sleep(500)
            }
        }
        var view = checkNotNull(canvas) { "No live remote canvas" }
        var oldInput: android.view.inputmethod.InputConnection? = null
        val commands = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent) { commands.offer(intent) } }
        val filter = IntentFilter("net.calabi.app.test.RDP_GESTURE")
        ContextCompat.registerReceiver(targetContext, receiver, filter, "android.permission.DUMP", null,
            ContextCompat.RECEIVER_EXPORTED)
        status("READY_GESTURES: ADB test-only receiver active")
        try {
            val deadline = SystemClock.uptimeMillis() + 1200000
            while (SystemClock.uptimeMillis() < deadline) {
                val command = commands.poll(10, TimeUnit.SECONDS) ?: continue
                val gesture = command.getStringExtra("gesture").orEmpty()
                if (gesture == "finish") break
                runOnMainSync {
                    val current = (RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as? RdpSession)?.view
                    if (current != null && current !== view) { status("REFRESHED_CANVAS"); view = current }
                }
                if (gesture == "file-smoke") { fileSmoke(); continue }
                if (gesture == "editor") {
                    val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    runOnMainSync { view.shortcut(intArrayOf(0x15b), 0x13) }
                    SystemClock.sleep(1000)
                    runOnMainSync { live.shortcut(intArrayOf(0x1d), 0x1e); live.text("notepad.exe"); live.press(0x1c) }
                    SystemClock.sleep(2000)
                    runOnMainSync { live.text("Calabi AVC 中文输入 123"); live.press(0x1c) }
                    status("REMOTE_EDITOR_QUEUED"); continue
                }
                if (gesture == "animation") {
                    val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val script = """
                        Add-Type -AssemblyName System.Windows.Forms,System.Drawing
                        ${'$'}f=New-Object Windows.Forms.Form
                        ${'$'}f.Text='Calabi AVC decode test';${'$'}f.Width=1100;${'$'}f.Height=650;${'$'}f.StartPosition='CenterScreen';${'$'}script:i=0
                        ${'$'}f.Add_Paint({param(${'$'}s,${'$'}e);${'$'}e.Graphics.Clear([Drawing.Color]::FromArgb((${'$'}script:i%255),80,180));${'$'}e.Graphics.FillRectangle([Drawing.Brushes]::Lime,(${'$'}script:i%900),80,200,400)})
                        ${'$'}t=New-Object Windows.Forms.Timer;${'$'}t.Interval=33
                        ${'$'}t.Add_Tick({${'$'}script:i+=13;if(${'$'}script:i-gt39000){${'$'}f.Close()}else{${'$'}f.Invalidate()}})
                        ${'$'}f.Add_FormClosed({${'$'}t.Stop();${'$'}t.Dispose()});${'$'}t.Start();[void]${'$'}f.ShowDialog()
                    """.trimIndent()
                    runOnMainSync { view.shortcut(intArrayOf(0x15b), 0x13) }
                    SystemClock.sleep(1000)
                    // Keep Run below its command length limit. Send the fixture to
                    // our own console afterwards, pacing by chunks rather than
                    // overflowing the native queue while the remote app starts.
                    runOnMainSync { live.shortcut(intArrayOf(0x1d), 0x1e); live.text("powershell.exe -NoProfile"); live.press(0x1c) }
                    SystemClock.sleep(3000)
                    for (chunk in script.lineSequence().joinToString(";").chunked(24)) {
                        runOnMainSync { live.text(chunk) }; SystemClock.sleep(180)
                    }
                    runOnMainSync { live.press(0x1c) }
                    status("REMOTE_ANIMATION_QUEUED"); continue
                }
                if (gesture == "assert-hardware" || gesture == "assert-fallback" || gesture == "gfx-fail") {
                    if (gesture == "assert-hardware") check(!referenceCodec) { "Software reference is not hardware acceptance" }
                    val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    if (gesture == "gfx-fail") {
                        check(live.graphicsStatistics()[0] == 1L)
                        val handle = RdpSession::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(live)
                        net.calabi.app.rdp.RdpNative.failGraphics(handle)
                        status("GRAPHICS_FAILURE_INJECTED"); continue
                    }
                    val deadline = SystemClock.elapsedRealtime() + 30000
                    var g = live.graphicsStatistics()
                    fun ready() = g.size == 4 && live.state.value.first == 1 && live.interactive.value &&
                        if (gesture == "assert-hardware") g[0] == 1L && g[2] > 0 && g[3] > 0 else g[0] == 0L && g[1] == 1L
                    while (!ready() && SystemClock.elapsedRealtime() < deadline) { SystemClock.sleep(100); g = live.graphicsStatistics() }
                    check(ready()) { "Expected $gesture, graphics=${g.joinToString()} state=${live.state.value.first}" }
                    status("${gesture.uppercase()} PASSED graphics=${g.joinToString()} decoder=${net.calabi.app.rdp.RdpHardware.decoder}")
                    continue
                }
                if (gesture == "system-shortcut") {
                    val action = command.getStringExtra("key")
                    runOnMainSync {
                        when (action) {
                            "lock" -> view.shortcut(intArrayOf(0x15b), 0x26)
                            "run" -> view.shortcut(intArrayOf(0x15b), 0x13)
                            "close" -> view.shortcut(intArrayOf(0x38), 0x3e)
                            else -> error("Unknown system shortcut")
                        }
                    }
                    status("SYSTEM_SHORTCUT_$action"); continue
                }
                if (gesture == "soak") {
                    val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val deadline = SystemClock.elapsedRealtime() + command.getIntExtra("seconds", 600).coerceIn(30, 1800) * 1000L
                    var samples = 0
                    while (SystemClock.elapsedRealtime() < deadline) {
                        check(live.state.value.first == 1 && live.interactive.value) { "Session stopped during soak" }
                        // Exercise only the existing, unsaved test editor. Do not
                        // read clipboard contents or operate user files.
                        runOnMainSync { live.text("."); live.press(0x0e) }
                        val m = live.statistics()
                        val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                        status("SOAK sample=${samples++} native=${m.joinToString(",")} cpuMs=${android.os.Process.getElapsedCpuTime()} pssKb=${memory.totalPss}")
                        SystemClock.sleep(10000)
                    }
                    check(live.state.value.first == 1 && live.interactive.value)
                    status("SOAK_PASSED samples=$samples"); continue
                }
                if (gesture == "metrics" || gesture == "probe-input") {
                    val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val before = live.statistics()
                    if (gesture == "probe-input") {
                        val started = SystemClock.elapsedRealtime()
                        runOnMainSync { live.text("P2") }
                        var after = live.statistics()
                        while (after.size == 8 && after[2] == before[2] && SystemClock.elapsedRealtime() - started < 5000) {
                            SystemClock.sleep(10); after = live.statistics()
                        }
                        check(after[2] > before[2]) { "No remote paint after test input" }
                        status("INPUT_TO_REMOTE_PAINT_MS=${SystemClock.elapsedRealtime() - started}")
                    }
                    val m = live.statistics()
                    check(m.size == 8 && m[0] == live.quality.width.toLong() && m[1] == live.quality.height.toLong())
                    val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                    status("METRICS quality=${live.quality} native=${m.joinToString(",")} cpuMs=${android.os.Process.getElapsedCpuTime()} pssKb=${memory.totalPss}")
                    status("GRAPHICS ${live.graphicsStatistics().joinToString()}")
                    val mesh = CoreClient.callBlocking("GET", "/v1/mesh").json()
                    val peers = mesh.optJSONArray("peers")
                    val target = (0 until (peers?.length() ?: 0)).map { peers!!.getJSONObject(it) }.firstOrNull { it.optString("name") == "eq-2fc57a77" }
                    status("MESH_PATH=${target?.optString("path")} rx=${target?.optLong("rx_bytes")} tx=${target?.optLong("tx_bytes")} datapath=${mesh.optJSONObject("datapath")}")
                    continue
                }
                if (gesture == "weak-overflow") {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val handle = RdpSession::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(session)
                    for (i in 0 until 10000) {
                        check(net.calabi.app.rdp.RdpNative.input(handle, 0, 0x0800, 640 + i % 100, 360)) {
                            "Adjacent absolute motion should coalesce without overflowing"
                        }
                    }
                    check(session.interactive.value)
                    status("WEAK_INPUT_10000_MOTIONS_COALESCED"); continue
                }
                if (gesture.startsWith("roundtrip-")) {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val fixture = "Calabi P1 文本剪贴板 😀\n第二行：中文、English 123"
                    fun clip(text: String? = null): String? {
                        val deadline = SystemClock.elapsedRealtime() + 10000
                        do {
                            var id = 0
                            runOnMainSync { id = session.clipboard(text) }
                            while (session.clipboardResult.value.id != id && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                            val result = session.clipboardResult.value
                            if (result.id == id && result.error == 0) return result.text
                            check(text == null && result.error in 1..2 && SystemClock.elapsedRealtime() < deadline) { "Clipboard test operation error=${result.error}" }
                            SystemClock.sleep(250) // The newly copied format list may follow channel readiness.
                        } while (true)
                    }
                    fun chord(scan: Int) { runOnMainSync { view.shortcut(intArrayOf(0x1d), scan) }; SystemClock.sleep(300) }
                    if (gesture == "roundtrip-reset") { clip(fixture); chord(0x1e); chord(0x2f); SystemClock.sleep(500) }
                    chord(0x1e); chord(0x2e); SystemClock.sleep(500)
                    val expected = fixture + if (gesture == "roundtrip-input") "-恢复输入" else ""
                    check(clip() == expected) { "Remote fixture mismatch (contents omitted)" }
                    status("REMOTE_${gesture.uppercase()}_PASSED"); continue
                }
                if (gesture == "blocked-input") {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    check(!session.interactive.value)
                    runOnMainSync { view.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("BLOCKED_SHOULD_NOT_APPEAR", 1) }
                    session.text("QUEUED_SHOULD_NOT_APPEAR")
                    status("BLOCKED_INPUT_INJECTED"); continue
                }
                if (gesture.startsWith("assert-")) {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val expected = when (gesture) { "assert-connected" -> 1; "assert-recovering" -> 6; else -> command.getIntExtra("code", 9) }
                    val deadline = SystemClock.elapsedRealtime() + command.getIntExtra("wait", 45000)
                    while (session.state.value.first != expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    check(session.state.value.first == expected) { "Expected state=$expected, got=${session.state.value.first}" }
                    check(session.interactive.value == (expected == 1))
                    status("ASSERT_STATE_$expected interactive=${session.interactive.value} zoom=${view.viewport.zoom}")
                    continue
                }
                if (gesture == "repair") {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    runOnMainSync { session.networkChanged(true) }
                    status("TEST_TRANSPORT_REPAIR_REQUESTED"); continue
                }
                if (gesture in setOf("failure-auth", "failure-certificate", "failure-network")) { failureSmoke(gesture.removePrefix("failure-")); continue }
                if (gesture == "key-combo") {
                    val action = command.getStringExtra("key").orEmpty()
                    val code = when (action) {
                        "all" -> KeyEvent.KEYCODE_A; "copy" -> KeyEvent.KEYCODE_C; "paste" -> KeyEvent.KEYCODE_V
                        "end" -> KeyEvent.KEYCODE_MOVE_END; "home" -> KeyEvent.KEYCODE_MOVE_HOME
                        "escape" -> KeyEvent.KEYCODE_ESCAPE; else -> error("Unknown test key")
                    }
                    fun key(code: Int, down: Boolean, meta: Int = 0) {
                        val now = SystemClock.uptimeMillis()
                        sendKeySync(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0,
                            meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
                    }
                    if (action != "escape") key(KeyEvent.KEYCODE_CTRL_LEFT, true)
                    val meta = if (action == "escape") 0 else KeyEvent.META_CTRL_ON
                    key(code, true, meta); key(code, false, meta)
                    if (action != "escape") key(KeyEvent.KEYCODE_CTRL_LEFT, false)
                    status("PHYSICAL_KEY_$action"); continue
                }
                if (gesture == "soft-all") {
                    runOnMainSync {
                        view.modifiers = 1
                        view.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("a", 1)
                        check(view.modifiers == 0)
                    }
                    status("SOFT_MODIFIER_CONSUMED"); continue
                }
                if (gesture.startsWith("mouse-")) {
                    val location = IntArray(2)
                    var px = 0f; var py = 0f
                    runOnMainSync {
                        view.getLocationOnScreen(location)
                        val p = view.viewport.local(command.getFloatExtra("rx", 500f), command.getFloatExtra("ry", 300f))
                        px = location[0] + p.x; py = location[1] + p.y
                    }
                    val down = SystemClock.uptimeMillis()
                    fun inject(action: Int, buttons: Int, dx: Float = 0f, dy: Float = 0f) {
                        val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                        val point = MotionEvent.PointerCoords().apply { x = px + dx; y = py + dy; pressure = 1f; size = 1f
                            setAxisValue(MotionEvent.AXIS_VSCROLL, command.getFloatExtra("v", 0f)); setAxisValue(MotionEvent.AXIS_HSCROLL, command.getFloatExtra("h", 0f)) }
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(property), arrayOf(point), 0, buttons, 1f, 1f, 0, 0,
                            InputDevice.SOURCE_MOUSE, 0).also { sendPointerSync(it); it.recycle() }
                        SystemClock.sleep(50)
                    }
                    val button = when (gesture) { "mouse-right" -> 2; "mouse-middle" -> 4; else -> 1 }
                    inject(MotionEvent.ACTION_HOVER_MOVE, 0)
                    if (gesture == "mouse-scroll") inject(MotionEvent.ACTION_SCROLL, 0)
                    else if (gesture != "mouse-move") {
                        inject(MotionEvent.ACTION_DOWN, button)
                        if (gesture == "mouse-drag") inject(MotionEvent.ACTION_MOVE, button, 80f, 60f)
                        inject(MotionEvent.ACTION_UP, 0, if (gesture == "mouse-drag") 80f else 0f, if (gesture == "mouse-drag") 60f else 0f)
                    }
                    status("PHYSICAL_$gesture"); continue
                }
                if (gesture == "single-session") {
                    val previous = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    val next = startActivitySync(Intent(targetContext, RdpActivity::class.java).putExtra("overlay", "100.64.0.42").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitForIdleSync(); check(previous.state.value.first == 3 && !previous.interactive.value)
                    runOnMainSync { next.finish() }
                    status("SINGLE_SESSION_PASSED"); continue
                }
                if (gesture == "vpn-stop") { CalabiVpnService.disconnect(targetContext); status("TEST_VPN_STOPPED"); continue }
                if (gesture == "ime-start" || gesture == "ime-finish") {
                    runOnMainSync {
                        if (gesture == "ime-start") {
                            oldInput = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                            oldInput!!.setComposingText("未提交草稿", 1)
                        } else {
                            val live = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                            val queue = RdpSession::class.java.getDeclaredField("inputs").apply { isAccessible = true }.get(live)
                            val enabled = RdpInputQueue::class.java.getDeclaredField("enabled").apply { isAccessible = true }.get(queue)
                            status("IME_FLAGS viewInteractive=${view.interactive} sessionInteractive=${live.interactive.value} queueEnabled=$enabled currentView=${live.view === view}")
                            oldInput!!.commitText("STALE_SHOULD_NOT_APPEAR", 1)
                            view.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("-恢复输入", 1)
                        }
                    }
                    status("IME_${if (gesture == "ime-start") "COMPOSING" else "RESUMED"}"); continue
                }
                if (gesture in setOf("state", "revoke", "hold-control")) {
                    val session = RdpActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity) as RdpSession
                    if (gesture == "hold-control") {
                        session.key(0x1d, true); status("TEST_CONTROL_HELD")
                    } else if (gesture == "revoke") {
                        val prepared = RdpSession::class.java.getDeclaredField("prepared").apply { isAccessible = true }.get(session) as JSONObject
                        check(CoreClient.callBlocking("POST", "/v1/remote/rdp/close", JSONObject().put("token", prepared.getString("token"))).ok)
                        status("TEST_TICKET_REVOKED")
                    } else runOnMainSync {
                        status("LIFECYCLE state=${session.state.value.first} interactive=${session.interactive.value} zoom=${view.viewport.zoom} size=${view.width}x${view.height} activityFinishing=${activity.isFinishing}")
                    }
                    continue
                }
                if (gesture in setOf("fixture", "check-clipboard", "check-input")) {
                    val fixture = "Calabi P1 文本剪贴板 😀\n第二行：中文、English 123" + if (gesture == "check-input") "-恢复输入" else ""
                    runOnMainSync {
                        val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (gesture == "fixture") clipboard.setPrimaryClip(ClipData.newPlainText("Calabi test fixture", fixture))
                        else {
                            check(clipboard.primaryClipDescription?.label?.toString() == "Calabi") { "Use Copy to phone after receiving the remote text" }
                            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == fixture) { "Clipboard fixture mismatch (content omitted)" }
                        }
                    }
                    status("CLIPBOARD_${if (gesture == "fixture") "SEEDED" else "ROUNDTRIP_PASSED"}")
                    continue
                }
                val location = IntArray(2)
                var cx = 0f; var cy = 0f
                runOnMainSync {
                    view.getLocationOnScreen(location)
                    cx = location[0] + view.width / 2f; cy = location[1] + view.height / 2f
                }
                val x = command.getFloatExtra("x", cx); val y = command.getFloatExtra("y", cy)
                val dx = command.getFloatExtra("dx", 0f); val dy = command.getFloatExtra("dy", -300f)
                val initial = command.getFloatExtra("span", 150f)
                val factor = command.getFloatExtra("factor", 2f)
                val downAt = SystemClock.uptimeMillis()
                fun inject(action: Int, points: List<Pair<Float, Float>>) {
                    motion(action, SystemClock.uptimeMillis(), points, downAt).also { sendPointerSync(it); it.recycle() }
                    SystemClock.sleep(20)
                }
                if (gesture in setOf("move", "tap", "drag")) {
                    inject(MotionEvent.ACTION_DOWN, listOf(x to y))
                    if (gesture != "tap") repeat(12) { i ->
                        val progress = (i + 1) / 12f
                        inject(MotionEvent.ACTION_MOVE, listOf(x + dx * progress to y + dy * progress))
                    }
                    inject(MotionEvent.ACTION_UP, listOf((x + (if (gesture == "tap") 0f else dx)) to (y + (if (gesture == "tap") 0f else dy))))
                    waitForIdleSync()
                    status("GESTURE_DONE $gesture")
                    continue
                }
                val first = x - initial / 2 to y; val second = x + initial / 2 to y
                inject(MotionEvent.ACTION_DOWN, listOf(first))
                inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(first, second))
                var end = listOf(first, second)
                if (gesture != "right") repeat(12) { i ->
                    val progress = (i + 1) / 12f
                    val span = if (gesture == "pinch") initial * (1 + (factor - 1) * progress) else initial
                    val px = x + if (gesture == "pinch") 0f else dx * progress
                    val py = y + if (gesture == "pinch") 0f else dy * progress
                    end = listOf(px - span / 2 to py, px + span / 2 to py)
                    inject(MotionEvent.ACTION_MOVE, end)
                }
                inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
                inject(MotionEvent.ACTION_UP, listOf(end[0]))
                waitForIdleSync()
                runOnMainSync { status("GESTURE_DONE $gesture zoom=${view.viewport.zoom} bounds=${view.viewport.bounds}") }
            }
        } finally { targetContext.unregisterReceiver(receiver) }
    }

    override fun onStart() {
        val results = Bundle()
        try {
            regression()
            status("Viewport/trackpad/shortcut/clipboard regression passed, including cancellation and bounds")
            if (live) liveSession()
            results.putString("stream", "\nRDP gesture checks passed\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
