// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("unused")

package eu.kanade.tachiyomi.torrentutils.model

data class TorrentFile(
    val path: String,
    val indexFile: Int,
    val size: Long,
    private val torrentHash: String,
    private val trackers: List<String> = emptyList(),
) {
    fun toMagnetURI(): String {
        val tr = trackers.joinToString("") { "&tr=$it" }
        return "magnet:?xt=urn:btih:$torrentHash$tr&index=$indexFile"
    }
}

data class TorrentInfo(
    val title: String,
    val files: List<TorrentFile>,
    val hash: String,
    val size: Long,
    val trackers: List<String> = emptyList(),
) {
    fun setTrackers(trackers: List<String>): TorrentInfo = TorrentInfo(title, files, hash, size, trackers)
}

class DeadTorrentException : Exception()
