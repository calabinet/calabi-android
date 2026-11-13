package net.calabi.app

import android.content.Context
import net.calabi.app.rdp.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/** Uses isolated fixture credentials, never the phone's real credential directory. */
internal fun verifyRdpPorts(context: Context) {
    val alias = "calabi-rdp-port-test"
    val directory = File(context.noBackupFilesDir, "rdp-port-test")
    val store = RdpCredentialStore(context, directory, alias)
    val fixture = RdpCredentials("fixture-user", "", "fixture-only")
    try {
        store.save("fixture-scope", "fixture-device", fixture)
        check(store.load("fixture-scope", "fixture-device", 3389) == fixture)
        check(store.load("fixture-scope", "fixture-device", 3390) == null)
        store.save("fixture-scope", "fixture-device", fixture.copy(user = "custom-port-user"), 3390)
        check(store.load("fixture-scope", "fixture-device", 3390)?.user == "custom-port-user")
        store.file("fixture-scope", "fixture-device", 3391).writeBytes(store.file("fixture-scope", "fixture-device").readBytes())
        check(runCatching { store.load("fixture-scope", "fixture-device", 3391) }.isFailure)
        store.forget("fixture-scope", "fixture-device", 3390)
        check(store.load("fixture-scope", "fixture-device", 3389) == fixture)
        check(store.load("fixture-scope", "fixture-device", 3390) == null)
        check(runCatching { store.file("fixture-scope", "fixture-device", 0) }.isFailure)
        check(runCatching { store.file("fixture-scope", "fixture-device", 65536) }.isFailure)
        check(RdpNative.available)
        val callback = RdpSession(JSONObject().put("token", "port-fixture-only"))
        try {
            for (port in listOf(1, 3389, 3390, 65535)) {
                val handle = RdpNative.create(callback, "100.64.0.8", "100.64.0.7", "ab".repeat(32), "fixture-user", "", "fixture-only", port)
                check(handle != 0L) { "Native rejected valid port $port" }
                RdpNative.destroy(handle)
            }
            for (port in listOf(-1, 0, 65536)) check(RdpNative.create(callback, "100.64.0.8", "100.64.0.7", "ab".repeat(32), "fixture-user", "", "fixture-only", port) == 0L)
        } finally { callback.close() }
    } finally {
        directory.listFiles()?.forEach { it.delete() }; directory.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
