// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("unused")

package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo

/** AniDeck has no torrent client, so torrent sources can list episodes but not play them. */
object TorrentUtils {
    fun getTorrentInfo(url: String, title: String): TorrentInfo = throw UnsupportedOperationException("AniDeck can't play torrents")
}
