package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.XtreamPhoneSetupServer
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.LiveTvHistory
import com.nuvio.tv.data.livetv.LiveTvPlayer
import com.nuvio.tv.data.livetv.LiveTvStorage
import com.nuvio.tv.data.livetv.XtreamCatalog
import com.nuvio.tv.data.livetv.XtreamClient
import com.nuvio.tv.data.livetv.XtreamException
import com.nuvio.tv.data.livetv.XtreamProblem
import com.nuvio.tv.data.livetv.XtreamSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class LiveTvState(
    val profileId: Int,
    val player: LiveTvPlayer = LiveTvPlayer.DEFAULT,
    val catalog: XtreamCatalog? = null,
    val sourceKey: String? = null,
    val hasSource: Boolean = false,
    val loading: Boolean = true,
    val history: LiveTvHistory = LiveTvHistory(),
    val lastTunedChannelId: Long? = null,
    val guide: Map<Long, List<LiveProgramme>> = emptyMap(),
    @StringRes val error: Int? = null
)

internal data class XtreamPhoneSetupState(
    val url: String? = null,
    val testing: Boolean = false,
    val channelCount: Int? = null,
    @StringRes val message: Int? = null
)

@HiltViewModel
internal class LiveTvViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileManager,
    private val storage: LiveTvStorage
) : ViewModel() {
    private data class GuideCheck(val checkedAt: Long, val full: Boolean)

    private class Profile(
        val id: Int,
        val resetVersion: Int,
        val scope: CoroutineScope
    ) {
        var source: XtreamSource? = null
        var refreshedAt: Long? = null
        var ready = false
        var tuningOrder: List<Long>? = null
        val loadingGuide = mutableSetOf<Long>()
        val guideCheckedAt = mutableMapOf<Long, GuideCheck>()
        val pendingFullGuide = mutableSetOf<Long>()
    }

    private val client = XtreamClient()
    private val guideRequests = Semaphore(3)
    private var profile: Profile? = null
    private var liveTvVisible = false
    private var phoneServer: XtreamPhoneSetupServer? = null
    private var verifiedPlaylist: Pair<XtreamSource, XtreamCatalog>? = null

    private val _state = MutableStateFlow(LiveTvState(profiles.activeProfileId.value))
    val state = _state.asStateFlow()
    private val _phone = MutableStateFlow<XtreamPhoneSetupState?>(null)
    val phone = _phone.asStateFlow()

    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, storage.resetVersion) { id, reset -> id to reset }
                .collect { (id, reset) -> openProfile(id, reset) }
        }
    }

    private fun openProfile(id: Int, resetVersion: Int) {
        closePhoneSetup()
        profile?.scope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val active = Profile(id, resetVersion, scope)
        profile = active
        _state.value = LiveTvState(id)
        scope.launch {
            val (player, saved) = withContext(Dispatchers.IO) {
                val source = storage.readSource(id)
                storage.readPlayer(id) to Triple(
                    source,
                    source?.let { storage.readCatalog(id, it) },
                    source?.let { storage.readHistory(id, it) }
                )
            }
            if (profile !== active) return@launch
            active.source = saved.first
            active.ready = true
            _state.value = LiveTvState(
                profileId = id,
                player = player,
                catalog = saved.second,
                sourceKey = saved.first?.cacheKey,
                history = saved.third ?: LiveTvHistory(),
                hasSource = saved.first != null,
                loading = false
            )
            if (liveTvVisible) refreshIfStale()
        }
    }

    fun setLiveTvVisible(visible: Boolean) {
        liveTvVisible = visible
        if (visible) refreshIfStale()
    }

    private fun refreshIfStale() {
        val refreshedAt = profile?.refreshedAt
        if (refreshedAt == null || SystemClock.elapsedRealtime() - refreshedAt >= CATALOG_TTL_MS) refresh()
    }

    fun refresh() {
        val active = currentProfile() ?: return
        val source = active.source ?: return
        if (_state.value.loading) return
        active.refreshedAt = SystemClock.elapsedRealtime()
        load(active, source, save = false)
    }

    fun connect(server: String, username: String, password: String): Boolean {
        val active = currentProfile() ?: return false
        if (_state.value.loading) return false
        val candidate = XtreamSource.from(server, username, password)
        if (candidate == null) {
            _state.update { it.copy(error = R.string.live_tv_invalid_source) }
            return false
        }
        load(active, candidate, save = true)
        return true
    }

    fun setPlayer(player: LiveTvPlayer) {
        val active = currentProfile() ?: return
        storage.writePlayer(active.id, player)
        _state.update { it.copy(player = player) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun disconnect() {
        val active = currentProfile() ?: return
        if (_state.value.loading) return
        closePhoneSetup()
        _state.update { it.copy(loading = true, error = null) }
        active.scope.launch {
            if (!storeSource(active, null)) return@launch
            active.source = null
            active.tuningOrder = null
            active.refreshedAt = null
            active.guideCheckedAt.clear()
            active.loadingGuide.clear()
            active.pendingFullGuide.clear()
            withContext(Dispatchers.IO) { storage.clearCatalog(active.id) }
            _state.value = LiveTvState(active.id, player = _state.value.player, loading = false)
        }
    }

    fun tune(channel: LiveChannel, channelList: List<LiveChannel>? = null): String? {
        val active = currentProfile() ?: return null
        val url = streamUrl(active.id, channel.id) ?: return null
        if (channelList != null) {
            active.tuningOrder = channelList.map { it.id }
        } else if (active.tuningOrder?.contains(channel.id) != true) {
            // A manual pick outside the current list starts tuning in the new channel's group.
            active.tuningOrder = _state.value.catalog?.channels
                ?.filter { it.group == channel.group }?.map { it.id }
        }
        _state.update { it.copy(lastTunedChannelId = channel.id) }
        return url
    }

    fun streamUrl(profileId: Int, channelId: Long): String? {
        val source = currentProfile()?.takeIf { it.id == profileId }?.source ?: return null
        val catalog = _state.value.catalog ?: return null
        if (catalog.channels.none { it.id == channelId }) return null
        return source.streamUrl(channelId, catalog.format).toString()
    }

    fun adjacentChannel(profileId: Int, currentId: Long, offset: Int): LiveChannel? {
        val active = currentProfile()?.takeIf { it.id == profileId } ?: return null
        val channels = _state.value.catalog?.channels ?: return null
        val index = channels.associateBy { it.id }
        val current = index[currentId] ?: return null
        val order = active.tuningOrder?.mapNotNull(index::get) ?: channels.filter { it.group == current.group }
        val position = order.indexOfFirst { it.id == currentId }
        return if (position >= 0) order.getOrNull(position + offset) else null
    }

    fun toggleFavourite(channelId: Long) = updateHistory { it.toggleFavourite(channelId) }

    fun channelRendered(profileId: Int, channelId: Long) {
        if (_state.value.profileId == profileId) updateHistory { it.watched(channelId) }
    }

    private fun updateHistory(update: (LiveTvHistory) -> LiveTvHistory) {
        val active = currentProfile() ?: return
        val source = active.source ?: return
        val changed = update(_state.value.history)
        if (changed == _state.value.history) return
        storage.writeHistory(active.id, source, changed)
        _state.update { it.copy(history = changed) }
    }

    fun requestGuide(ids: List<Long>, full: Boolean = false) {
        val active = currentProfile() ?: return
        val source = active.source ?: return
        val timezone = _state.value.catalog?.timezone
        val now = SystemClock.elapsedRealtime()
        ids.forEach { id ->
            val check = active.guideCheckedAt[id]
            if (check != null && now - check.checkedAt < GUIDE_TTL_MS && (!full || check.full)) return@forEach
            val fullSchedule = full || check?.full == true
            if (!active.loadingGuide.add(id)) {
                if (fullSchedule) active.pendingFullGuide.add(id)
                return@forEach
            }
            active.scope.launch {
                try {
                    val listings = guideRequests.withPermit {
                        if (active.source !== source) return@withPermit null
                        providerRequest { client.guide(source, id, timezone, fullSchedule) }.getOrNull()
                    }
                    if (active.source !== source) return@launch
                    active.guideCheckedAt[id] = GuideCheck(SystemClock.elapsedRealtime(), fullSchedule)
                    if (listings != null) {
                        val guide = _state.value.guide
                        val evicted = guide.keys.firstOrNull().takeIf { id !in guide && guide.size >= 256 }
                        val entries = if (evicted != null) {
                            active.guideCheckedAt.remove(evicted)
                            guide - evicted
                        } else guide
                        _state.update { it.copy(guide = entries + (id to listings)) }
                    }
                } finally {
                    if (active.source === source) {
                        active.loadingGuide.remove(id)
                        if (active.pendingFullGuide.remove(id) && !fullSchedule && currentProfile() === active) requestGuide(listOf(id), full = true)
                    }
                }
            }
        }
    }

    fun startPhoneSetup() {
        val active = currentProfile() ?: return
        if (_state.value.loading) return
        closePhoneSetup()
        _phone.value = XtreamPhoneSetupState()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _phone.value = XtreamPhoneSetupState(message = R.string.error_network_required)
            return
        }
        val server = XtreamPhoneSetupServer(
            ip,
            assets = { file -> context.assets.open("nexus/playlist/" + file).use { it.readBytes() } },
            onPlaylist = { listener, candidate ->
                active.scope.launch { verifyPhonePlaylist(active, listener, candidate) }
            }
        )
        val started = runCatching { server.start(5_000, true) }.isSuccess
        if (!started) {
            server.stop()
            _phone.value = XtreamPhoneSetupState(message = R.string.error_server_ports_unavailable)
            return
        }
        phoneServer = server
        _phone.value = XtreamPhoneSetupState(url = server.url)
        active.scope.launch {
            delay(XtreamPhoneSetupServer.VALID_FOR_MS)
            if (phoneServer === server && verifiedPlaylist == null) {
                closePhoneSetup()
                _phone.value = XtreamPhoneSetupState(message = R.string.live_tv_phone_expired)
            }
        }
    }

    fun closePhoneSetup() {
        val server = phoneServer
        phoneServer = null
        verifiedPlaylist = null
        _phone.value = null
        server?.stop()
    }

    fun savePhonePlaylist() {
        val active = currentProfile() ?: return
        if (_state.value.loading) return
        val (source, catalog) = verifiedPlaylist ?: return
        closePhoneSetup()
        _state.update { it.copy(loading = true, error = null) }
        active.scope.launch { applyCatalog(active, source, catalog, save = true) }
    }

    private suspend fun verifyPhonePlaylist(
        active: Profile,
        listener: XtreamPhoneSetupServer,
        candidate: XtreamSource
    ) {
        if (currentProfile() !== active || phoneServer !== listener) return
        _phone.update { it?.copy(testing = true, message = null) }
        val result = providerRequest { client.load(candidate) }
        if (phoneServer !== listener) return
        result.fold(
            onSuccess = { catalog ->
                verifiedPlaylist = candidate to catalog
                _phone.update { it?.copy(testing = false, channelCount = catalog.channels.size) }
            },
            onFailure = { failure ->
                _phone.update { it?.copy(testing = false, message = connectionMessage(failure)) }
                listener.retry()
            }
        )
    }

    override fun onCleared() {
        closePhoneSetup()
        super.onCleared()
    }

    private fun load(active: Profile, source: XtreamSource, save: Boolean) {
        _state.update { it.copy(loading = true, error = null) }
        active.scope.launch {
            val result = providerRequest { client.load(source) }
            result.fold(
                onSuccess = { applyCatalog(active, source, it, save) },
                onFailure = { failure ->
                    _state.update { it.copy(loading = false, error = connectionMessage(failure)) }
                }
            )
        }
    }

    private suspend fun applyCatalog(active: Profile, source: XtreamSource, catalog: XtreamCatalog, save: Boolean) {
        if (save && !storeSource(active, source)) return
        val oldSource = active.source
        val sameAccount = oldSource?.cacheKey == source.cacheKey
        val history = withContext(Dispatchers.IO) {
            ensureActive()
            runCatching { storage.writeCatalog(active.id, source, catalog) }
            if (sameAccount) null else storage.readHistory(active.id, source)
        }
        if (oldSource !== source) {
            active.loadingGuide.clear()
            active.pendingFullGuide.clear()
        }
        if (!sameAccount) {
            active.guideCheckedAt.clear()
            active.tuningOrder = null
        }
        active.source = source
        active.refreshedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                catalog = catalog,
                sourceKey = source.cacheKey,
                hasSource = true,
                loading = false,
                lastTunedChannelId = it.lastTunedChannelId.takeIf { sameAccount },
                history = history ?: it.history,
                guide = if (sameAccount) it.guide else emptyMap()
            )
        }
    }

    private fun currentProfile(): Profile? =
        profile?.takeIf {
            it.ready && it.id == profiles.activeProfileId.value && it.resetVersion == storage.resetVersion.value
        }

    private suspend fun storeSource(active: Profile, source: XtreamSource?): Boolean = try {
        withContext(Dispatchers.IO) { storage.writeSource(active.id, source) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        _state.update { it.copy(loading = false, error = R.string.live_tv_storage_error) }
        false
    }

    private suspend fun <T> providerRequest(block: () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    @StringRes
    private fun connectionMessage(failure: Throwable): Int = when ((failure as? XtreamException)?.problem) {
        XtreamProblem.SIGN_IN -> R.string.live_tv_credentials_rejected
        XtreamProblem.NO_CHANNELS -> R.string.live_tv_no_channels
        else -> R.string.live_tv_connection_error
    }

    private companion object {
        const val CATALOG_TTL_MS = 5 * 60_000L
        const val GUIDE_TTL_MS = 10 * 60_000L
    }
}
