package com.datamonitor.app.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

const val SEC_PHOTOS = "p"
const val SEC_VIDEOS = "v"
const val SEC_FILES = "f"

data class Node(
    val id: String,
    val parent: String,   // id папки або ключ розділу (p/v/f)
    val section: String,
    val folder: Boolean,
    val name: String,
    val mime: String,
    val size: Long,
    val added: Long
) {
    val isImage get() = !folder && mime.startsWith("image/")
    val isVideo get() = !folder && mime.startsWith("video/")
}

fun sectionOf(mime: String): String = when {
    mime.startsWith("image/") -> SEC_PHOTOS
    mime.startsWith("video/") -> SEC_VIDEOS
    else -> SEC_FILES
}

class VaultStore private constructor(app: Context) {

    companion object {
        @Volatile private var inst: VaultStore? = null
        fun get(c: Context): VaultStore =
            inst ?: synchronized(this) { inst ?: VaultStore(c.applicationContext).also { inst = it } }
        fun peek(): VaultStore? = inst
        private const val BACKUP_MAGIC = 0x444D4231
        private val NAME_OK = Regex("^(i|d/[0-9a-f]{1,32}|t/[0-9a-f]{1,32})$")
    }

    private val appCtx = app
    private val root get() = File(appCtx.filesDir, "sys")
    val dataDir get() = File(root, "d").also { it.mkdirs() }
    val thumbDir get() = File(root, "t").also { it.mkdirs() }
    private val keyFile get() = File(root, "k")
    private val indexFile get() = File(root, "i")

    @Volatile var master: ByteArray? = null
        private set
    private val nodes = ArrayList<Node>()
    private val jobs = AtomicInteger(0)
    @Volatile private var locked = true
    private val thumbCache = LruCache<String, ImageBitmap>(80)

    /** Лічильник змін — UI читає його, щоб перемальовуватись. */
    var rev by mutableIntStateOf(0)

    fun exists() = keyFile.exists()

    // ---------- ключі ----------
    @Synchronized
    fun create(pw: CharArray) {
        root.mkdirs(); dataDir; thumbDir
        val m = VaultCrypto.random(32)
        writeAtomic(keyFile, VaultCrypto.ksEncrypt(VaultCrypto.wrap(m, pw)))
        master = m
        nodes.clear()
        saveIndex()
        locked = false
        rev++
    }

    fun unlock(pw: CharArray): Boolean {
        try {
            if (!keyFile.exists()) return false
            val w = VaultCrypto.ksDecrypt(keyFile.readBytes())
            val m = VaultCrypto.unwrap(w, pw) ?: return false
            synchronized(this) {
                loadIndex(m)
                master = m
                locked = false
                rev++
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun lock() {
        locked = true
        if (jobs.get() == 0) wipe()
    }

    @Synchronized
    private fun wipe() {
        master?.fill(0)
        master = null
        nodes.clear()
        thumbCache.evictAll()
        rev++
    }

    /** Фонова робота бере ключ і не дає його стерти, поки не завершиться. */
    @Synchronized
    fun jobStart(): ByteArray? {
        val m = master ?: return null
        jobs.incrementAndGet()
        return m
    }

    fun jobEnd() {
        if (jobs.decrementAndGet() == 0 && locked) wipe()
    }

    fun changePassword(old: CharArray, new: CharArray): Boolean {
        return try {
            val w = VaultCrypto.ksDecrypt(keyFile.readBytes())
            VaultCrypto.unwrap(w, old) ?: return false
            val m = master ?: return false
            writeAtomic(keyFile, VaultCrypto.ksEncrypt(VaultCrypto.wrap(m, new)))
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun destroy() {
        wipe()
        root.deleteRecursively()
        VaultCrypto.ksDelete()
    }

    // ---------- індекс ----------
    private fun loadIndex(m: ByteArray) {
        nodes.clear()
        if (!indexFile.exists()) return
        val arr = JSONArray(String(VaultCrypto.open(m, indexFile.readBytes()), Charsets.UTF_8))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            nodes.add(
                Node(
                    o.getString("id"), o.getString("p"), o.getString("s"), o.getBoolean("d"),
                    o.getString("n"), o.getString("m"), o.getLong("z"), o.getLong("a")
                )
            )
        }
    }

    private fun saveIndex() {
        val m = master ?: return
        val arr = JSONArray()
        for (n in nodes) {
            arr.put(
                JSONObject().put("id", n.id).put("p", n.parent).put("s", n.section).put("d", n.folder)
                    .put("n", n.name).put("m", n.mime).put("z", n.size).put("a", n.added)
            )
        }
        writeAtomic(indexFile, VaultCrypto.seal(m, arr.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val t = File(f.path + ".tmp")
        t.writeBytes(data)
        t.renameTo(f)
    }

    // ---------- вузли ----------
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 20)

    @Synchronized
    fun children(parent: String): List<Node> {
        val l = nodes.filter { it.parent == parent }
        return l.filter { it.folder }.sortedBy { it.name.lowercase() } +
            l.filter { !it.folder }.sortedByDescending { it.added }
    }

    @Synchronized
    fun find(id: String): Node? = nodes.firstOrNull { it.id == id }

    @Synchronized
    fun countIn(section: String): Int = nodes.count { it.section == section && !it.folder }

    @Synchronized
    fun totalBytes(): Long = nodes.filter { !it.folder }.sumOf { it.size }

    @Synchronized
    private fun uniqueName(parent: String, name: String): String {
        val used = nodes.filter { it.parent == parent }.map { it.name }.toSet()
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while ("$base ($i)$ext" in used) i++
        return "$base ($i)$ext"
    }

    @Synchronized
    fun addFolder(parent: String, section: String, name: String): Node {
        val n = Node(newId(), parent, section, true, uniqueName(parent, name), "", 0, System.currentTimeMillis())
        nodes.add(n); saveIndex(); rev++
        return n
    }

    @Synchronized
    fun addFile(id: String, parent: String, section: String, name: String, mime: String, size: Long): Node {
        val n = Node(id, parent, section, false, uniqueName(parent, name), mime, size, System.currentTimeMillis())
        nodes.add(n); saveIndex(); rev++
        return n
    }

    private fun descendants(ids: Collection<String>): Set<String> {
        val out = HashSet<String>(ids)
        var grew = true
        while (grew) {
            grew = false
            for (n in nodes) if (n.parent in out && out.add(n.id)) grew = true
        }
        return out
    }

    @Synchronized
    fun delete(ids: Collection<String>) {
        val all = descendants(ids)
        for (n in nodes.filter { it.id in all && !it.folder }) {
            File(dataDir, n.id).delete()
            File(thumbDir, n.id).delete()
            thumbCache.remove(n.id)
        }
        nodes.removeAll { it.id in all }
        saveIndex(); rev++
    }

    /** Після винесення файлів прибирає папки, які стали порожніми. */
    @Synchronized
    fun pruneEmptyFolders(ids: Collection<String>) {
        val cand = descendants(ids).filter { id -> nodes.any { it.id == id && it.folder } }.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for (id in cand.toList()) {
                if (nodes.none { it.parent == id }) {
                    nodes.removeAll { it.id == id }; cand.remove(id); changed = true
                }
            }
        }
        saveIndex(); rev++
    }

    fun filesUnder(ids: Collection<String>): List<Node> {
        synchronized(this) {
            val all = descendants(ids)
            return nodes.filter { it.id in all && !it.folder }
        }
    }

    // ---------- файли ----------
    fun openReader(id: String): VaultCrypto.Reader? {
        val m = master ?: return null
        val f = File(dataDir, id)
        return if (f.exists()) VaultCrypto.Reader(m, f) else null
    }

    fun putThumb(id: String, jpeg: ByteArray) {
        val m = master ?: return
        writeAtomic(File(thumbDir, id), VaultCrypto.seal(m, jpeg))
    }

    fun thumb(id: String): ImageBitmap? {
        thumbCache.get(id)?.let { return it }
        val m = master ?: return null
        val f = File(thumbDir, id)
        if (!f.exists()) return null
        return try {
            val b = VaultCrypto.open(m, f.readBytes())
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()?.also { thumbCache.put(id, it) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- резервна копія ----------
    /** false — пароль не збігається. */
    fun writeBackup(pw: CharArray, out: OutputStream): Boolean {
        val w = VaultCrypto.ksDecrypt(keyFile.readBytes())
        VaultCrypto.unwrap(w, pw) ?: return false
        val files = ArrayList<Pair<String, File>>()
        if (indexFile.exists()) files.add("i" to indexFile)
        dataDir.listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { files.add("d/${it.name}" to it) }
        thumbDir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.forEach { files.add("t/${it.name}" to it) }
        val d = DataOutputStream(out.buffered())
        d.writeInt(BACKUP_MAGIC)
        d.writeInt(w.size); d.write(w)
        d.writeInt(files.size)
        val buf = ByteArray(64 * 1024)
        for ((name, f) in files) {
            val len = f.length()
            d.writeUTF(name); d.writeLong(len)
            f.inputStream().use { ins ->
                var left = len
                while (left > 0) {
                    val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw java.io.IOException("short file")
                    d.write(buf, 0, r); left -= r
                }
            }
        }
        d.flush()
        return true
    }

    /** false — пароль не збігається або файл пошкоджений. Замінює все сховище. */
    fun restoreBackup(pw: CharArray, ins: InputStream): Boolean {
        val tmp = File(appCtx.filesDir, "sys_new")
        try {
            tmp.deleteRecursively()
            val d = DataInputStream(ins.buffered())
            if (d.readInt() != BACKUP_MAGIC) return false
            val w = ByteArray(d.readInt().also { if (it !in 1..4096) return false }); d.readFully(w)
            val m = VaultCrypto.unwrap(w, pw) ?: return false
            val count = d.readInt()
            File(tmp, "d").mkdirs(); File(tmp, "t").mkdirs()
            val buf = ByteArray(64 * 1024)
            repeat(count) {
                val name = d.readUTF()
                val len = d.readLong()
                if (!NAME_OK.matches(name)) return false
                File(tmp, name).outputStream().use { o ->
                    var left = len
                    while (left > 0) {
                        val r = d.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) return false
                        o.write(buf, 0, r); left -= r
                    }
                }
            }
            File(tmp, "k").writeBytes(VaultCrypto.ksEncrypt(w))
            synchronized(this) {
                wipe()
                val old = File(appCtx.filesDir, "sys_old")
                old.deleteRecursively()
                if (root.exists() && !root.renameTo(old)) return false
                if (!tmp.renameTo(root)) {
                    old.renameTo(root)
                    return false
                }
                old.deleteRecursively()
                loadIndex(m)
                master = m
                locked = false
                rev++
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }
}
