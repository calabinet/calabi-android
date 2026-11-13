package net.calabi.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.calabi.app.CalabiApp
import net.calabi.app.CoreClient
import net.calabi.app.R
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

internal data class ReceivedSource(val id: String, val name: String, val size: Long, val sha256: String, val owner: String)
internal data class ReceivedDocument(val uri: Uri, val name: String, val mime: String, val generation: Long)

/** Only the verified completed-file API can populate this narrow FileProvider cache. */
internal class ReceivedFileCache(context: Context,
    private val lookup: (String) -> ReceivedSource = ::receivedSource,
    private val export: (String, Int) -> Unit = { id, fd -> CalabiApp.instance.core.saveFile(id, fd) },
    private val generation: () -> Long = { CoreClient.session.requestGeneration() },
    namespace: String = "live") {
    private val app = context.applicationContext
    private val root = File(app.cacheDir, "received-previews/$namespace")
    private val mutex = Mutex()
    private data class Key(val source: ReceivedSource, val generation: Long)
    private data class Entry(val file: File, val document: ReceivedDocument)
    private val entries = mutableMapOf<Key, Entry>()

    init { require(namespace.matches(Regex("[A-Za-z0-9-]+"))) }

    suspend fun prepare(id: String): ReceivedDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val epoch = generation()
            val source = lookup(id)
            require(source.id == id && source.size >= 0 && source.sha256.matches(Regex("[a-fA-F0-9]{64}")))
            check(epoch == generation())
            check(root.mkdirs() || root.isDirectory)
            prune()
            val key = Key(source, epoch)
            entries[key]?.takeIf { it.file.isFile && it.file.length() == source.size }?.let {
                it.file.parentFile?.setLastModified(System.currentTimeMillis())
                return@withLock it.document
            }
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.mkdir())
            val temporary = File(directory, "incomplete.part")
            try {
                ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { fd ->
                    ParcelFileDescriptor.dup(fd.fileDescriptor).use { duplicate -> export(id, duplicate.detachFd()) }
                }
                check(epoch == generation())
                check(temporary.length() == source.size)
                val file = File(directory, safeDocumentName(source.name))
                check(temporary.renameTo(file))
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.received-files", file)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
                ReceivedDocument(uri, file.name, mime, epoch).also { entries[key] = Entry(file, it) }
            } catch (error: Exception) {
                discard(directory)
                throw error
            }
        }
    }

    suspend fun launch(context: Context, id: String, share: Boolean): Int? = try {
        val document = prepare(id)
        check(document.generation == generation())
        val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            if (share) {
                type = document.mime
                putExtra(Intent.EXTRA_STREAM, document.uri)
                putExtra(Intent.EXTRA_TITLE, document.name)
            } else setDataAndType(document.uri, document.mime)
            clipData = ClipData.newRawUri(document.name, document.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(if (share) Intent.createChooser(intent, context.getString(R.string.action_share)).apply {
            clipData = intent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else intent)
        null
    } catch (e: CancellationException) { throw e }
    catch (_: ActivityNotFoundException) { if (share) R.string.files_share_no_app else R.string.files_open_no_app }
    catch (_: Exception) { R.string.files_open_unavailable }

    suspend fun forget(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            entries.filterKeys { it.source.id == id }.values.forEach { discard(it.file.parentFile!!) }
            entries.keys.removeAll { it.source.id == id }
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < cutoff }.forEach(::discard)
        entries.entries.removeAll { !it.value.file.isFile }
    }

    private fun discard(directory: File) {
        // Never follow a path out of the cache namespace or remove user documents.
        if (directory.canonicalFile.parentFile != root.canonicalFile) return
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.received-files", file)
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                file.delete()
            }
        }
        directory.delete()
    }
}

internal object ReceivedFiles {
    val cache by lazy { ReceivedFileCache(CalabiApp.instance) }
}

private fun receivedSource(id: String): ReceivedSource {
    val response = CoreClient.callBlocking("GET", "/v1/files")
    if (!response.ok) throw IOException("file service unavailable")
    val rows = response.json().optJSONArray("received") ?: throw IOException("unknown transfer")
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val file = row.optJSONObject("transfer") ?: continue
        if (file.optString("id") == id && file.optString("state") == "completed") {
            return ReceivedSource(id, file.optString("name"), file.optLong("size"), file.optString("sha256"), row.optString("device_key"))
        }
    }
    throw IOException("file not completed or no longer available")
}

private fun safeDocumentName(original: String): String {
    val cleaned = original.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().trim('.')
    val extension = cleaned.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) }
    val base = if (extension != null) cleaned.substringBeforeLast('.') else cleaned
    val points = base.codePoints().limit(48).toArray()
    return String(points, 0, points.size).ifBlank { "document" } + (extension?.let { ".$it" } ?: "")
}
