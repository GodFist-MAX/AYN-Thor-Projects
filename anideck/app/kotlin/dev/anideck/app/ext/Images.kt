package dev.anideck.app.ext

import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.CacheControl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Cover art and icons, fetched the way their source expects and kept on disk. */
object Images {
    private const val MAX_BYTES = 150L * 1024 * 1024
    private val dir: File get() = File(Host.app.cacheDir, "img").apply { mkdirs() }
    private var written = 0L

    /** The cached file for url, downloading it first if needed. sourceId 0 means no particular source. */
    @JvmStatic
    fun get(sourceId: String, url: String): File? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val file = File(dir, sha1(url))
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val source = sourceId.toLongOrNull()?.takeIf { it != 0L }?.let { Extensions.source(it) } as? AnimeHttpSource
        val client = (source?.client ?: Host.network.client).newBuilder().callTimeout(40, TimeUnit.SECONDS).build()
        val headers = source?.headers?.newBuilder()?.set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")?.build()
        val tmp = File(dir, file.name + ".part")
        try {
            val request = if (headers != null) GET(url, headers, CacheControl.FORCE_NETWORK) else GET(url, cache = CacheControl.FORCE_NETWORK)
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            if (tmp.length() == 0L || !tmp.renameTo(file)) return null
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            // Not a URL OkHttp accepts.
            return null
        } finally {
            tmp.delete()
        }
        synchronized(this) {
            written += file.length()
            if (written > 8L * 1024 * 1024) {
                written = 0
                trim()
            }
        }
        return file
    }

    /** Saves a copy that outlives the cache, for downloads and the library. */
    @JvmStatic
    fun keep(sourceId: String, url: String, target: File): Boolean {
        if (target.isFile && target.length() > 0) return true
        val f = get(sourceId, url) ?: return false
        target.parentFile?.mkdirs()
        return try {
            f.copyTo(target, overwrite = true)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_BYTES * 3 / 4) break
        }
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
