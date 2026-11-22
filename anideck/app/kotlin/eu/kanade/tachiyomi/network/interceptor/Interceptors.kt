// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("unused")

package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

/** Solves Cloudflare's browser check by loading the page in an off-screen WebView until it sets cf_clearance. */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {
    private val main = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) return response
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url).firstOrNull { it.name == "cf_clearance" }
            resolveWithWebView(request, oldCookie)
            return chain.proceed(request)
        } catch (e: CloudflareBypassException) {
            throw IOException("Couldn't get past Cloudflare's check for ${request.url.host}", e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        val latch = CountDownLatch(1)
        var webview: WebView? = null
        var challengeFound = false
        var bypassed = false
        val origRequestUrl = originalRequest.url.toString()
        val headers = originalRequest.headers
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy({ it.first }) { it.second }
            .mapValues { it.value.firstOrNull().orEmpty() }

        main.post {
            try {
                WebSettings.getDefaultUserAgent(context)
            } catch (_: Exception) {
            }
            webview = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = originalRequest.header("User-Agent") ?: defaultUserAgentProvider()
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val cleared = cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                        if (cleared) {
                            bypassed = true
                            latch.countDown()
                        }
                        if (url == origRequestUrl && !challengeFound) latch.countDown()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame) return
                        if (error.errorCode in ERROR_CODES) challengeFound = true else latch.countDown()
                    }
                }
                loadUrl(origRequestUrl, headers)
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        main.post {
            webview?.run {
                stopLoading()
                destroy()
            }
        }
        if (!bypassed) throw CloudflareBypassException()
    }

    private class CloudflareBypassException : Exception()

    private companion object {
        val ERROR_CODES = listOf(403, 503)
        val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        val COOKIE_NAMES = listOf("cf_clearance")
        val UNSAFE_HEADERS = listOf("content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie")

        fun isRequestHeaderSafe(name: String, value: String): Boolean {
            val n = name.lowercase(Locale.ENGLISH)
            if (n in UNSAFE_HEADERS || n.startsWith("proxy-")) return false
            return !(n == "connection" && value.lowercase(Locale.ENGLISH) == "upgrade")
        }
    }
}

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimit(permits: Int, period: Long = 1, unit: TimeUnit = TimeUnit.SECONDS) =
    addInterceptor(RateLimitInterceptor(null, permits, period.toDuration(unit.toDurationUnit())))

fun OkHttpClient.Builder.rateLimit(permits: Int, period: Duration = 1.seconds) =
    addInterceptor(RateLimitInterceptor(null, permits, period))

fun OkHttpClient.Builder.rateLimitHost(url: okhttp3.HttpUrl, permits: Int, period: Duration = 1.seconds) =
    addInterceptor(RateLimitInterceptor(url.host, permits, period))

fun OkHttpClient.Builder.rateLimitHost(url: String, permits: Int, period: Duration = 1.seconds) =
    addInterceptor(RateLimitInterceptor(url.toHttpUrl().host, permits, period))

internal class RateLimitInterceptor(private val host: String?, private val permits: Int, period: Duration) : Interceptor {
    private val requestQueue = ArrayDeque<Long>(permits)
    private val rateLimitMillis = period.inWholeMilliseconds
    private val fairLock = Semaphore(1, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        val request = chain.request()
        if (host != null && host != request.url.host) return chain.proceed(request)
        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }
        val timestamp: Long
        try {
            synchronized(requestQueue) {
                while (requestQueue.size >= permits) {
                    val periodStart = SystemClock.elapsedRealtime() - rateLimitMillis
                    var removed = false
                    while (requestQueue.isNotEmpty() && requestQueue.first <= periodStart) {
                        requestQueue.removeFirst()
                        removed = true
                    }
                    if (call.isCanceled()) throw IOException("Canceled")
                    if (removed) break
                    try {
                        (requestQueue as Object).wait(requestQueue.first - periodStart)
                    } catch (_: InterruptedException) {
                    }
                }
                timestamp = SystemClock.elapsedRealtime()
                requestQueue.addLast(timestamp)
            }
        } finally {
            fairLock.release()
        }
        val response = chain.proceed(request)
        if (response.networkResponse == null) {
            synchronized(requestQueue) {
                if (requestQueue.isNotEmpty() && timestamp >= requestQueue.first) {
                    requestQueue.removeFirstOccurrence(timestamp)
                    (requestQueue as Object).notifyAll()
                }
            }
        }
        return response
    }
}
