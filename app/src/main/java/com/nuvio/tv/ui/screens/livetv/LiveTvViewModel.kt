package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import android.net.Uri
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
import com.nuvio.tv.data.livetv.LiveRecording
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.data.livetv.LiveTvRecordings
import com.nuvio.tv.data.livetv.RecordingRequest
import com.nuvio.tv.data.livetv.canReplay
import com.nuvio.tv.data.livetv.guideHistoryStart
import com.nuvio.tv.data.livetv.LiveTvCategories
import com.nuvio.tv.data.livetv.LiveTvHistory
import com.nuvio.tv.data.livetv.LiveTvPlayer
import com.nuvio.tv.data.livetv.LiveTvStorage
import com.nuvio.tv.data.livetv.XtreamCatalog
import com.nuvio.tv.data.livetv.XtreamClient
import com.nuvio.tv.data.livetv.XtreamConnections
import com.nuvio.tv.data.livetv.XtreamException
import com.nuvio.tv.data.livetv.XtreamProblem
import com.nuvio.tv.data.livetv.XtreamSource
import com.nuvio.tv.domain.model.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
import java.time.ZoneId
import javax.inject.Inject

internal data class LiveTvState(
    val profileId: Int,
    val player: LiveTvPlayer = LiveTvPlayer.DEFAULT,
    val guideHistoryDays: Int = 1,
    val guideColour: AppTheme? = null,
    val stereoPcm: Boolean = false,
    val catalog: XtreamCatalog? = null,
    val sourceKey: String? = null,
    val hasSource: Boolean = false,
    val loading: Boolean = true,
    val history: LiveTvHistory = LiveTvHistory(),
    val categories: LiveTvCategories = LiveTvCategories(),
    val lastTunedChannelId: Long? = null,
    val guide: Map<Long, List<LiveProgramme>> = emptyMap(),
    @StringRes val error: Int? = null
) {
    fun visibleChannels(): List<LiveChannel> {
        val channels = catalog?.channels.orEmpty()
        return if (categories.hidden.isEmpty()) channels else channels.filterNot { it.group in categories.hidden }
    }
}

internal data class LiveTvArchivePlayback(
    val url: String,
    val title: String,
    val profileId: Int,
    val filename: String? = null
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
    private val storage: LiveTvStorage,
    private val recorder: LiveTvRecordings
) : ViewModel() {
    private data class GuideCheck(val checkedAt: Long, val full: Boolean)
    private data class SportsGuideCheck(val checkedAt: Long, val programmes: List<LiveProgramme>)

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
        val sportsGuide = mutableMapOf<Long, SportsGuideCheck>()
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
    val recordings = recorder.entries

    init {
        viewModelScope.launch(Dispatchers.IO) { recorder.restoreAlarms() }
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
            val (source, saved) = withContext(Dispatchers.IO) {
                val source = storage.readSource(id)
                source to LiveTvState(
                    profileId = id,
                    player = storage.readPlayer(id),
                    guideHistoryDays = storage.readGuideHistoryDays(id),
                    guideColour = storage.readGuideColour(id),
                    stereoPcm = storage.readStereoPcm(id),
                    catalog = source?.let { storage.readCatalog(id, it) },
                    sourceKey = source?.cacheKey,
                    history = source?.let { storage.readHistory(id, it) } ?: LiveTvHistory(),
                    categories = source?.let { storage.readCategories(id, it) } ?: LiveTvCategories(),
                    hasSource = source != null,
                    loading = false
                )
            }
            if (profile !== active) return@launch
            active.source = source
            active.ready = true
            _state.value = saved
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

    suspend fun recordProgramme(event: LiveSportsEvent): RecordingRequest {
        val active = currentProfile() ?: return RecordingRequest.UNAVAILABLE
        val source = active.source ?: return RecordingRequest.UNAVAILABLE
        val catalog = _state.value.catalog ?: return RecordingRequest.UNAVAILABLE
        val channel = catalog.channels.firstOrNull { it.id == event.channel.id } ?: return RecordingRequest.UNAVAILABLE
        return withContext(Dispatchers.IO) {
            recorder.schedule(active.id, source.cacheKey, channel, event.programme, catalog.format)
        }
    }

    fun stopOrRemoveRecording(entry: LiveRecording) {
        val active = currentProfile() ?: return
        if (active.id == entry.profileId) active.scope.launch(Dispatchers.IO) { recorder.stopOrRemove(active.id, entry.id) }
    }

    fun playRecording(entry: LiveRecording): LiveTvArchivePlayback? {
        val active = currentProfile() ?: return null
        val file = recorder.recordingFile(active.id, entry.id) ?: return null
        return LiveTvArchivePlayback(Uri.fromFile(file).toString(), entry.title, active.id, file.name)
    }

    fun playReplay(event: LiveSportsEvent): LiveTvArchivePlayback? {
        val active = currentProfile() ?: return null
        val source = active.source ?: return null
        val catalog = _state.value.catalog ?: return null
        val channel = catalog.channels.firstOrNull { it.id == event.channel.id } ?: return null
        if (!event.programme.canReplay(channel, System.currentTimeMillis())) return null
        val url = source.replayUrl(channel, event.programme, catalog).toString()
        return LiveTvArchivePlayback(url, event.programme.title, active.id, "replay.${catalog.format}")
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

    fun setGuideHistoryDays(days: Int) {
        val active = currentProfile() ?: return
        val previousDays = _state.value.guideHistoryDays
        storage.writeGuideHistoryDays(active.id, days)
        val cutoff = guideHistoryStart(System.currentTimeMillis(), days, ZoneId.systemDefault())
        _state.update { state ->
            state.copy(guideHistoryDays = days, guide = state.guide.mapValues { (_, listings) ->
                listings.filter { it.endsAt > cutoff }
            })
        }
        val source = active.source ?: return
        if (days < previousDays) {
            active.scope.launch(Dispatchers.IO) { storage.trimGuideHistory(active.id, source) }
        }
        // Fetch the provider's full table again the next time the guide opens.
        active.guideCheckedAt.clear()
    }

    fun setGuideColour(colour: AppTheme?) {
        val active = currentProfile() ?: return
        storage.writeGuideColour(active.id, colour)
        _state.update { it.copy(guideColour = colour) }
    }

    fun setStereoPcm(enabled: Boolean) {
        val active = currentProfile() ?: return
        storage.writeStereoPcm(active.id, enabled)
        _state.update { it.copy(stereoPcm = enabled) }
    }

    fun updateCategories(sourceKey: String?, update: (LiveTvCategories) -> LiveTvCategories) {
        val active = currentProfile() ?: return
        val source = active.source?.takeIf { it.cacheKey == sourceKey } ?: return
        if (_state.value.loading) return
        val previous = _state.value.categories
        val changed = update(previous)
        if (previous == changed) return
        storage.writeCategories(active.id, source, changed)
        _state.update { it.copy(categories = changed) }
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
            _state.update { current ->
                LiveTvState(
                    profileId = active.id,
                    player = current.player,
                    guideHistoryDays = current.guideHistoryDays,
                    guideColour = current.guideColour,
                    stereoPcm = current.stereoPcm,
                    loading = false
                )
            }
        }
    }

    fun tune(channel: LiveChannel, channelList: List<LiveChannel>? = null): String? {
        val active = currentProfile() ?: return null
        if (channel.group in _state.value.categories.hidden) return null
        val url = streamUrl(active.id, channel.id) ?: return null
        if (channelList != null) {
            active.tuningOrder = channelList.map { it.id }
        } else if (active.tuningOrder?.contains(channel.id) != true) {
            // A manual pick outside the current list starts tuning in the new channel's group.
            active.tuningOrder = _state.value.visibleChannels()
                .filter { it.group == channel.group }.map { it.id }
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
        val channels = _state.value.visibleChannels()
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

    suspend fun searchProgrammes(query: String): List<LiveSportsEvent> {
        val active = currentProfile() ?: return emptyList()
        val source = active.source ?: return emptyList()
        val snapshot = _state.value
        val channels = snapshot.visibleChannels().associateBy { it.id }
        val results = withContext(Dispatchers.IO) {
            storage.searchGuide(active.id, source, channels, snapshot.guide, query)
        }
        return results.takeIf { currentProfile() === active && active.source === source }.orEmpty()
    }

    suspend fun readPlaylistConnections(profileId: Int, sourceKey: String): XtreamConnections? {
        val active = currentProfile()?.takeIf { it.id == profileId } ?: return null
        val source = active.source?.takeIf { it.cacheKey == sourceKey } ?: return null
        val connections = providerRequest { client.playlistConnections(source) }.getOrNull()
        return connections.takeIf { currentProfile() === active && active.source === source }
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
                    if (id !in _state.value.guide) {
                        val cached = withContext(Dispatchers.IO) { storage.readGuide(active.id, source, id) }
                        if (active.source !== source) return@launch
                        if (cached.isNotEmpty()) showGuideListings(active, id, cached)
                    }
                    val updated = guideRequests.withPermit {
                        if (active.source !== source) return@withPermit null
                        providerRequest { client.guide(source, id, timezone, fullSchedule) }.getOrNull()
                    }
                    if (active.source !== source) return@launch
                    active.guideCheckedAt[id] = GuideCheck(SystemClock.elapsedRealtime(), fullSchedule)
                    if (!updated.isNullOrEmpty()) {
                        val listings = withContext(Dispatchers.IO) { storage.rememberGuide(active.id, source, id, updated) }
                        if (active.source !== source) return@launch
                        showGuideListings(active, id, listings)
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

    private fun showGuideListings(active: Profile, id: Long, listings: List<LiveProgramme>) {
        val guide = _state.value.guide
        val evicted = guide.keys.firstOrNull().takeIf { id !in guide && guide.size >= 256 }
        val entries = if (evicted != null) {
            active.guideCheckedAt.remove(evicted)
            guide - evicted
        } else guide
        _state.update { it.copy(guide = entries + (id to listings)) }
    }

    suspend fun scanSportsGuide(
        ids: List<Long>,
        onListings: (Map<Long, List<LiveProgramme>>) -> Unit
    ) {
        val active = currentProfile() ?: return
        val source = active.source ?: return
        val timezone = _state.value.catalog?.timezone

        for (batch in ids.chunked(12)) {
            if (active.source !== source || currentProfile() !== active) return
            val checkedAt = SystemClock.elapsedRealtime()
            val updates = coroutineScope {
                batch.map { id ->
                    async {
                        val existing = _state.value.guide[id]
                        val guideCheck = active.guideCheckedAt[id]
                        if (existing != null && guideCheck != null && checkedAt - guideCheck.checkedAt < GUIDE_TTL_MS) {
                            return@async id to existing
                        }
                        val cached = active.sportsGuide[id]
                        if (cached != null && checkedAt - cached.checkedAt < GUIDE_TTL_MS) {
                            return@async id to cached.programmes
                        }
                        val programmes = guideRequests.withPermit {
                            if (active.source !== source || currentProfile() !== active) return@withPermit null
                            providerRequest { client.guide(source, id, timezone) }.getOrNull()
                        }
                        if (active.source === source && currentProfile() === active && programmes != null) {
                            active.sportsGuide[id] = SportsGuideCheck(SystemClock.elapsedRealtime(), programmes)
                        }
                        id to (programmes ?: cached?.programmes.orEmpty())
                    }
                }.awaitAll().toMap()
            }
            if (active.source !== source || currentProfile() !== active) return
            onListings(updates)
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
        val saved = withContext(Dispatchers.IO) {
            ensureActive()
            runCatching { storage.writeCatalog(active.id, source, catalog) }
            if (sameAccount) null else storage.readHistory(active.id, source) to storage.readCategories(active.id, source)
        }
        if (oldSource !== source) {
            active.loadingGuide.clear()
            active.pendingFullGuide.clear()
        }
        if (!sameAccount) {
            active.guideCheckedAt.clear()
            active.sportsGuide.clear()
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
                history = saved?.first ?: it.history,
                categories = saved?.second ?: it.categories,
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
