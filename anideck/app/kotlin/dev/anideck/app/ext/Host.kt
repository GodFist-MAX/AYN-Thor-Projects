package dev.anideck.app.ext

import android.app.Application
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Enumeration
import java.util.concurrent.TimeUnit

/** Sets up what Aniyomi extensions expect the app to provide. */
object Host {
    lateinit var app: Application
        private set
    var versionCode = 0
        private set
    var versionName = ""
        private set

    val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }

    /**
     * The app's own client (cookies, Cloudflare) for the player and downloads, rather than a source's
     * rate-limited one. A video can stream over one call for its whole length, so there's no call timeout,
     * and media skips the small response cache.
     */
    @JvmStatic
    val client: OkHttpClient by lazy {
        network.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).cache(null).build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @JvmStatic
    fun init(app: Application, versionCode: Int, versionName: String) {
        this.app = app
        this.versionCode = versionCode
        this.versionName = versionName
        OkHttp.initialize(app)
        Injekt.addSingleton<Application>(app)
        Injekt.addSingletonFactory { NetworkHelper(app) }
        Injekt.addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        Injekt.addSingletonFactory<ProtoBuf> { ProtoBuf }
        Injekt.addSingletonFactory { JavaScriptEngine(app) }
    }
}

/** Loads an extension's own classes before the app's, so its bundled helpers win over same-named ones here. */
class ChildFirstPathClassLoader(dexPath: String, librarySearchPath: String?, parent: ClassLoader) :
    PathClassLoader(dexPath, librarySearchPath, parent) {
    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        var c = findLoadedClass(name)
        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        if (c == null) {
            c = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }
        if (resolve) resolveClass(c)
        return c!!
    }

    override fun getResource(name: String?): URL? = systemClassLoader?.getResource(name) ?: findResource(name) ?: super.getResource(name)

    override fun getResources(name: String?): Enumeration<URL> {
        val urls = buildList {
            systemClassLoader?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        }
        return java.util.Collections.enumeration(urls)
    }

    override fun getResourceAsStream(name: String?): InputStream? = try {
        getResource(name)?.openStream()
    } catch (_: IOException) {
        null
    }
}
