package net.calabi.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import net.calabi.app.rdp.RdpCredentials
import net.calabi.app.rdp.RdpCredentialStore
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/** Real Android Keystore roundtrip, account/device isolation and authenticated ciphertext. */
class RdpCredentialStoreTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        val alias = "calabi-rdp-credential-test"
        val directory = File(targetContext.noBackupFilesDir, "rdp-credential-test")
        try {
            val store = RdpCredentialStore(targetContext, directory, alias)
            val original = RdpCredentials("测试-user", "测试-domain", "fixture-only-密码-🔐")
            val state = JSONObject().put("signed_in", true).put("mode", "platform").put("user_id", 1).put("active_org_id", 2)
            val identity = RdpCredentialStore.scope(state, "https://example.test")!!
            check(RdpCredentialStore.scope(JSONObject(), "https://example.test") == null)
            check(RdpCredentialStore.scope(state.put("user_id", 3), "https://example.test") != identity)
            check(RdpCredentialStore.scope(state.put("user_id", 1).put("active_org_id", 4), "https://example.test") != identity)
            check(store.load(identity, "device-a") == null)
            store.save(identity, "device-a", original)
            check(RdpCredentialStore(targetContext, directory, alias).load(identity, "device-a") == original)
            check(store.load(identity, "device-b") == null)
            check(store.load("other-account", "device-a") == null)
            val ciphertext = store.file(identity, "device-a").readBytes()
            check(!ciphertext.toString(Charsets.UTF_8).contains(original.password))
            // A copied ciphertext cannot authenticate under another device's identity.
            store.file(identity, "device-b").writeBytes(ciphertext)
            check(runCatching { store.load(identity, "device-b") }.isFailure)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            store.file(identity, "device-a").writeBytes(ciphertext)
            check(runCatching { store.load(identity, "device-a") }.isFailure)
            store.save(identity, "device-a", original.copy(password = "replacement-fixture"))
            check(store.load(identity, "device-a")?.password == "replacement-fixture")
            store.forget(identity, "device-a")
            check(store.load(identity, "device-a") == null)
            results.putString("stream", "\nKeystore credential roundtrip, isolation, tamper rejection, update and forget passed\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
