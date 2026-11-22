// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("DEPRECATION", "unused", "MemberVisibilityCanBePrivate")

package eu.kanade.tachiyomi.animesource.online

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

abstract class AnimeHttpSource : AnimeCatalogueSource {
    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        client.newCall(popularAnimeRequest(page)).awaitSuccess().use { popularAnimeParse(it) }

    protected open fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess().use { searchAnimeParse(it) }

    protected open fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException()

    protected open fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        client.newCall(latestUpdatesRequest(page)).awaitSuccess().use { latestUpdatesParse(it) }

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        client.newCall(animeDetailsRequest(anime)).awaitSuccess().use { animeDetailsParse(it).apply { initialized = true } }

    open fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> =
        client.newCall(relatedAnimeListRequest(anime)).awaitSuccess().use { relatedAnimeListParse(it) }

    protected open fun relatedAnimeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    protected open fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        client.newCall(episodeListRequest(anime)).awaitSuccess().use { episodeListParse(it) }

    protected open fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    protected open fun episodeVideoParse(response: Response): SEpisode = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> =
        client.newCall(seasonListRequest(anime)).awaitSuccess().use { seasonListParse(it) }

    protected open fun seasonListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        client.newCall(hosterListRequest(episode)).awaitSuccess().use { hosterListParse(it) }

    protected open fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        client.newCall(videoListRequest(hoster)).awaitSuccess().use { videoListParse(it, hoster) }

    protected open fun videoListRequest(hoster: Hoster): Request = GET(hoster.hosterUrl, headers)

    protected open fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    /** The pre-hoster way to list an episode's videos (extensions-lib 14 and older). */
    open suspend fun getVideoList(episode: SEpisode): List<Video> =
        client.newCall(videoListRequest(episode)).awaitSuccess().use { videoListParse(it) }

    protected open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    open suspend fun resolveVideo(video: Video): Video? = video

    open suspend fun getVideoThumbnails(video: Video): ThumbnailInfo? = null

    open suspend fun getImageTile(url: String): Bitmap? =
        client.newCall(GET(url, headers)).execute().use { r -> r.body.byteStream().use { BitmapFactory.decodeStream(it) } }

    open fun List<Hoster>.sortHosters(): List<Hoster> = this

    open fun List<Video>.sortVideos(): List<Video> = sort()

    protected open fun List<Video>.sort(): List<Video> = this

    open suspend fun getVideoUrl(video: Video): String =
        client.newCall(videoUrlRequest(video)).awaitSuccess().use { videoUrlParse(it) }

    protected open fun videoUrlRequest(video: Video): Request = GET(video.url, headers)

    protected open fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig.replace(" ", "%20"))
        var out = uri.path
        if (uri.query != null) out += "?" + uri.query
        if (uri.fragment != null) out += "#" + uri.fragment
        out
    } catch (_: URISyntaxException) {
        orig
    }

    open fun getAnimeUrl(anime: SAnime): String = animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) {}

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}

abstract class ParsedAnimeHttpSource : AnimeHttpSource() {
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = popularAnimeNextPageSelector()?.let { document.select(it).first() } != null
        return AnimesPage(animes, hasNextPage)
    }

    protected open fun popularAnimeSelector(): String = throw UnsupportedOperationException()

    protected open fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    protected open fun popularAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
        val hasNextPage = searchAnimeNextPageSelector()?.let { document.select(it).first() } != null
        return AnimesPage(animes, hasNextPage)
    }

    protected open fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    protected open fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    protected open fun searchAnimeNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.select(it).first() } != null
        return AnimesPage(animes, hasNextPage)
    }

    protected open fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    protected open fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    protected open fun latestUpdatesNextPageSelector(): String? = null

    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.asJsoup())

    protected open fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override fun relatedAnimeListParse(response: Response): List<SAnime> =
        response.asJsoup().select(relatedAnimeListSelector()).map { relatedAnimeFromElement(it) }

    protected open fun relatedAnimeListSelector(): String = throw UnsupportedOperationException()

    protected open fun relatedAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup().select(episodeListSelector()).map { episodeFromElement(it) }

    protected open fun episodeListSelector(): String = throw UnsupportedOperationException()

    protected open fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> =
        response.asJsoup().select(seasonListSelector()).map { seasonFromElement(it) }

    protected open fun seasonListSelector(): String = throw UnsupportedOperationException()

    protected open fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> =
        response.asJsoup().select(hosterListSelector()).map { hosterFromElement(it) }

    protected open fun hosterListSelector(): String = throw UnsupportedOperationException()

    protected open fun hosterFromElement(element: Element): Hoster = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> =
        response.asJsoup().select(videoListSelector()).map { videoFromElement(it) }

    protected open fun videoListSelector(): String = throw UnsupportedOperationException()

    protected open fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(response: Response): String = videoUrlParse(response.asJsoup())

    protected open fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}

interface ResolvableAnimeSource : AnimeSource {
    fun getUriType(uri: String): UriType

    suspend fun getAnime(uri: String): SAnime?

    suspend fun getEpisode(uri: String): SEpisode?
}

sealed interface UriType {
    data object Anime : UriType

    data object Episode : UriType

    data object Unknown : UriType
}
