package com.nuvio.tv.data.livetv

import android.content.Context
import android.util.AtomicFile
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.domain.model.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class LiveTvHistory(
    val favourites: List<Long> = emptyList(),
    val recent: List<Long> = emptyList()
) {
    fun toggleFavourite(id: Long): LiveTvHistory = copy(
        favourites = if (id in favourites) favourites - id else favourites + id
    )

    fun watched(id: Long): LiveTvHistory = copy(recent = (listOf(id) + (recent - id)).take(30))

    fun previousChannel(currentId: Long, isAvailable: (Long) -> Boolean = { true }): Long? =
        recent.firstOrNull { it != currentId && isAvailable(it) }
}

internal enum class LiveTvPlayer {
    DEFAULT,
    LIBVLC
}

@Singleton
internal class LiveTvStorage @Inject constructor(@ApplicationContext context: Context) : ProfileScopedCredentialStore {
    private val credentials = ProtectedProfilePreferences(context, "nexus_iptv", "app.nexusmedia.tv.iptv.v1")
    private val directory = File(context.filesDir, "live-tv")
    private val history = context.getSharedPreferences("nexus_live_tv_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheLock = Any()
    private val _resetVersion = MutableStateFlow(0)
    val resetVersion = _resetVersion.asStateFlow()

    fun readPlayer(profileId: Int): LiveTvPlayer = runCatching {
        LiveTvPlayer.valueOf(history.getString("profile.$profileId.player", null).orEmpty())
    }.getOrDefault(LiveTvPlayer.DEFAULT)

    fun writePlayer(profileId: Int, player: LiveTvPlayer) {
        history.edit().putString("profile.$profileId.player", player.name).apply()
    }

    fun readGuideHistoryDays(profileId: Int): Int =
        history.getInt("profile.$profileId.guideHistoryDays", 1).takeIf { it in LIVE_TV_GUIDE_HISTORY_DAYS } ?: 1

    fun writeGuideHistoryDays(profileId: Int, days: Int) {
        require(days in LIVE_TV_GUIDE_HISTORY_DAYS)
        history.edit().putInt("profile.$profileId.guideHistoryDays", days).apply()
    }

    fun readGuideColour(profileId: Int): AppTheme? {
        val name = history.getString("profile.$profileId.guideColour", null)
        return AppTheme.entries.firstOrNull { it.name == name && it != AppTheme.CUSTOM }
    }

    fun writeGuideColour(profileId: Int, colour: AppTheme?) {
        require(colour != AppTheme.CUSTOM)
        history.edit().putString("profile.$profileId.guideColour", colour?.name).apply()
    }

    fun readStereoPcm(profileId: Int): Boolean = history.getBoolean("profile.$profileId.stereoPcm", false)

    fun writeStereoPcm(profileId: Int, enabled: Boolean) {
        history.edit().putBoolean("profile.$profileId.stereoPcm", enabled).apply()
    }

    fun readSource(profileId: Int): XtreamSource? = runCatching {
        credentials.read(profileId)?.let {
            val saved = json.decodeFromString<SavedSource>(it)
            XtreamSource.from(saved.server, saved.username, saved.password)
        }
    }.getOrNull()

    fun writeSource(profileId: Int, source: XtreamSource?) {
        val value = source?.let { json.encodeToString(SavedSource(it.server.toString(), it.username, it.password)) }
        credentials.write(profileId, value)
    }

    fun readCatalog(profileId: Int, source: XtreamSource): XtreamCatalog? = synchronized(cacheLock) {
        runCatching {
            val entry = json.decodeFromString<CachedCatalog>(cache(profileId).readFully().decodeToString())
            entry.catalog.takeIf { entry.sourceKey == source.cacheKey }
        }.getOrNull()
    }

    fun writeCatalog(profileId: Int, source: XtreamSource, catalog: XtreamCatalog) = synchronized(cacheLock) {
        check(directory.isDirectory || directory.mkdirs())
        val cache = cache(profileId)
        val bytes = json.encodeToString(CachedCatalog(source.cacheKey, catalog)).encodeToByteArray()
        val stream = cache.startWrite()
        try {
            stream.write(bytes)
            cache.finishWrite(stream)
        } catch (failure: Exception) {
            cache.failWrite(stream)
            throw failure
        }
    }

    fun clearCatalog(profileId: Int) = synchronized(cacheLock) { cache(profileId).delete() }

    fun readHistory(profileId: Int, source: XtreamSource): LiveTvHistory = runCatching {
        history.getString(historyKey(profileId, source), null)?.let { json.decodeFromString<LiveTvHistory>(it) }
    }.getOrNull() ?: LiveTvHistory()

    fun writeHistory(profileId: Int, source: XtreamSource, state: LiveTvHistory) {
        history.edit().putString(historyKey(profileId, source), json.encodeToString(state)).apply()
    }

    fun readCategories(profileId: Int, source: XtreamSource): LiveTvCategories = runCatching {
        history.getString("${historyKey(profileId, source)}.categories", null)
            ?.let { json.decodeFromString<LiveTvCategories>(it) }
    }.getOrNull() ?: LiveTvCategories()

    fun writeCategories(profileId: Int, source: XtreamSource, categories: LiveTvCategories) {
        history.edit()
            .putString("${historyKey(profileId, source)}.categories", json.encodeToString(categories))
            .apply()
    }

    override fun removeProfile(profileId: Int) {
        writeSource(profileId, null)
        clearCatalog(profileId)
        val editor = history.edit()
        history.all.keys.filter { it.startsWith("profile.$profileId.") }.forEach { editor.remove(it) }
        editor.apply()
        _resetVersion.update { it + 1 }
    }

    override fun clearAllProfiles() {
        credentials.clear()
        history.edit().clear().apply()
        synchronized(cacheLock) {
            directory.listFiles()?.filter { it.isFile && it.name.startsWith("profile-") }?.forEach { it.delete() }
        }
        _resetVersion.update { it + 1 }
    }

    private fun cache(profileId: Int): AtomicFile = AtomicFile(File(directory, "profile-$profileId.json"))

    private fun historyKey(profileId: Int, source: XtreamSource): String = "profile.$profileId.${source.cacheKey}"

    @Serializable
    private data class SavedSource(val server: String, val username: String, val password: String)

    @Serializable
    private data class CachedCatalog(val sourceKey: String, val catalog: XtreamCatalog)
}
