package com.datamonitor.app.cache

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.datamonitor.app.DataMonitorService
import com.datamonitor.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Поки йде важка робота, тримаємо процес живим через ТОЙ САМИЙ foreground-сервіс моніторингу
 * (те саме сповіщення зі статистикою). Окремого сповіщення і нових дозволів немає.
 */
object CacheKeepAlive {
    private val n = AtomicInteger(0)

    fun acquire(ctx: Context) {
        if (n.getAndIncrement() == 0) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DataMonitorService::class.java))
        }
    }

    fun release(ctx: Context) {
        if (n.decrementAndGet() == 0 && !Prefs(ctx).monitoringEnabled) {
            ctx.stopService(Intent(ctx, DataMonitorService::class.java))
        }
    }
}

object ImageUtil {
    fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxDim && o.outHeight / (s * 2) >= maxDim) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            ?: return null
        val rot = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        return if (rot == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }

    fun jpeg(b: Bitmap, q: Int = 80): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return o.toByteArray()
    }

    fun scaleDown(b: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxDim) return b
        val f = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }
}

object CacheJobs {
    class Progress(val done: Int, val total: Int)

    val progress = mutableStateOf<Progress?>(null)
    val message = mutableStateOf<String?>(null)
    val pendingDelete = mutableStateOf<List<Uri>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MEDIA_DOCS = "com.android.providers.media.documents"

    /** Куди класти: resolve(розділ за типом файлу) -> (parentId, section). */
    class Dest(val resolve: (String) -> Pair<String, String>) {
        fun child(store: CacheStore, name: String): Dest {
            val cache = HashMap<String, Pair<String, String>>()
            return Dest { sec ->
                val (p, s) = resolve(sec)
                cache.getOrPut("$p|$s") { Pair(store.addFolder(p, s, name).id, s) }
            }
        }
    }

    fun destFor(section: String?, parent: String?): Dest =
        if (section == null || parent == null) Dest { sec -> Pair(sec, sec) }
        else Dest { _ -> Pair(parent, section) }

    private class Meta(val name: String, val size: Long, val mime: String)

    private fun meta(cr: ContentResolver, uri: Uri): Meta {
        var name = "file"
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        var mime = cr.getType(uri) ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        return Meta(name, size, mime)
    }

    private fun videoThumb(app: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            val f = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
            f?.let { ImageUtil.scaleDown(it, 256) }
        } catch (e: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Шифрує один файл. true — файл надійно збережено (можна видаляти оригінал). */
    private fun importOne(app: Context, store: CacheStore, master: ByteArray, uri: Uri, dest: Dest, nameHint: String? = null): Boolean {
        val cr = app.contentResolver
        val m = meta(cr, uri)
        val (parent, section) = dest.resolve(sectionOf(m.mime))
        val id = store.newId()
        val part = File(store.dataDir, "$id.part")
        val fin = File(store.dataDir, id)
        val plain = try {
            RandomAccessFile(part, "rw").use { raf ->
                val ins = cr.openInputStream(uri) ?: throw java.io.IOException("no stream")
                ins.use { CacheCrypto.encrypt(master, it, raf) }
            }
        } catch (e: Exception) {
            part.delete(); return false
        }
        if ((m.size >= 0 && plain != m.size) || part.length() != CacheCrypto.encryptedSize(plain) || !part.renameTo(fin)) {
            part.delete(); fin.delete(); return false
        }
        try {
            val tb: Bitmap? = when {
                m.mime.startsWith("image/") && plain <= 40L * 1024 * 1024 ->
                    store.openReader(id)?.use { r -> ImageUtil.decode(r.readAll(), 256) }
                m.mime.startsWith("video/") -> videoThumb(app, uri)
                else -> null
            }
            tb?.let { store.putThumb(id, ImageUtil.jpeg(it)) }
        } catch (_: Throwable) {
        }
        store.addFile(id, parent, section, nameHint ?: m.name, m.mime, plain)
        return true
    }

    private fun deleteOriginal(app: Context, uri: Uri, mediaFallback: MutableList<Uri>): Boolean {
        try {
            if (DocumentsContract.deleteDocument(app.contentResolver, uri)) return true
        } catch (_: Exception) {
        }
        try {
            if (uri.authority == MEDIA_DOCS) {
                val docId = DocumentsContract.getDocumentId(uri)
                val type = docId.substringBefore(':')
                val id = docId.substringAfter(':').toLong()
                val base = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                mediaFallback.add(ContentUris.withAppendedId(base, id))
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun busy(): Boolean {
        if (progress.value != null) {
            message.value = "Зачекайте завершення поточної операції"
            return true
        }
        return false
    }

    // ---------- імпорт вибраних файлів ----------
    fun importDocs(ctx: Context, uris: List<Uri>, section: String?, parent: String?) {
        if (uris.isEmpty() || busy()) return
        val app = ctx.applicationContext
        val store = CacheStore.get(app)
        val master = store.jobStart() ?: return
        CacheKeepAlive.acquire(app)
        progress.value = Progress(0, uris.size)
        scope.launch {
            var ok = 0
            val media = ArrayList<Uri>()
            try {
                val dest = destFor(section, parent)
                uris.forEachIndexed { i, u ->
                    if (importOne(app, store, master, u, dest)) {
                        ok++
                        deleteOriginal(app, u, media)
                    }
                    progress.value = Progress(i + 1, uris.size)
                }
                finish(ok, uris.size - ok, media)
            } catch (e: Throwable) {
                message.value = "Помилка: ${e.message}"
            } finally {
                progress.value = null
                store.jobEnd()
                CacheKeepAlive.release(app)
            }
        }
    }

    private fun finish(ok: Int, failed: Int, media: List<Uri>) {
        message.value = buildString {
            append("Переміщено: $ok")
            if (failed > 0) append(". Не вдалося: $failed (оригінали залишились)")
        }
        if (media.isNotEmpty()) pendingDelete.value = media
    }

    // ---------- імпорт цілої папки ----------
    private class Child(val id: String, val name: String, val mime: String)

    private fun listChildren(cr: ContentResolver, tree: Uri, docId: String): List<Child> {
        val out = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        cr.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: ""))
        }
        return out
    }

    private fun isDir(c: Child) = c.mime == DocumentsContract.Document.MIME_TYPE_DIR

    private fun countFiles(cr: ContentResolver, tree: Uri, docId: String): Int =
        listChildren(cr, tree, docId).sumOf { if (isDir(it)) countFiles(cr, tree, it.id) else 1 }

    private class Counter(val total: Int) { var done = 0 }

    private fun walk(app: Context, store: CacheStore, master: ByteArray, tree: Uri, docId: String, dest: Dest, st: Counter): Boolean {
        val cr = app.contentResolver
        var allOk = true
        for (c in listChildren(cr, tree, docId)) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.id)
            if (isDir(c)) {
                val ok = walk(app, store, master, tree, c.id, dest.child(store, c.name), st)
                // каталог видаляємо лише якщо все всередині успішно перенесене
                if (ok) {
                    val del = try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }
                    if (!del) allOk = false
                } else allOk = false
            } else {
                val ok = importOne(app, store, master, uri, dest, c.name)
                if (ok) {
                    val del = try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }
                    if (!del) allOk = false
                } else allOk = false
                st.done++
                progress.value = Progress(st.done, st.total)
            }
        }
        return allOk
    }

    fun importTree(ctx: Context, tree: Uri, section: String?, parent: String?) {
        if (busy()) return
        val app = ctx.applicationContext
        val store = CacheStore.get(app)
        val master = store.jobStart() ?: return
        CacheKeepAlive.acquire(app)
        progress.value = Progress(0, 0)
        scope.launch {
            try {
                val cr = app.contentResolver
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                val rootName = meta(cr, rootUri).name
                val st = Counter(countFiles(cr, tree, rootId))
                progress.value = Progress(0, st.total)
                val allOk = walk(app, store, master, tree, rootId, destFor(section, parent).child(store, rootName), st)
                var rootGone = false
                if (allOk) rootGone = try { DocumentsContract.deleteDocument(cr, rootUri) } catch (e: Exception) { false }
                message.value = if (allOk) "Папку переміщено (${st.done} файлів)"
                else "Частину файлів не вдалося перенести або видалити з телефону — вони залишились на місці"
                if (allOk && !rootGone) message.value = message.value + ". Порожню папку видаліть вручну"
            } catch (e: Throwable) {
                message.value = "Помилка: ${e.message}"
            } finally {
                progress.value = null
                store.jobEnd()
                CacheKeepAlive.release(app)
            }
        }
    }

    // ---------- винесення назад на телефон ----------
    fun extract(ctx: Context, ids: List<String>, tree: Uri) {
        if (busy()) return
        val app = ctx.applicationContext
        val store = CacheStore.get(app)
        val master = store.jobStart() ?: return
        CacheKeepAlive.acquire(app)
        val files = store.filesUnder(ids)
        progress.value = Progress(0, files.size)
        scope.launch {
            try {
                val cr = app.contentResolver
                val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val dirCache = HashMap<String, Uri>()
                val base = ids.firstOrNull()?.let { store.find(it)?.parent }
                // створює (за потреби) каталог для вузла
                fun dirFor(parentId: String): Uri {
                    if (parentId == base || parentId.length <= 1) return rootDoc  // корінь вибору або ключ розділу
                    return dirCache.getOrPut(parentId) {
                        val n = store.find(parentId)
                        val up = dirFor(n?.parent ?: "p")
                        DocumentsContract.createDocument(cr, up, DocumentsContract.Document.MIME_TYPE_DIR, n?.name ?: "folder") ?: rootDoc
                    }
                }
                val okIds = ArrayList<String>()
                files.forEachIndexed { i, n ->
                    try {
                        val dir = dirFor(n.parent)
                        val doc = DocumentsContract.createDocument(cr, dir, n.mime.ifEmpty { "application/octet-stream" }, n.name)
                        if (doc != null) {
                            store.openReader(n.id)?.use { r ->
                                cr.openOutputStream(doc)?.use { o ->
                                    val buf = ByteArray(64 * 1024)
                                    var pos = 0L
                                    while (true) {
                                        val k = r.read(pos, buf, 0, buf.size)
                                        if (k < 0) break
                                        o.write(buf, 0, k); pos += k
                                    }
                                    if (pos == n.size) okIds.add(n.id)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    progress.value = Progress(i + 1, files.size)
                }
                store.delete(okIds)
                store.pruneEmptyFolders(ids)
                message.value = "Винесено: ${okIds.size}" + if (okIds.size < files.size) ". Не вдалося: ${files.size - okIds.size}" else ""
            } catch (e: Throwable) {
                message.value = "Помилка: ${e.message}"
            } finally {
                progress.value = null
                store.jobEnd()
                CacheKeepAlive.release(app)
            }
        }
    }
}
