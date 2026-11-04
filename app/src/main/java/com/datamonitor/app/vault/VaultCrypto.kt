package com.datamonitor.app.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Формат файлу даних: [DMV1][salt16][plainLen8] + чанки по 64 КБ (AES-GCM, +16 байт тегу).
 * Ключ файлу = HMAC(master, salt). Нонс = номер чанка. AAD = заголовок + номер + прапорець "останній".
 * Це дозволяє довільний доступ (перемотування відео) без розшифровки всього файлу.
 */
object VaultCrypto {
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val HEADER = 28
    private val MAGIC = byteArrayOf(0x44, 0x4D, 0x56, 0x01)
    private const val ITER = 200_000
    private const val KS_ALIAS = "dm_k1"
    private val rnd = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    private fun derive(pw: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pw, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** iv(12) + шифртекст */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun open(key: ByteArray, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** [iter4][salt16][seal(derived, master)] */
    fun wrap(master: ByteArray, pw: CharArray): ByteArray {
        val salt = random(16)
        val k = derive(pw, salt, ITER)
        return ByteBuffer.allocate(4 + 16).putInt(ITER).put(salt).array() + seal(k, master)
    }

    fun unwrap(w: ByteArray, pw: CharArray): ByteArray? {
        return try {
            val bb = ByteBuffer.wrap(w)
            val iter = bb.int
            val salt = ByteArray(16); bb.get(salt)
            val rest = ByteArray(bb.remaining()); bb.get(rest)
            open(derive(pw, salt, iter), rest)
        } catch (e: Exception) {
            null
        }
    }

    // ---- Android Keystore: другий шар, ключ не покидає пристрій ----
    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun ksEncrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, ksKey())
        return c.iv + c.doFinal(data)
    }

    fun ksDecrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun ksDelete() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KS_ALIAS)
        } catch (_: Exception) {
        }
    }

    // ---- файли даних ----
    private fun fileKey(master: ByteArray, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return SecretKeySpec(mac.doFinal(salt), "AES")
    }

    private fun chunkCipher(mode: Int, key: SecretKeySpec, salt: ByteArray, idx: Long, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).putLong(4, idx).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, nonce))
        c.updateAAD(MAGIC)
        c.updateAAD(salt)
        c.updateAAD(ByteBuffer.allocate(9).putLong(idx).put(if (last) 1 else 0).array())
        return c
    }

    private fun readFull(ins: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Шифрує потік у файл, повертає довжину відкритого тексту. */
    fun encrypt(master: ByteArray, input: InputStream, raf: RandomAccessFile): Long {
        val salt = random(16)
        val key = fileKey(master, salt)
        raf.setLength(0)
        raf.write(MAGIC); raf.write(salt); raf.writeLong(0L)
        var cur = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var curN = readFull(input, cur)
        var idx = 0L
        var total = 0L
        while (curN > 0) {
            val nextN = if (curN == CHUNK) readFull(input, next) else 0
            val c = chunkCipher(Cipher.ENCRYPT_MODE, key, salt, idx, nextN == 0)
            raf.write(c.doFinal(cur, 0, curN))
            total += curN
            idx++
            val t = cur; cur = next; next = t
            curN = nextN
        }
        raf.seek(20); raf.writeLong(total)
        return total
    }

    fun encryptedSize(plain: Long): Long {
        val chunks = (plain + CHUNK - 1) / CHUNK
        return HEADER + plain + chunks * TAG
    }

    class Reader(master: ByteArray, file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val salt = ByteArray(16)
        val length: Long
        private val key: SecretKeySpec
        private var cIdx = -1L
        private var cBuf = ByteArray(0)

        init {
            val h = ByteArray(HEADER)
            raf.readFully(h)
            require(h.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad file" }
            System.arraycopy(h, 4, salt, 0, 16)
            length = ByteBuffer.wrap(h, 20, 8).long
            key = fileKey(master, salt)
        }

        @Synchronized
        private fun load(i: Long) {
            if (cIdx == i) return
            val n = (length + CHUNK - 1) / CHUNK
            val last = i == n - 1
            val plainN = if (last) (length - i * CHUNK).toInt() else CHUNK
            raf.seek(HEADER + i * (CHUNK + TAG))
            val enc = ByteArray(plainN + TAG)
            raf.readFully(enc)
            cBuf = chunkCipher(Cipher.DECRYPT_MODE, key, salt, i, last).doFinal(enc)
            cIdx = i
        }

        /** Повертає кількість прочитаних байтів або -1 в кінці файлу. */
        @Synchronized
        fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (pos >= length) return -1
            val i = pos / CHUNK
            load(i)
            val o = (pos - i * CHUNK).toInt()
            val n = minOf(len, cBuf.size - o)
            System.arraycopy(cBuf, o, dst, off, n)
            return n
        }

        fun readAll(max: Long = Long.MAX_VALUE): ByteArray {
            val n = minOf(length, max).toInt()
            val out = ByteArray(n)
            var p = 0
            while (p < n) {
                val r = read(p.toLong(), out, p, n - p)
                if (r <= 0) break
                p += r
            }
            return out
        }

        override fun close() {
            try { raf.close() } catch (_: Exception) {}
        }
    }
}
