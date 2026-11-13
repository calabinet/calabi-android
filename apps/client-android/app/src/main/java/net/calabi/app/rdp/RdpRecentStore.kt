package net.calabi.app.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class RdpRecent(val deviceKey: String, val name: String, val overlay: String, val port: Int, val usedAt: Long, val previewVersion: Long = 0)

/** Local successful connections; no credentials. Partitioned by the same account/network scope as credentials. */
internal class RdpRecentStore(context: Context, preferences: String = "rdp-recent") {
    private val prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
    private val previews = File(context.noBackupFilesDir, "$preferences-previews")
    private fun key(scope: String): String {
        require(scope.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun load(scope: String): List<RdpRecent> = synchronized(lock) {
        val items = JSONArray(prefs.getString(key(scope), "[]"))
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val record = RdpRecent(item.getString("device_key"), item.getString("name"), item.getString("overlay"), item.getInt("port"), item.getLong("used_at"))
            record.copy(previewVersion = previewFile(scope, record).lastModified())
        }.also { records ->
            require(records.size <= 20 && records.all { it.deviceKey.isNotBlank() && it.port in 1..65535 && it.usedAt > 0 })
        }.sortedByDescending { it.usedAt }
    }

    fun record(scope: String, record: RdpRecent) = synchronized(lock) {
        require(record.deviceKey.isNotBlank() && record.port in 1..65535 && record.usedAt > 0)
        val previous = load(scope)
        val retained = (previous.filterNot { it.deviceKey == record.deviceKey && it.port == record.port } + record)
            .sortedByDescending { it.usedAt }.take(20)
        save(scope, retained)
        previous.filter { old -> retained.none { it.deviceKey == old.deviceKey && it.port == old.port } }
            .forEach { AtomicFile(previewFile(scope, it)).delete() }
    }

    fun remove(scope: String, record: RdpRecent) = synchronized(lock) {
        save(scope, load(scope).filterNot { it.deviceKey == record.deviceKey && it.port == record.port })
        AtomicFile(previewFile(scope, record)).delete()
    }

    internal fun previewFile(scope: String, record: RdpRecent): File {
        require(record.deviceKey.isNotBlank() && record.port in 1..65535)
        val endpoint = JSONObject().put("device", record.deviceKey).put("port", record.port).toString()
        return File(File(previews, key(scope)), key(endpoint) + ".jpg")
    }

    fun savePreview(scope: String, record: RdpRecent, image: Bitmap): Boolean = synchronized(lock) {
        require(image.width in 1..640 && image.height in 1..640)
        // A queued frame must not recreate a preview after its history was removed or evicted.
        if (load(scope).none { it.deviceKey == record.deviceKey && it.port == record.port }) return@synchronized false
        val file = previewFile(scope, record)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { check(image.compress(Bitmap.CompressFormat.JPEG, 75, output)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        true
    }

    fun loadPreview(scope: String, record: RdpRecent): Bitmap? = synchronized(lock) {
        val file = previewFile(scope, record)
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized null
        val atomic = AtomicFile(file)
        atomic.openRead().use { check(file.length() <= 512 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        atomic.openRead().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..640) return@synchronized null
        atomic.openRead().use { BitmapFactory.decodeStream(it) }
    }

    private fun save(scope: String, records: List<RdpRecent>) {
        val items = JSONArray()
        records.forEach { item -> items.put(JSONObject().put("device_key", item.deviceKey).put("name", item.name)
            .put("overlay", item.overlay).put("port", item.port).put("used_at", item.usedAt)) }
        check(prefs.edit().putString(key(scope), items.toString()).commit())
    }

    companion object { private val lock = Any() }
}
