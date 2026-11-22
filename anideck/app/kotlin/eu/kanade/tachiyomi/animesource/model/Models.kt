// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("PropertyName", "unused")

package eu.kanade.tachiyomi.animesource.model

import okhttp3.Headers

interface SAnime {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var background_url: String?
    var update_strategy: AnimeUpdateStrategy
    var fetch_type: FetchType
    var season_number: Double
    var initialized: Boolean

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
        const val UPCOMING = 7

        fun create(): SAnime = SAnimeImpl()
    }
}

class SAnimeImpl : SAnime {
    override lateinit var url: String
    override lateinit var title: String
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = 0
    override var thumbnail_url: String? = null
    override var background_url: String? = null
    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE
    override var fetch_type: FetchType = FetchType.Episodes
    override var season_number: Double = -1.0
    override var initialized: Boolean = false
}

interface SEpisode {
    var url: String
    var name: String
    var date_upload: Long
    var episode_number: Float
    var fillermark: Boolean
    var scanlator: String?
    var summary: String?
    var preview_url: String?

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}

class SEpisodeImpl : SEpisode {
    override lateinit var url: String
    override lateinit var name: String
    override var date_upload: Long = 0
    override var episode_number: Float = -1f
    override var fillermark: Boolean = false
    override var scanlator: String? = null
    override var summary: String? = null
    override var preview_url: String? = null
}

enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

enum class FetchType {
    Seasons,
    Episodes,
}

data class AnimesPage(val animes: List<SAnime>, val hasNextPage: Boolean)

class AnimeRelation(val name: String, val animes: List<SAnime>)

class SAnimeEpisodeUpdate(val anime: SAnime, val episodes: List<SEpisode>)

class SAnimeSeasonUpdate(val anime: SAnime, val seasons: List<SAnime>)

open class ThumbnailInfo(val tileInfo: List<TileInfo>, val imageTileUrls: List<String>)

data class TileInfo(val imageIndex: Int, val timeMs: Long, val x: Int, val y: Int, val width: Int, val height: Int)

class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {
    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> = listOf(Hoster(hosterUrl = "", hosterName = NO_HOSTER_LIST, videoList = this))
    }
}

data class Track(val url: String, val lang: String)

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

data class TimeStamp(val start: Double, val end: Double, val name: String, val type: ChapterType = ChapterType.Other)

data class Video(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {
    /** The page the video link is read from, for videos made with the old constructor. */
    var url: String = ""
        private set

    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    @Deprecated("Use the new Video constructor")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoTitle = quality,
        videoUrl = videoUrl ?: "null",
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        this.url = url
    }
}
