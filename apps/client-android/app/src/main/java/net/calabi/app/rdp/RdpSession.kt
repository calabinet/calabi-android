package net.calabi.app.rdp

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.calabi.app.CoreClient
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal data class RdpClipboardResult(val id: Int = 0, val error: Int = 0, val text: String? = null, val sending: Boolean = false)

/** One authorized session. Transport repair retains native credentials only until close. */
@Keep
internal class RdpSession(private val prepared: JSONObject, val quality: RdpQuality = RdpQuality.BALANCED, private val hardware: Boolean = true) {
    companion object {
        const val BACKGROUND_TIMEOUT_MS = 60_000L
        fun terminal(code: Int) = code >= 2 && code != 6
    }
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val inputs = RdpInputQueue { kind, flags, x, y -> input(kind, flags, x, y) }
    private var handle = 0L
    @Volatile private var closed = false
    @Volatile private var foreground = true
    @Volatile private var online = true
    private var networks: RdpNetworks? = null
    private var recovering = false
    private var recoveryAt = -1L
    private val repairExpire = Runnable { if (recovering && !closed) close(9) }
    private val connectExpire = Runnable { if (!closed && mutableState.value.first == 0) close(10) }
    private var backgroundAt = -1L
    private var visibilityGeneration = 0L
    private var transportGeneration = 0L
    private val mutableInteractive = MutableStateFlow(false)
    val interactive = mutableInteractive.asStateFlow()
    private val mutableGraphics = MutableStateFlow(0)
    val graphics = mutableGraphics.asStateFlow()
    @Keep fun onNativeGraphics(mode: Int) { main.post { if (!closed) mutableGraphics.value = mode } }
    private val expire = Runnable { if (!foreground && !closed) close(4) }
    private val mutableState = MutableStateFlow(0 to "")
    val state = mutableState.asStateFlow()
    private val clipboardIds = AtomicInteger()
    private var clipboardRequest = 0
    private val clipboardAvailable = MutableStateFlow(false)
    val clipboardReady = clipboardAvailable.asStateFlow()
    private val mutableClipboard = MutableStateFlow(RdpClipboardResult())
    val clipboardResult = mutableClipboard.asStateFlow()
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    private val framePending = AtomicBoolean()
    private val inputRepairPending = AtomicBoolean()
    val port: Int get() = prepared.optInt("port", 3389)
    var view: RdpView? = null
        set(value) { field = value; if (frameWidth > 0) value?.frame(frameWidth, frameHeight) }

    fun start(user: String, domain: String, password: String) {
        main.postDelayed(connectExpire, 30_000)
        inputs.setEnabled(false)
        networks = RdpNetworks(net.calabi.app.CalabiApp.instance) { networkChanged(it) }
        worker.execute {
            synchronized(lock) {
                if (closed) return@execute
                handle = RdpNative.create(this, prepared.getString("host"), prepared.getString("local"),
                    prepared.getString("certificate_sha256"), user, domain, password, prepared.optInt("port", 3389))
                if (handle != 0L) {
                    if (!RdpNative.configure(handle, quality.ordinal, hardware)) { onNativeState(2, "Invalid display configuration"); return@execute }
                    RdpNative.foreground(handle, false)
                    RdpNative.network(handle, online && foreground, recovering)
                }
                if (handle == 0L || !RdpNative.start(handle)) onNativeState(2, "Could not initialize RDP")
            }
        }
        worker.scheduleWithFixedDelay({
            if (!closed) {
                val authority = recoveryAuthorization()
                main.post {
                    if (!closed && authority < 0) close(5)
                    else if (!closed && authority == 0 && !recovering) networkChanged(online)
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    @Keep fun verifyNativeCertificate(pin: String): Boolean = !closed && CoreClient.callBlocking(
        "POST", "/v1/remote/rdp/verify", JSONObject().put("token", prepared.getString("token")).put("certificate_sha256", pin).put("port", port),
    ).ok

    /** 503 pauses a known ticket; only a full successful verify can authorize a new socket. */
    @Keep fun recoveryAuthorization(): Int {
        if (closed) return -1
        val result = CoreClient.callBlocking("POST", "/v1/remote/rdp/verify", JSONObject()
            .put("token", prepared.getString("token")).put("certificate_sha256", prepared.getString("certificate_sha256")).put("port", port))
        if (!result.ok) android.util.Log.i("CalabiRdp", "authority_status=${result.status} temporary=${result.status == 503}")
        return if (result.ok) 1 else if (result.status == 503 && result.json().optString("reason") == "mesh_reconnecting") 0 else -1
    }

    fun networkChanged(available: Boolean) {
        if (closed) return
        online = available
        recovering = true
        startRepairDeadline()
        visibilityGeneration++; transportGeneration++
        synchronized(lock) { if (handle != 0L) RdpNative.network(handle, online && foreground, true) }
        suspendInteraction()
        mutableState.value = 6 to if (available) "repair" else "offline"
        android.util.Log.i("CalabiRdp", "network_repair online=$available")
    }

    private fun startRepairDeadline() {
        if (recoveryAt >= 0) return
        recoveryAt = SystemClock.elapsedRealtime()
        main.postDelayed(repairExpire, 60_000)
    }

    private fun suspendInteraction() {
        mutableInteractive.value = false
        inputs.setEnabled(false)
        synchronized(lock) { if (handle != 0L) RdpNative.foreground(handle, false) }
        view?.suspendInput(); view?.interactive = false
        cancelClipboard()
    }

    private fun restoreInteraction() {
        if (closed || !foreground || recovering || mutableState.value.first != 1) return
        synchronized(lock) { if (handle != 0L) RdpNative.foreground(handle, true) }
        inputs.setEnabled(true)
        mutableInteractive.value = true
        view?.let { it.resumeInput(); it.requestFocus(); it.frame(frameWidth, frameHeight); it.invalidate() }
    }

    @Keep fun onNativeState(code: Int, message: String) {
        main.post {
            if (closed) return@post
            if (terminal(code)) { close(code, message); return@post }
            if (code == 6) {
                recovering = true; visibilityGeneration++; transportGeneration++; suspendInteraction()
                startRepairDeadline()
                mutableState.value = code to if (online) message else "offline"
            } else if (code == 1) {
                main.removeCallbacks(connectExpire)
                val generation = transportGeneration
                worker.execute {
                    val authority = recoveryAuthorization()
                    main.post {
                        if (closed || generation != transportGeneration) return@post
                        if (authority < 0) { close(5); return@post }
                        if (authority == 0) { networkChanged(online); return@post }
                        if (recoveryAt >= 0 && SystemClock.elapsedRealtime() - recoveryAt >= 60_000) { close(9); return@post }
                        main.removeCallbacks(repairExpire); recoveryAt = -1L
                        recovering = false; mutableState.value = 1 to message
                        restoreInteraction()
                        android.util.Log.i("CalabiRdp", "session_ready")
                    }
                }
            } else mutableState.value = code to message
        }
    }

    /** Main-thread lifecycle: a retained socket is never an authorization to resume input. */
    fun background() {
        if (closed || !foreground) return
        foreground = false; visibilityGeneration++
        if (mutableInteractive.value || backgroundAt < 0) backgroundAt = SystemClock.elapsedRealtime()
        suspendInteraction()
        synchronized(lock) { if (handle != 0L) RdpNative.network(handle, false, false) }
        main.removeCallbacks(expire)
        main.postDelayed(expire, (BACKGROUND_TIMEOUT_MS - (SystemClock.elapsedRealtime() - backgroundAt)).coerceAtLeast(0))
        android.util.Log.i("CalabiRdp", "background_retained")
    }

    fun foreground() {
        if (closed || foreground) return
        main.removeCallbacks(expire)
        if (SystemClock.elapsedRealtime() - backgroundAt >= BACKGROUND_TIMEOUT_MS) { close(4); return }
        foreground = true
        val ticket = ++visibilityGeneration
        worker.execute {
            val verified = recoveryAuthorization()
            main.post {
                if (closed || !foreground || ticket != visibilityGeneration) return@post
                if (SystemClock.elapsedRealtime() - backgroundAt >= BACKGROUND_TIMEOUT_MS) { close(4); return@post }
                if (verified < 0) { close(5); return@post }
                synchronized(lock) { if (handle != 0L) RdpNative.network(handle, online, false) }
                if (verified == 0) { networkChanged(online); return@post }
                restoreInteraction()
                android.util.Log.i("CalabiRdp", "foreground_restored")
            }
        }
    }

    @Keep fun onNativeClipboard(id: Int, value: String?, error: Int) {
        android.util.Log.i("CalabiRdp", "clipboard_event id=$id error=$error")
        main.post {
            if (closed) return@post
            if (id == 0) clipboardAvailable.value = error == 0
            else if (id == clipboardRequest) {
                clipboardRequest = 0
                mutableClipboard.value = mutableClipboard.value.copy(id = id, error = error,
                    text = value?.replace("\r\n", "\n"))
            }
        }
    }

    /** Called only by explicit foreground UI actions; no platform clipboard is read here. */
    fun clipboard(text: String? = null): Int {
        cancelClipboard()
        val id = clipboardIds.incrementAndGet()
        clipboardRequest = id
        mutableClipboard.value = RdpClipboardResult(sending = text != null)
        val accepted = synchronized(lock) {
            handle != 0L && !closed && mutableInteractive.value && RdpNative.clipboard(handle, if (text != null) 1 else 2, text, id)
        }
        if (!accepted) onNativeClipboard(id, null, 1)
        return id
    }

    fun cancelClipboard() {
        val id = clipboardRequest
        clipboardRequest = 0
        if (id != 0) synchronized(lock) { if (handle != 0L && !closed) RdpNative.clipboard(handle, 3, null, id) }
        mutableClipboard.value = RdpClipboardResult()
    }

    @Keep fun onNativeFrame(width: Int, height: Int) {
        if (closed) return
        frameWidth = width; frameHeight = height
        if (framePending.compareAndSet(false, true)) main.postDelayed({
            framePending.set(false)
            if (!closed && mutableInteractive.value) view?.frame(frameWidth, frameHeight)
        }, quality.frameMs)
    }

    fun blit(bitmap: Bitmap): Boolean = synchronized(lock) { handle != 0L && !closed && RdpNative.blit(handle, bitmap) }

    private fun input(kind: Int, flags: Int, x: Int, y: Int = 0) = synchronized(lock) {
        if (handle != 0L && !closed && !RdpNative.input(handle, kind, flags, x, y) && inputRepairPending.compareAndSet(false, true)) {
            main.post {
                inputRepairPending.set(false)
                if (!closed && foreground && mutableInteractive.value && !recovering) networkChanged(online)
            }
        }
    }

    fun mouse(flags: Int, x: Int, y: Int) { inputs.mouse(flags, x, y) }
    fun key(scan: Int, down: Boolean) { inputs.key(scan, down) }
    fun press(scan: Int) { inputs.press(scan) }
    fun shortcut(modifiers: IntArray, scan: Int) { inputs.chord(modifiers, scan) }
    fun text(text: String) { inputs.text(text) }

    fun statistics(): LongArray = synchronized(lock) {
        if (handle != 0L && !closed) RdpNative.stats(handle) else LongArray(0)
    }
    fun graphicsStatistics(): LongArray = synchronized(lock) {
        if (handle != 0L && !closed) RdpNative.graphicsStats(handle) else LongArray(0)
    }

    fun close(code: Int = 3, message: String = "") {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            inputs.close()
            val current = handle
            handle = 0L
            if (current != 0L) RdpNative.stop(current)
            current
        }
        main.removeCallbacks(expire)
        main.removeCallbacks(repairExpire)
        main.removeCallbacks(connectExpire)
        networks?.close(); networks = null
        mutableInteractive.value = false
        mutableState.value = code to message
        clipboardRequest = 0; clipboardAvailable.value = false; mutableClipboard.value = RdpClipboardResult()
        main.post { view?.release(); view = null }
        worker.execute {
            if (old != 0L) RdpNative.destroy(old)
            CoreClient.callBlocking("POST", "/v1/remote/rdp/close", JSONObject().put("token", prepared.getString("token")))
        }
        worker.shutdown()
    }
}
