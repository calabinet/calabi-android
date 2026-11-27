package net.calabi.app.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
internal data class ReceivedDocument(val uri: Uri, val name: String, val mime: String, val generation: Long, val size: Long)

private const val APK_MIME = "application/vnd.android.package-archive"
private val calabiDownloadsPath = "${Environment.DIRECTORY_DOWNLOADS}/Calabi/"
private val calabiDownloadsFolder = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents", "primary:${calabiDownloadsPath.trimEnd('/')}",
)

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
                val extension = file.extension.lowercase(Locale.ROOT)
                val mime = if (extension == "apk") APK_MIME
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                ReceivedDocument(uri, file.name, mime, epoch, source.size).also { entries[key] = Entry(file, it) }
            } catch (error: Exception) {
                discard(directory)
                throw error
            }
        }
    }

    suspend fun launch(context: Context, id: String, share: Boolean): Int? {
        return try {
            val document = prepare(id)
            check(document.generation == generation())
            // Let the file manager hand packages to Android's installer using its own authorization.
            if (!share && document.mime == APK_MIME) return R.string.files_open_no_app
            val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if (share) {
                    type = document.mime
                    putExtra(Intent.EXTRA_STREAM, document.uri)
                    putExtra(Intent.EXTRA_TITLE, document.name)
                } else setDataAndType(document.uri, document.mime)
                clipData = ClipData.newRawUri(document.name, document.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val external = if (share) Intent.createChooser(intent, context.getString(R.string.action_share)).apply {
                clipData = intent.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else intent
            when {
                context.tryOpen(external) -> null
                share -> R.string.files_share_no_app
                else -> R.string.files_open_no_app
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { R.string.files_open_unavailable }
    }

    suspend fun openInDownloads(context: Context, id: String, saveAs: (ReceivedDocument) -> Unit): Int? = try {
        val document = prepare(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveInCalabiDownloads(document)
            if (!context.tryOpen(Intent(Intent.ACTION_VIEW).setDataAndType(calabiDownloadsFolder, DocumentsContract.Document.MIME_TYPE_DIR))) {
                context.tryOpen(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).putExtra(DocumentsContract.EXTRA_INITIAL_URI, calabiDownloadsFolder))
            }
            R.string.files_saved_in_calabi_downloads
        } else {
            saveAs(document)
            null
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { R.string.files_open_unavailable }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveInCalabiDownloads(document: ReceivedDocument) = withContext(Dispatchers.IO) {
        check(document.generation == generation())
        val resolver = app.contentResolver
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, document.name)
            put(MediaStore.MediaColumns.MIME_TYPE, document.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, calabiDownloadsPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("could not create a download")
        try {
            writeCopy(document, destination)
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (e: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            throw e
        }
    }

    suspend fun saveAs(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        try {
            writeCopy(prepare(id), destination)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }
            throw e
        }
    }

    private suspend fun writeCopy(document: ReceivedDocument, destination: Uri) {
        check(document.generation == generation())
        val resolver = app.contentResolver
        val count = requireNotNull(resolver.openInputStream(document.uri)).use { input ->
            requireNotNull(resolver.openOutputStream(destination, "wt")).use { output -> input.copyTo(output) }
        }
        currentCoroutineContext().ensureActive()
        check(count == document.size && document.generation == generation())
    }

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

internal fun receivedSaveIntent(document: ReceivedDocument): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = document.mime
    putExtra(Intent.EXTRA_TITLE, document.name)
    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"))
}

private fun Context.tryOpen(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) { false }
catch (_: SecurityException) { false }

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
