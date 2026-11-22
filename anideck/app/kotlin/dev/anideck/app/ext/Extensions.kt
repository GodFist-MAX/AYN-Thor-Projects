package dev.anideck.app.ext

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.network.GET
import okhttp3.CacheControl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Aniyomi extensions from the Yūzōnō repo: installs them privately (no system install prompt),
 * checks each one is signed with the repo's key before any of its code runs, and loads their sources.
 */
object Extensions {
    private const val TAG = "AniDeck"
    const val REPO_INDEX = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.json"

    /** SHA-256 of the certificate the Yūzōnō repo signs its extensions with. */
    private const val TRUSTED_SIGNER = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
    private const val FEATURE = "tachiyomi.animeextension"

    /** The extensions-lib versions this host implements. */
    private val SUPPORTED_LIBS = 14.0..16.0
    private const val REPO_MAX_AGE = 6 * 3600_000L

    @Suppress("DEPRECATION")
    private const val FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_CONFIGURATIONS

    class Installed(
        val pkg: String,
        val name: String,
        val versionCode: Long,
        val versionName: String,
        val lib: Double,
        val rating: Int,
        val torrent: Boolean,
        val file: File,
        val sources: List<AnimeCatalogueSource>,
    )

    class Available(
        val pkg: String,
        val name: String,
        val versionCode: Long,
        val versionName: String,
        val lib: Double,
        val rating: Int,
        val apkUrl: String,
        val iconUrl: String,
        val sources: JSONArray,
    )

    private val dir: File get() = File(Host.app.filesDir, "exts").apply { mkdirs() }
    private val repoFile: File get() = File(Host.app.filesDir, "repo-index.json")

    val installed = ConcurrentHashMap<String, Installed>()

    /** Extensions that are on disk but didn't load, and why. */
    val broken = ConcurrentHashMap<String, String>()
    private val byId = ConcurrentHashMap<Long, AnimeCatalogueSource>()
    private val pkgById = ConcurrentHashMap<Long, String>()
    private val loaded = CountDownLatch(1)
    private val loading = AtomicBoolean(false)
    private val installLock = Any()

    /** Runs after the installed extensions load, and after each install or uninstall. */
    @JvmStatic
    @Volatile
    var onChanged: Runnable? = null

    private fun changed() {
        try {
            onChanged?.run()
        } catch (e: RuntimeException) {
            Log.w(TAG, "extension change listener", e)
        }
    }

    @JvmStatic
    fun loadAllAsync() {
        if (loading.compareAndSet(false, true)) thread(name = "ext-load") { loadAll() }
    }

    fun awaitLoaded() {
        loadAllAsync()
        loaded.await(90, TimeUnit.SECONDS)
    }

    fun source(id: Long): AnimeCatalogueSource? {
        awaitLoaded()
        return byId[id]
    }

    /** Without waiting for the extensions to load: false until they have. */
    @JvmStatic
    fun isInstalled(id: String): Boolean = id.toLongOrNull()?.let { byId.containsKey(it) } ?: false

    fun sources(): List<AnimeCatalogueSource> {
        awaitLoaded()
        return byId.values.sortedBy { it.name.lowercase() }
    }

    fun extensionOf(id: Long): Installed? = pkgById[id]?.let { installed[it] }

    private fun loadAll() {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".ext") }.orEmpty()
            for (f in files.sortedBy { it.name }) {
                try {
                    load(f)
                } catch (e: Throwable) {
                    Log.w(TAG, "extension ${f.name} didn't load", e)
                    broken[f.name.substringBefore('@')] = e.message ?: e.javaClass.simpleName
                }
            }
        } finally {
            loaded.countDown()
            changed()
        }
    }

    private fun inspect(file: File): PackageInfo {
        val pm = Host.app.packageManager
        val pi = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
            ?: throw IOException("Not a valid, signed extension file")
        pi.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        if (pi.reqFeatures.orEmpty().none { it.name == FEATURE }) throw IOException("Not an Aniyomi extension")
        val signing = pi.signingInfo ?: throw SecurityException("The extension isn't signed")
        val signer = if (signing.hasMultipleSigners()) null else signing.signingCertificateHistory?.lastOrNull()
        if (signer == null || sha256(signer.toByteArray()) != TRUSTED_SIGNER) {
            throw SecurityException("The extension isn't signed by the Yūzōnō repo")
        }
        return pi
    }

    private fun load(file: File): Installed {
        val pi = inspect(file)
        val meta = pi.applicationInfo?.metaData ?: throw IOException("The extension has no metadata")
        val pkg = pi.packageName
        val versionName = pi.versionName.orEmpty()
        val lib = meta.get("aniyomix.extensionLib")?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
            ?: throw IOException("Unknown extension version")
        if (lib !in SUPPORTED_LIBS) throw IOException("Needs extensions-lib $lib, AniDeck supports 14 to 16")
        val rating = when {
            meta.getInt("aniyomix.contentWarning") >= 2 -> 2
            meta.getInt("aniyomix.contentWarning") == 1 || meta.getInt("tachiyomi.animeextension.nsfw") == 1 -> 1
            else -> 0
        }
        val torrent = meta.getBoolean("aniyomix.torrent") || meta.getInt("tachiyomi.animeextension.torrent") == 1
        val parent = Host::class.java.classLoader!!
        val loader = ChildFirstPathClassLoader(file.absolutePath, null, parent)
        val classes = (meta.getString("tachiyomi.animeextension.class") ?: throw IOException("The extension lists no sources"))
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith(".")) pkg + it else it }
        val sources = classes.flatMap { name ->
            val obj = try {
                Class.forName(name, false, loader).getDeclaredConstructor().newInstance()
            } catch (e: LinkageError) {
                Class.forName(name, false, PathClassLoader(file.absolutePath, null, parent)).getDeclaredConstructor().newInstance()
            }
            when (obj) {
                is AnimeSourceFactory -> obj.createSources()
                is AnimeSource -> listOf(obj)
                else -> throw IOException("Unknown source type ${obj.javaClass.name}")
            }
        }.filterIsInstance<AnimeCatalogueSource>()
        val name = meta.getString("aniyomix.name") ?: sources.firstOrNull()?.name ?: pkg.substringAfterLast('.')
        val ext = Installed(pkg, name, pi.longVersionCode, versionName, lib, rating, torrent, file, sources)
        installed[pkg] = ext
        broken.remove(pkg)
        for (s in sources) {
            byId[s.id] = s
            pkgById[s.id] = pkg
        }
        Log.i(TAG, "loaded extension $name $versionName with ${sources.size} source(s)")
        return ext
    }

    private fun unload(pkg: String) {
        val old = installed.remove(pkg) ?: return
        for (s in old.sources) {
            byId.remove(s.id)
            pkgById.remove(s.id)
        }
    }

    /** Downloads, checks and loads an extension from the repo (also updates it). */
    fun install(pkg: String): Installed {
        awaitLoaded()
        val entry = repo(false).firstOrNull { it.pkg == pkg } ?: repo(true).firstOrNull { it.pkg == pkg }
            ?: throw IOException("$pkg isn't in the repo")
        val tmp = File(Host.app.cacheDir, "ext-download.apk")
        synchronized(installLock) {
            tmp.delete()
            Host.network.client.newCall(GET(entry.apkUrl, cache = CacheControl.FORCE_NETWORK)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            try {
                val pi = inspect(tmp)
                if (pi.packageName != pkg) throw SecurityException("The download is a different extension")
                val current = installed[pkg]
                if (current != null && pi.longVersionCode < current.versionCode) throw IOException("The installed version is newer")
                val target = File(dir, "$pkg@${pi.longVersionCode}.ext")
                target.setWritable(true)
                target.delete()
                tmp.copyTo(target, overwrite = true)
                target.setReadOnly()
                unload(pkg)
                dir.listFiles { f -> f.name.startsWith("$pkg@") && f != target }?.forEach { it.setWritable(true); it.delete() }
                return load(target).also { changed() }
            } finally {
                tmp.delete()
            }
        }
    }

    fun uninstall(pkg: String) {
        synchronized(installLock) {
            unload(pkg)
            broken.remove(pkg)
            dir.listFiles { f -> f.name.startsWith("$pkg@") }?.forEach { it.setWritable(true); it.delete() }
        }
        changed()
    }

    /** The repo's extension list, cached for a few hours. */
    fun repo(force: Boolean): List<Available> {
        val cached = repoFile.takeIf { it.isFile }
        val fresh = cached != null && System.currentTimeMillis() - cached.lastModified() < REPO_MAX_AGE
        if (force || !fresh) {
            try {
                val text = Host.network.client.newCall(GET(REPO_INDEX, cache = CacheControl.FORCE_NETWORK)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body.string()
                }
                parseRepo(text)
                repoFile.writeText(text)
            } catch (e: Exception) {
                if (cached == null) throw IOException("Couldn't reach the extension repo: ${e.message}", e)
                Log.w(TAG, "repo refresh failed, using the saved list", e)
            }
        }
        return parseRepo(repoFile.readText())
    }

    private fun parseRepo(text: String): List<Available> {
        val root = JSONObject(text)
        val key = root.optString("signingKey")
        if (key.isNotEmpty() && key != TRUSTED_SIGNER) throw SecurityException("The repo's signing key changed")
        val list = root.getJSONObject("extensionList").getJSONArray("extensions")
        return (0 until list.length()).map { i ->
            val e = list.getJSONObject(i)
            val res = e.optJSONObject("resources") ?: JSONObject()
            Available(
                pkg = e.getString("packageName"),
                name = e.optString("name"),
                versionCode = e.optString("versionCode").toLongOrNull() ?: 0,
                versionName = e.optString("versionName"),
                lib = e.optString("extensionLib").toDoubleOrNull() ?: 0.0,
                rating = when (e.optString("contentWarning")) {
                    "CONTENT_WARNING_NSFW" -> 2
                    "CONTENT_WARNING_MIXED" -> 1
                    else -> 0
                },
                apkUrl = res.optString("apkUrl"),
                iconUrl = res.optString("iconUrl"),
                sources = e.optJSONArray("sources") ?: JSONArray(),
            )
        }
    }

    fun langOf(pkg: String): String = pkg.substringAfter("animeextension.", "").substringBefore('.')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
