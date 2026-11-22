package dev.anideck.app.ext

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * What the rest of AniDeck asks of the extensions, as JSON in and out: browsing, search, show
 * details, episodes, the servers and videos for an episode, and each source's settings.
 */
object Catalog {
    private const val TAG = "AniDeck"
    private const val CALL_TIMEOUT = 150_000L
    private const val HOSTER_TIMEOUT = 45_000L
    private val main = Handler(Looper.getMainLooper())
    private val nextKey = AtomicLong()

    private class HosterEntry(val key: String, val sourceId: Long, val hoster: Hoster) {
        @Volatile var videos: List<VideoEntry>? = null
        @Volatile var error: String? = null
    }

    private class VideoEntry(val key: String, val sourceId: Long, @Volatile var video: Video)

    private val hosterCache = lru<HosterEntry>(400)
    private val videoCache = lru<VideoEntry>(4000)
    private val screens = HashMap<Long, PreferenceScreen>()

    /** Runs one request; the answer is {"ok": ...} or {"error": "..."}. */
    @JvmStatic
    fun call(method: String, args: String): String {
        val out = JSONObject()
        try {
            val a = if (args.isBlank()) JSONObject() else JSONObject(args)
            out.put("ok", dispatch(method, a))
        } catch (e: Throwable) {
            if (e !is IOException && e !is UnsupportedOperationException) Log.w(TAG, "$method failed", e)
            out.put("error", describe(e))
        }
        return out.toString()
    }

    private fun dispatch(method: String, a: JSONObject): Any = when (method) {
        "sources" -> sourcesJson()
        "repo" -> repoJson(a.optBoolean("force"))
        "install" -> Extensions.install(a.getString("pkg")).let { repoJson(false) }
        "uninstall" -> Extensions.uninstall(a.getString("pkg")).let { repoJson(false) }
        "popular" -> page(src(a)) { getPopularAnime(a.optInt("page", 1)) }
        "latest" -> page(src(a)) { getLatestUpdates(a.optInt("page", 1)) }
        "search" -> src(a).let { s ->
            val filters = s.getFilterList()
            a.optJSONArray("filters")?.let { applyFilters(filters, it) }
            page(s) { getSearchAnime(a.optInt("page", 1), a.optString("query"), filters) }
        }
        "filters" -> JSONArray(src(a).getFilterList().map(::filterJson))
        "details" -> src(a).let { s -> io { animeJson(s.getAnimeDetails(anime(a.getJSONObject("anime")))) } }
        "episodes" -> src(a).let { s -> io { JSONArray(s.getEpisodeList(anime(a.getJSONObject("anime"))).map(::episodeJson)) } }
        "seasons" -> src(a).let { s -> io { JSONArray(s.getSeasonList(anime(a.getJSONObject("anime"))).map(::animeJson)) } }
        "hosters" -> src(a).let { s -> io { JSONArray(hosters(s, episode(a.getJSONObject("episode"))).map(::hosterJson)) } }
        "videos" -> hosterVideos(a.getString("hoster"))
        "resolve" -> resolve(a.getString("video"))
        "best" -> src(a).let { s -> io { best(s, episode(a.getJSONObject("episode"))) } }
        "prefs" -> prefsJson(src(a), rebuild = true)
        "setPref" -> setPref(src(a), a.getString("key"), a.opt("value"))
        else -> throw IllegalArgumentException("Unknown request $method")
    }

    // ---- sources and extensions ----

    private fun src(a: JSONObject): AnimeCatalogueSource {
        val id = a.getString("source").toLongOrNull() ?: throw IOException("Bad source id")
        return Extensions.source(id) ?: throw IOException("That source isn't installed")
    }

    private fun sourcesJson(): JSONArray = JSONArray(
        Extensions.sources().map { s ->
            val ext = Extensions.extensionOf(s.id)
            JSONObject()
                .put("id", s.id.toString())
                .put("name", s.name)
                .put("lang", s.lang)
                .put("latest", s.supportsLatest)
                .put("settings", s is ConfigurableAnimeSource)
                .put("baseUrl", (s as? AnimeHttpSource)?.baseUrl ?: "")
                .put("pkg", ext?.pkg ?: "")
                .put("ext", ext?.name ?: "")
                .put("rating", ext?.rating ?: 0)
                .put("torrent", ext?.torrent ?: false)
        },
    )

    private fun repoJson(force: Boolean): JSONObject {
        Extensions.awaitLoaded()
        val out = JSONArray()
        val available = try {
            Extensions.repo(force)
        } catch (e: IOException) {
            if (Extensions.installed.isEmpty()) throw e
            emptyList()
        }
        val seen = HashSet<String>()
        for (e in available) {
            seen += e.pkg
            val inst = Extensions.installed[e.pkg]
            out.put(
                JSONObject()
                    .put("pkg", e.pkg)
                    .put("name", e.name)
                    .put("lang", Extensions.langOf(e.pkg))
                    .put("version", e.versionName)
                    .put("lib", e.lib)
                    .put("rating", e.rating)
                    .put("icon", e.iconUrl)
                    .put("installed", inst?.versionName ?: JSONObject.NULL)
                    .put("update", inst != null && inst.versionCode < e.versionCode)
                    .put("broken", Extensions.broken[e.pkg] ?: JSONObject.NULL)
                    .put("sources", JSONArray((0 until e.sources.length()).map { i -> e.sources.getJSONObject(i).optString("name") }.distinct())),
            )
        }
        for (inst in Extensions.installed.values) {
            if (inst.pkg in seen) continue
            out.put(
                JSONObject()
                    .put("pkg", inst.pkg).put("name", inst.name).put("lang", Extensions.langOf(inst.pkg))
                    .put("version", inst.versionName).put("lib", inst.lib).put("rating", inst.rating).put("icon", "")
                    .put("installed", inst.versionName).put("update", false).put("broken", JSONObject.NULL)
                    .put("sources", JSONArray(inst.sources.map { it.name }.distinct())),
            )
        }
        return JSONObject().put("extensions", out).put("offline", available.isEmpty())
    }

    // ---- browsing ----

    private fun page(s: AnimeCatalogueSource, fetch: suspend AnimeCatalogueSource.() -> AnimesPage): JSONObject = io {
        val p = s.fetch()
        JSONObject().put("animes", JSONArray(p.animes.map(::animeJson))).put("next", p.hasNextPage)
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(CALL_TIMEOUT) { block() } }

    @JvmStatic
    fun animeJson(a: SAnime): JSONObject = JSONObject()
        .put("url", safe { a.url } ?: "")
        .put("title", safe { a.title } ?: "")
        .put("artist", a.artist ?: JSONObject.NULL)
        .put("author", a.author ?: JSONObject.NULL)
        .put("description", a.description ?: JSONObject.NULL)
        .put("genre", a.genre ?: JSONObject.NULL)
        .put("status", a.status)
        .put("thumbnail_url", a.thumbnail_url ?: JSONObject.NULL)
        .put("background_url", safe { a.background_url } ?: JSONObject.NULL)
        .put("fetch_type", safe { a.fetch_type.name } ?: FetchType.Episodes.name)
        .put("season_number", safe { a.season_number } ?: -1.0)
        .put("initialized", a.initialized)

    @JvmStatic
    fun anime(j: JSONObject): SAnime = SAnime.create().apply {
        url = j.getString("url")
        title = j.optString("title")
        artist = j.str("artist")
        author = j.str("author")
        description = j.str("description")
        genre = j.str("genre")
        status = j.optInt("status")
        thumbnail_url = j.str("thumbnail_url")
        background_url = j.str("background_url")
        fetch_type = if (j.optString("fetch_type") == FetchType.Seasons.name) FetchType.Seasons else FetchType.Episodes
        season_number = j.optDouble("season_number", -1.0)
        initialized = j.optBoolean("initialized")
    }

    @JvmStatic
    fun episodeJson(e: SEpisode): JSONObject = JSONObject()
        .put("url", safe { e.url } ?: "")
        .put("name", safe { e.name } ?: "")
        .put("date_upload", e.date_upload)
        .put("episode_number", e.episode_number.toDouble())
        .put("fillermark", safe { e.fillermark } ?: false)
        .put("scanlator", e.scanlator ?: JSONObject.NULL)
        .put("summary", safe { e.summary } ?: JSONObject.NULL)
        .put("preview_url", safe { e.preview_url } ?: JSONObject.NULL)

    @JvmStatic
    fun episode(j: JSONObject): SEpisode = SEpisode.create().apply {
        url = j.getString("url")
        name = j.optString("name")
        date_upload = j.optLong("date_upload")
        episode_number = j.optDouble("episode_number", -1.0).toFloat()
        fillermark = j.optBoolean("fillermark")
        scanlator = j.str("scanlator")
        summary = j.str("summary")
        preview_url = j.str("preview_url")
    }

    // ---- filters ----

    private fun filterJson(f: AnimeFilter<*>): JSONObject {
        val j = JSONObject().put("name", f.name)
        when (f) {
            is AnimeFilter.Header -> j.put("type", "header")
            is AnimeFilter.Separator -> j.put("type", "separator")
            is AnimeFilter.Select<*> -> j.put("type", "select").put("values", JSONArray(f.values.map { it.toString() })).put("state", f.state)
            is AnimeFilter.Text -> j.put("type", "text").put("state", f.state)
            is AnimeFilter.CheckBox -> j.put("type", "check").put("state", f.state)
            is AnimeFilter.TriState -> j.put("type", "tri").put("state", f.state)
            is AnimeFilter.Group<*> -> j.put("type", "group").put(
                "items",
                JSONArray(f.state.map { if (it is AnimeFilter<*>) filterJson(it) else JSONObject().put("type", "other").put("name", it.toString()) }),
            )
            is AnimeFilter.Sort -> j.put("type", "sort").put("values", JSONArray(f.values.toList())).put(
                "state",
                f.state?.let { JSONObject().put("index", it.index).put("asc", it.ascending) } ?: JSONObject.NULL,
            )
        }
        return j
    }

    private fun applyFilters(list: List<*>, states: JSONArray) {
        for (i in list.indices) {
            val f = list[i] as? AnimeFilter<*> ?: continue
            val s = states.optJSONObject(i) ?: continue
            if (s.optString("name") != f.name) continue
            when (f) {
                is AnimeFilter.Select<*> -> f.state = s.optInt("state", f.state).coerceIn(0, (f.values.size - 1).coerceAtLeast(0))
                is AnimeFilter.Text -> f.state = s.optString("state", f.state)
                is AnimeFilter.CheckBox -> f.state = s.optBoolean("state", f.state)
                is AnimeFilter.TriState -> f.state = s.optInt("state", f.state)
                is AnimeFilter.Group<*> -> s.optJSONArray("items")?.let { applyFilters(f.state, it) }
                is AnimeFilter.Sort -> {
                    val st = s.optJSONObject("state")
                    f.state = st?.let { AnimeFilter.Sort.Selection(it.optInt("index"), it.optBoolean("asc")) }
                }
                else -> {}
            }
        }
    }

    // ---- servers and videos ----

    /** Whether the source lists servers itself; older ones only list videos per episode. */
    private fun usesHosters(s: AnimeHttpSource): Boolean {
        var c: Class<*>? = s.javaClass
        while (c != null && c != AnimeHttpSource::class.java && c != ParsedAnimeHttpSource::class.java) {
            if (c.declaredMethods.any { it.name == "getHosterList" || it.name == "hosterListRequest" || it.name == "hosterListParse" }) return true
            c = c.superclass
        }
        return false
    }

    private suspend fun hosters(s: AnimeCatalogueSource, ep: SEpisode): List<HosterEntry> {
        val http = s as? AnimeHttpSource
        val list = if (http != null && !usesHosters(http)) {
            with(http) { http.getVideoList(ep).sortVideos() }.toHosterList()
        } else {
            s.getHosterList(ep).let { l -> if (http != null) with(http) { l.sortHosters() } else l }
        }
        val entries = list.map { h -> HosterEntry("h" + nextKey.incrementAndGet(), s.id, h).also { synchronized(hosterCache) { hosterCache[it.key] = it } } }
        coroutineScope {
            entries.filter { !it.hoster.lazy || it.hoster.videoList != null }.map { e -> async { loadVideos(s, e) } }.awaitAll()
        }
        return entries
    }

    private suspend fun loadVideos(s: AnimeCatalogueSource, e: HosterEntry) {
        try {
            val http = s as? AnimeHttpSource
            val found = withTimeoutOrNull(HOSTER_TIMEOUT) {
                val raw = e.hoster.videoList ?: s.getVideoList(e.hoster)
                val urls = raw.map { v -> if (v.videoUrl == "null" && http != null) v.copy(videoUrl = http.getVideoUrl(v)) else v }
                if (http != null) with(http) { urls.sortVideos() } else urls
            } ?: throw SocketTimeoutException("The server took too long")
            e.videos = found.map { v -> VideoEntry("v" + nextKey.incrementAndGet(), s.id, v).also { synchronized(videoCache) { videoCache[it.key] = it } } }
            e.error = null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            e.error = describe(t)
        }
    }

    private fun hosterJson(e: HosterEntry): JSONObject = JSONObject()
        .put("key", e.key)
        .put("name", if (e.hoster.hosterName == Hoster.NO_HOSTER_LIST) "" else e.hoster.hosterName)
        .put("lazy", e.hoster.lazy && e.videos == null)
        .put("error", e.error ?: JSONObject.NULL)
        .put("videos", JSONArray(e.videos.orEmpty().map(::videoJson)))

    private fun videoJson(v: VideoEntry): JSONObject = JSONObject()
        .put("key", v.key)
        .put("title", v.video.videoTitle)
        .put("resolution", v.video.resolution ?: JSONObject.NULL)
        .put("preferred", v.video.preferred)

    private fun hosterVideos(key: String): JSONObject {
        val e = synchronized(hosterCache) { hosterCache[key] } ?: throw IOException("That server list expired, open the episode again")
        val s = Extensions.source(e.sourceId) ?: throw IOException("That source isn't installed")
        io { loadVideos(s, e) }
        return hosterJson(e)
    }

    private suspend fun resolveEntry(v: VideoEntry): Video? {
        val video = v.video
        if (video.initialized) return video
        val s = Extensions.source(v.sourceId) as? AnimeHttpSource ?: return video.copy(initialized = true)
        val resolved = withTimeoutOrNull(HOSTER_TIMEOUT) { s.resolveVideo(video) }?.copy(initialized = true)
        if (resolved != null) v.video = resolved
        return resolved
    }

    private fun resolve(key: String): JSONObject {
        val v = synchronized(videoCache) { videoCache[key] } ?: throw IOException("That video link expired, open the episode again")
        val r = io { resolveEntry(v) }
        if (r == null || r.videoUrl.isEmpty() || r.videoUrl == "null") throw IOException("This server didn't give a playable video")
        return streamJson(v, r)
    }

    private fun streamJson(v: VideoEntry, r: Video): JSONObject {
        val headers = JSONObject()
        val h = r.headers ?: (Extensions.source(v.sourceId) as? AnimeHttpSource)?.headers
        h?.forEach { (name, value) -> headers.put(name, value) }
        return JSONObject()
            .put("key", v.key)
            .put("url", r.videoUrl)
            .put("title", r.videoTitle)
            .put("resolution", r.resolution ?: JSONObject.NULL)
            .put("headers", headers)
            .put("subtitles", JSONArray(r.subtitleTracks.map { JSONObject().put("url", it.url).put("lang", it.lang) }))
            .put("audios", JSONArray(r.audioTracks.map { JSONObject().put("url", it.url).put("lang", it.lang) }))
            .put("timestamps", JSONArray(r.timestamps.map { JSONObject().put("start", it.start).put("end", it.end).put("name", it.name).put("type", it.type.name) }))
    }

    /** Every server for the episode plus the video Aniyomi would pick: the source's preferred one, else the first that resolves. */
    private suspend fun best(s: AnimeCatalogueSource, ep: SEpisode): JSONObject {
        val entries = hosters(s, ep)
        val order = ArrayList<Pair<Int, Int>>()
        entries.forEachIndexed { hi, e -> e.videos.orEmpty().forEachIndexed { vi, v -> if (v.video.preferred) order += hi to vi } }
        entries.forEachIndexed { hi, e -> e.videos.orEmpty().forEachIndexed { vi, v -> if (!v.video.preferred && v.video.videoUrl.isNotEmpty()) order += hi to vi } }
        var picked: Pair<Int, Int>? = null
        var stream: JSONObject? = null
        for ((hi, vi) in order) {
            val v = entries[hi].videos!![vi]
            val r = try {
                resolveEntry(v)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
                null
            }
            if (r != null && r.videoUrl.isNotEmpty() && r.videoUrl != "null") {
                picked = hi to vi
                stream = streamJson(v, r)
                break
            }
        }
        if (stream == null) {
            for ((hi, e) in entries.withIndex()) {
                if (e.videos != null) continue
                loadVideos(s, e)
                for ((vi, v) in e.videos.orEmpty().withIndex()) {
                    val r = runCatching { resolveEntry(v) }.getOrNull() ?: continue
                    if (r.videoUrl.isEmpty() || r.videoUrl == "null") continue
                    picked = hi to vi
                    stream = streamJson(v, r)
                    break
                }
                if (stream != null) break
            }
        }
        val out = JSONObject().put("hosters", JSONArray(entries.map(::hosterJson)))
        if (stream == null) {
            val why = entries.mapNotNull { it.error }.firstOrNull()
            out.put("error", if (entries.isEmpty()) "This episode has no servers" else why ?: "None of the servers gave a playable video")
        } else {
            out.put("hoster", picked!!.first).put("video", picked.second).put("stream", stream)
        }
        return out
    }

    // ---- source settings ----

    private fun prefsJson(s: AnimeCatalogueSource, rebuild: Boolean): JSONObject {
        if (s !is ConfigurableAnimeSource) return JSONObject().put("items", JSONArray())
        val screen = synchronized(screens) {
            if (rebuild || screens[s.id] == null) {
                val app = Host.app
                val built = onMain { PreferenceScreen(app, app.getSharedPreferences("source_${s.id}", 0)).also { s.setupPreferenceScreen(it) } }
                screens[s.id] = built
            }
            screens.getValue(s.id)
        }
        return JSONObject().put("items", groupJson(screen))
    }

    private fun groupJson(g: PreferenceGroup): JSONArray {
        val out = JSONArray()
        for (i in 0 until g.preferenceCount) {
            val p = g.getPreference(i)
            p.reload()
            val j = JSONObject()
                .put("key", p.key ?: JSONObject.NULL)
                .put("title", p.title?.toString() ?: "")
                .put("summary", p.summary?.toString() ?: "")
                .put("enabled", p.isEnabled)
                .put("visible", p.isVisible)
            when (p) {
                is ListPreference -> j.put("type", "list").put("entries", chars(p.entries)).put("values", chars(p.entryValues)).put("value", p.value ?: JSONObject.NULL)
                is MultiSelectListPreference -> j.put("type", "multi").put("entries", chars(p.entries)).put("values", chars(p.entryValues)).put("value", JSONArray(p.values.toList()))
                is EditTextPreference -> j.put("type", "text").put("value", p.text ?: "").put("dialogTitle", p.dialogTitle?.toString() ?: "").put("dialogMessage", p.dialogMessage?.toString() ?: "")
                is TwoStatePreference -> j.put("type", "switch").put("value", p.isChecked)
                is PreferenceGroup -> j.put("type", "group").put("items", groupJson(p))
                else -> j.put("type", "info")
            }
            out.put(j)
        }
        return out
    }

    private fun setPref(s: AnimeCatalogueSource, key: String, value: Any?): JSONObject {
        val screen = synchronized(screens) { screens[s.id] } ?: run {
            prefsJson(s, rebuild = true)
            synchronized(screens) { screens.getValue(s.id) }
        }
        val p = screen.findPreference<Preference>(key) ?: throw IOException("That setting is gone")
        val v: Any? = when (p) {
            is MultiSelectListPreference -> (value as? JSONArray)?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: emptySet<String>()
            is TwoStatePreference -> value == true || value == "true"
            else -> value?.takeIf { it != JSONObject.NULL }?.toString()
        }
        val accepted = onMain { p.choose(v) }
        return prefsJson(s, rebuild = false).put("accepted", accepted)
    }

    private fun chars(a: Array<CharSequence>?): JSONArray = JSONArray(a.orEmpty().map { it.toString() })

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(20, TimeUnit.SECONDS)) throw IOException("The extension's settings didn't answer")
        return result!!.getOrThrow()
    }

    // ---- helpers ----

    @JvmStatic
    fun describe(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t && (t is java.lang.reflect.InvocationTargetException || (t is IOException && t.message == t.cause?.toString()))) t = t.cause!!
        // Extensions often rethrow network failures as a plain exception that keeps only the message.
        val chain = generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.take(10).toList()
        val lookup = chain.firstOrNull { it is UnknownHostException || it.message.orEmpty().startsWith("Unable to resolve host") }
        val connect = chain.firstOrNull { it is java.net.ConnectException || it is java.net.NoRouteToHostException || it.message.orEmpty().startsWith("Failed to connect to") }
        if ((lookup != null || connect != null) && offline()) return "No internet connection"
        if (lookup != null) return "Can't find ${hostIn(lookup.message) ?: "the site"}. It may be down or blocked on this network"
        if (connect != null) return "Can't connect to ${hostIn(connect.message) ?: "the site"}"
        return when (t) {
            is HttpException -> "The site answered with error ${t.code}"
            is SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> if (offline()) "No internet connection" else "The site took too long to answer"
            is SSLException -> "Secure connection to the site failed"
            is UnsupportedOperationException -> t.message?.takeIf { it.isNotBlank() } ?: "This source doesn't support that"
            is SecurityException -> t.message ?: "Blocked for security"
            else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        }
    }

    /** "anizone.to" from `Unable to resolve host "anizone.to": …` or `Failed to connect to anizone.to/1.2.3.4:443`. */
    private fun hostIn(message: String?): String? {
        val m = message ?: return null
        return Regex("\"([^\"]+)\"").find(m)?.groupValues?.get(1) ?: Regex("to ([\\w-]+(\\.[\\w-]+)+)").find(m)?.groupValues?.get(1)
    }

    private fun offline(): Boolean = try {
        val cm = Host.app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        false
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun <V> lru(max: Int) = object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }
}
