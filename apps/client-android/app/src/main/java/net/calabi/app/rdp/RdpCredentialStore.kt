package net.calabi.app.rdp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class RdpCredentials(val user: String, val domain: String, val password: String)

/** App-private ciphertext excluded from backup; the AES key never leaves Android Keystore. */
internal class RdpCredentialStore(
    context: Context,
    private val directory: File = File(context.noBackupFilesDir, "rdp-credentials-v1"),
    private val alias: String = "calabi-rdp-credentials-v1",
) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun identity(scope: String, device: String, port: Int): ByteArray {
        require(scope.isNotBlank() && device.isNotBlank() && port in 1..65535)
        return JSONObject().put("scope", scope).put("device", device).apply { if (port != 3389) put("port", port) }.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun file(scope: String, device: String, port: Int = 3389): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest(identity(scope, device, port)).joinToString("") { "%02x".format(it) })

    fun load(scope: String, device: String, port: Int = 3389): RdpCredentials? {
        val atomic = AtomicFile(file(scope, device, port))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val buffer = ByteArray(65537)
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            buffer.copyOf(count)
        }
        require(bytes.size in 30..65536 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(identity(scope, device, port))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            RdpCredentials(json.getString("user"), json.getString("domain"), json.getString("password"))
        } finally { plain.fill(0) }
    }

    fun save(scope: String, device: String, credentials: RdpCredentials, port: Int = 3389) {
        val plain = JSONObject().put("user", credentials.user).put("domain", credentials.domain)
            .put("password", credentials.password).toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 60000)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(identity(scope, device, port))
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(scope, device, port))
        val output = atomic.startWrite()
        try { output.write(encrypted); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    fun forget(scope: String, device: String, port: Int = 3389) { AtomicFile(file(scope, device, port)).delete() }

    companion object {
        /** Mirrors the core's account/network scope; never uses a mutable overlay IP as identity. */
        fun scope(state: JSONObject, bff: String): String? {
            if (!state.optBoolean("signed_in")) return null
            return when (state.optString("mode")) {
                "platform" -> if (state.optLong("user_id") > 0 && state.optLong("active_org_id") > 0)
                    JSONObject().put("bff", bff).put("user", state.getLong("user_id"))
                        .put("org", state.getLong("active_org_id")).toString() else null
                "self_hosted" -> state.optString("server").takeIf { it.isNotBlank() }?.let {
                    JSONObject().put("server", it).toString()
                }
                else -> null
            }
        }
    }
}
