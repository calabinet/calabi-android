package net.calabi.app.rdp

internal object RdpNative {
    val available = runCatching {
        System.loadLibrary("winpr3")
        System.loadLibrary("freerdp3")
        System.loadLibrary("freerdp-client3")
        System.loadLibrary("calabi-rdp")
        check(initialize(net.calabi.app.CalabiApp.instance.filesDir.absolutePath, RdpHardware.decoder))
    }.onFailure { android.util.Log.e("CalabiRdp", "Native component could not load", it) }.isSuccess

    private external fun initialize(directory: String, decoder: String): Boolean

    external fun create(callback: RdpSession, host: String, local: String, pin: String, user: String, domain: String, password: String, port: Int = 3389): Long
    external fun start(handle: Long): Boolean
    external fun configure(handle: Long, quality: Int, hardware: Boolean): Boolean
    external fun graphicsStats(handle: Long): LongArray
    external fun failGraphics(handle: Long)
    external fun stats(handle: Long): LongArray
    external fun stop(handle: Long)
    external fun foreground(handle: Long, foreground: Boolean)
    external fun network(handle: Long, available: Boolean, changed: Boolean)
    external fun destroy(handle: Long)
    external fun input(handle: Long, kind: Int, flags: Int, x: Int, y: Int): Boolean
    external fun blit(handle: Long, bitmap: android.graphics.Bitmap): Boolean
    external fun clipboard(handle: Long, command: Int, text: String?, requestId: Int): Boolean
}
