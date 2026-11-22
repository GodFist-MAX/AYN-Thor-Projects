// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("unused")

package eu.kanade.tachiyomi.animesource

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

interface AnimeSource {
    val id: Long

    val name: String

    suspend fun getAnimeDetails(anime: SAnime): SAnime

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>

    suspend fun getSeasonList(anime: SAnime): List<SAnime>

    suspend fun getHosterList(episode: SEpisode): List<Hoster>

    suspend fun getVideoList(hoster: Hoster): List<Video>

    suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = SAnimeEpisodeUpdate(
        if (fetchDetails) getAnimeDetails(anime) else anime,
        if (fetchEpisodes) getEpisodeList(anime) else episodes,
    )

    suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = SAnimeSeasonUpdate(
        if (fetchDetails) getAnimeDetails(anime) else anime,
        if (fetchSeasons) getSeasonList(anime) else seasons,
    )

    val supportsRelatedAnime: Boolean get() = false

    suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
    }
}

interface AnimeCatalogueSource : AnimeSource {
    val lang: String

    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimesPage

    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage

    suspend fun getLatestUpdates(page: Int): AnimesPage

    fun getFilterList(): AnimeFilterList

    val supportsRelatedAnimes: Boolean get() = false

    val disableRelatedAnimesBySearch: Boolean get() = false

    val disableRelatedAnimes: Boolean get() = false

    suspend fun getRelatedAnimeListByExtension(
        anime: SAnime,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
    }

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException("Unsupported!")

    fun String.stripKeywordForRelatedAnimes(): List<String> = split(' ').filter { it.isNotBlank() }

    suspend fun getRelatedAnimeListBySearch(
        anime: SAnime,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
    }
}

interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

interface ConfigurableAnimeSource {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

interface UnmeteredSource
