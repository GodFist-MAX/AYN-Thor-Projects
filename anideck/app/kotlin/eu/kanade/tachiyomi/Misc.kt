// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0.
@file:Suppress("unused")

package eu.kanade.tachiyomi

import dev.anideck.app.ext.Host

object AppInfo {
    fun getVersionCode(): Int = Host.versionCode

    fun getVersionName(): String = Host.versionName
}
