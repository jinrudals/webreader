package app.webreader.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

data class CachedResource(val mimeType: String, val encoding: String?, val bytes: ByteArray)

/**
 * Disk LRU cache keyed by opaque strings. Size accounting counts payload bytes only (sidecars excluded).
 * All public methods are serialized by a single lock.
 */
class ResourceCache(
    private val dir: File,
    private val maxBytes: () -> Long,
    private val protectedKeys: () -> Set<String>,
) {
    private class Meta(val size: Long, var access: Long)

    private val lock = Any()
    private val index = HashMap<String, Meta>()
    private var total = 0L
    private var counter = 0L
    private var loaded = false

    fun maxBytes(): Long = maxBytes.invoke()

    fun sizeBytes(): Long = synchronized(lock) { ensureLoaded(); total }

    fun get(key: String): CachedResource? = synchronized(lock) {
        ensureLoaded()
        val meta = index[key] ?: return null
        val loadedRes = runCatching {
            val parsed = readMeta(metaFile(key))
            val bytes = payloadFile(key).readBytes()
            if (bytes.size.toLong() != meta.size) error("size mismatch")
            CachedResource(parsed.mime, parsed.encoding, bytes)
        }.getOrNull()
        if (loadedRes == null) {
            remove(key)
            return null
        }
        meta.access = ++counter
        runCatching { writeMeta(key, loadedRes.mimeType, loadedRes.encoding, meta) }
        loadedRes
    }

    fun put(key: String, res: CachedResource): Boolean = synchronized(lock) {
        ensureLoaded()
        val limit = maxBytes()
        val size = res.bytes.size.toLong()
        if (size > limit) return false
        val existing = index[key]
        var projected = total - (existing?.size ?: 0L) + size
        val victims = ArrayList<String>()
        if (projected > limit) {
            val protected = protectedKeys()
            val candidates = index.entries
                .filter { it.key != key && it.key !in protected }
                .sortedBy { it.value.access }
            for (c in candidates) {
                if (projected <= limit) break
                victims += c.key
                projected -= c.value.size
            }
            if (projected > limit) return false
        }
        val meta = Meta(size, ++counter)
        try {
            dir.mkdirs()
            val tmp = File(dir, "${name(key)}.bin.tmp")
            tmp.writeBytes(res.bytes)
            renameOver(tmp, payloadFile(key))
            writeMeta(key, res.mimeType, res.encoding, meta)
        } catch (e: Exception) {
            remove(key)
            return false
        }
        existing?.let { total -= it.size }
        index[key] = meta
        total += size
        victims.forEach { remove(it) }
        true
    }

    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        index.clear()
        total = 0L
        loaded = true
    }

    fun removePrefix(prefix: String) = synchronized(lock) {
        ensureLoaded()
        index.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
    }

    private fun remove(key: String) {
        index.remove(key)?.let { total -= it.size }
        payloadFile(key).delete()
        metaFile(key).delete()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val files = dir.listFiles() ?: return
        for (f in files) {
            when {
                f.name.endsWith(".tmp") -> f.delete()
                f.name.endsWith(".meta") -> {
                    val bin = File(dir, f.name.removeSuffix(".meta") + ".bin")
                    val parsed = runCatching { readMeta(f) }.getOrNull()
                    if (parsed == null || !bin.isFile || bin.length() != parsed.meta.size || name(parsed.key) != f.name.removeSuffix(".meta")) {
                        f.delete(); bin.delete()
                    } else {
                        index[parsed.key] = parsed.meta
                        total += parsed.meta.size
                        if (parsed.meta.access > counter) counter = parsed.meta.access
                    }
                }
            }
        }
        files.filter { it.name.endsWith(".bin") && !File(dir, it.name.removeSuffix(".bin") + ".meta").exists() }
            .forEach { it.delete() }
    }

    private class Parsed(val mime: String, val encoding: String?, val meta: Meta, val key: String)

    private fun readMeta(f: File): Parsed = DataInputStream(f.inputStream().buffered()).use {
        val key = it.readUTF()
        val mime = it.readUTF()
        val enc = if (it.readBoolean()) it.readUTF() else null
        val size = it.readLong()
        val access = it.readLong()
        Parsed(mime, enc, Meta(size, access), key)
    }

    private fun writeMeta(key: String, mime: String, enc: String?, meta: Meta) {
        val tmp = File(dir, "${name(key)}.meta.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use {
            it.writeUTF(key)
            it.writeUTF(mime)
            it.writeBoolean(enc != null)
            if (enc != null) it.writeUTF(enc)
            it.writeLong(meta.size)
            it.writeLong(meta.access)
        }
        renameOver(tmp, metaFile(key))
    }

    private fun renameOver(from: File, to: File) {
        java.nio.file.Files.move(
            from.toPath(), to.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun payloadFile(key: String) = File(dir, name(key) + ".bin")
    private fun metaFile(key: String) = File(dir, name(key) + ".meta")
}
