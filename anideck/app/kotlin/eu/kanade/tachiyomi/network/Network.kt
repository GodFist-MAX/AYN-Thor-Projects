// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("FunctionName", "unused")

package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.CookieManager
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkHelper(private val context: Context) {
    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .cache(Cache(File(context.cacheDir, "network_cache"), 5L * 1024 * 1024))
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .addInterceptor(CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider))
        .build()

    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider(): String = userAgent

    companion object {
        @Volatile
        var userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    }
}

class AndroidCookieJar : CookieJar {
    private val manager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        cookies.forEach { manager.setCookie(urlString, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

    fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager.getCookie(url.toString())
        return if (!cookies.isNullOrEmpty()) cookies.split(";").mapNotNull { Cookie.parse(url, it) } else emptyList()
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
        val cookies = manager.getCookie(urlString) ?: return 0
        return cookies.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { cookieNames == null || it in cookieNames }
            .onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
    }

    fun removeAll() {
        manager.removeAllCookies {}
    }
}

class HttpException(val code: Int) : IllegalStateException("HTTP error $code")

class UserAgentInterceptor(private val defaultUserAgentProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("User-Agent").isNullOrEmpty()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader("User-Agent").addHeader("User-Agent", defaultUserAgentProvider()).build())
    }
}

/** OkHttp's enqueue only reports IOExceptions; anything else would crash the app. */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = try {
        chain.proceed(chain.request())
    } catch (e: Exception) {
        throw if (e is IOException) e else IOException(e)
    }
}

class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.IO) {
        QuickJs.create().use { it.evaluate(script) as T }
    }
}
