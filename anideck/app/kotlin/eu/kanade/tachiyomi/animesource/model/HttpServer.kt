// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
package eu.kanade.tachiyomi.animesource.model

import android.util.Log
import fi.iki.elonen.NanoHTTPD

/** A local server some extensions run to hand the player streams they rewrite on the fly. */
open class HttpServer : NanoHTTPD(0) {
    val url: String
        get() = "http://localhost:$listeningPort"

    @Volatile
    private var isRunning = false

    fun isRunning(): Boolean = isRunning

    override fun start() {
        try {
            super.start()
            isRunning = true
        } catch (e: Exception) {
            Log.d("AniDeck", "an extension's http server didn't start", e)
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    companion object {
        const val PLACEHOLDER_URL = "http://localhost:1"
    }
}
