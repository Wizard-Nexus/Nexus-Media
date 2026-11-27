package com.nuvio.tv.ui.screens.livetv

import android.os.Bundle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.XtreamConnections
import com.nuvio.tv.data.livetv.canReplay
import com.nuvio.tv.data.livetv.earlierGuideDateTargets
import com.nuvio.tv.data.livetv.groupNames
import com.nuvio.tv.data.livetv.guideHistoryStart
import com.nuvio.tv.data.livetv.isSportsGuideCandidate
import com.nuvio.tv.data.livetv.liveMainSportsListings
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.settings.GuideColourPicker
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

private enum class LiveTvSection(val label: Int) {
    ALL(R.string.live_tv_all),
    FAVOURITES(R.string.live_tv_favourites),
    RECORDINGS(R.string.live_tv_recordings),
    RECENT(R.string.live_tv_recent),
    GUIDE(R.string.live_tv_guide),
    SPORTS(R.string.live_tv_sports)
}

internal const val LIVE_TV_GUIDE_STATE = "liveTvGuideState"

private val GuideSections = listOf(
    LiveTvSection.FAVOURITES, LiveTvSection.RECORDINGS, LiveTvSection.ALL,
    LiveTvSection.RECENT, LiveTvSection.SPORTS
)

@Composable
internal fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel, List<LiveChannel>, Bundle) -> Boolean,
    onOpenSettings: () -> Unit,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.setLiveTvVisible(true)
        onDispose { viewModel.setLiveTvVisible(false) }
    }
    val firstFocus = LocalContentFocusRequester.current
    val hasVisibleChannels = remember(state.catalog, state.categories.hidden) { state.visibleChannels().isNotEmpty() }
    LiveTvGuideTheme(state.guideColour) {
        if (!hasVisibleChannels) {
            var showRecordings by remember(state.profileId) { mutableStateOf(false) }
            LiveTvEmptyGuide(state, firstFocus, onOpenSettings, onRecordings = { showRecordings = true })
            if (showRecordings) {
                LiveTvRecordingsDialog(viewModel, state.profileId, onPlayArchive) { showRecordings = false }
            }
        } else {
            key(state.profileId, state.sourceKey) {
                LiveTvGuideContent(state, viewModel, firstFocus, onPlay, onPlayArchive)
            }
        }
    }
}

@Composable
internal fun LiveTvPlaybackGuide(
    state: LiveTvState,
    viewModel: LiveTvViewModel,
    currentId: Long,
    initialBrowse: Bundle?,
    onTune: (LiveChannel, List<LiveChannel>, Bundle) -> Boolean,
    onFullscreen: () -> Unit,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LiveTvGuideTheme(state.guideColour) {
        key(state.profileId, state.sourceKey) {
            LiveTvGuideContent(
                state, viewModel, firstFocus,
                onPlay = { channel, list, browse ->
                    if (channel.id == currentId) {
                        onFullscreen()
                        true
                    } else {
                        onTune(channel, list, browse)
                    }
                },
                onPlayArchive = onPlayArchive,
                playingId = currentId,
                initialBrowse = initialBrowse,
                onDismiss = onFullscreen,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun LiveTvEmptyGuide(
    state: LiveTvState,
    firstFocus: FocusRequester,
    onOpenSettings: () -> Unit,
    onRecordings: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(GuideRail), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(stringResource(R.string.live_tv_guide), style = MaterialTheme.typography.headlineLarge)
            val message = state.error ?: when {
                state.loading -> R.string.live_tv_loading
                state.hasSource && !state.catalog?.channels.isNullOrEmpty() -> R.string.live_tv_all_groups_hidden
                else -> R.string.live_tv_empty
            }
            Text(stringResource(message), color = GuideMuted)
            if (!state.loading) {
                GuideButton(stringResource(R.string.live_tv_open_settings), onOpenSettings, Modifier.focusRequester(firstFocus))
                GuideButton(stringResource(R.string.live_tv_recordings), onRecordings)
                LaunchedEffect(firstFocus) {
                    withFrameNanos { }
                    runCatching { firstFocus.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun LiveTvGuideContent(
    state: LiveTvState,
    viewModel: LiveTvViewModel,
    firstFocus: FocusRequester,
    onPlay: (LiveChannel, List<LiveChannel>, Bundle) -> Boolean,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean,
    playingId: Long? = null,
    initialBrowse: Bundle? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val channels = remember(state.catalog, state.categories.hidden) { state.visibleChannels() }
    val channelIndex = remember(channels) { channels.associateBy { it.id } }
    val groups = remember(state.catalog, state.categories) {
        state.categories.ordered(state.catalog?.groupNames().orEmpty()).filterNot(state.categories.hidden::contains)
    }
    val favouriteIds = remember(state.history.favourites) { state.history.favourites.toHashSet() }
    var section by rememberSaveable {
        mutableStateOf(LiveTvSection.entries.firstOrNull { it.name == initialBrowse?.getString("section") } ?: LiveTvSection.ALL)
    }
    var group by rememberSaveable {
        mutableStateOf(if (initialBrowse != null) initialBrowse.getString("group") else channelIndex[playingId]?.group)
    }
    var query by rememberSaveable { mutableStateOf(initialBrowse?.getString("query").orEmpty()) }
    var searching by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf(query) }
    var submittedSearch by remember { mutableStateOf<String?>(null) }
    var choosingDate by remember { mutableStateOf(false) }
    var choosingColour by remember { mutableStateOf(false) }
    var eventDetails by remember { mutableStateOf<LiveSportsEvent?>(null) }
    var showRecordings by remember { mutableStateOf(false) }
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    var categoriesVisible by rememberSaveable { mutableStateOf(initialBrowse?.getBoolean("categories") ?: (playingId == null)) }
    var restorePlaybackFocus by rememberSaveable { mutableStateOf(false) }
    var placedFocus by remember { mutableStateOf(false) }
    val restoreId = remember { playingId ?: state.lastTunedChannelId.takeIf { restorePlaybackFocus } }

    val now = liveGuideClock()
    val zone = ZoneId.systemDefault()
    val historyStart = guideHistoryStart(now, state.guideHistoryDays, zone)
    var connections by remember(state.profileId, state.sourceKey) { mutableStateOf<XtreamConnections?>(null) }
    LaunchedEffect(playingId, state.profileId, state.sourceKey) {
        val sourceKey = state.sourceKey ?: return@LaunchedEffect
        if (playingId == null) return@LaunchedEffect
        delay(1_500)
        while (true) {
            connections = viewModel.readPlaylistConnections(state.profileId, sourceKey)
            delay(15_000)
        }
    }
    val sportsOnly = section == LiveTvSection.SPORTS && group == null
    val sportsCandidates = remember(channels) { channels.filter(LiveChannel::isSportsGuideCandidate) }
    var sportsSchedules by remember(state.sourceKey) { mutableStateOf(emptyMap<Long, List<LiveProgramme>>()) }
    var sportsLoading by remember(state.sourceKey) { mutableStateOf(false) }
    LaunchedEffect(sportsOnly, state.sourceKey, sportsCandidates) {
        if (!sportsOnly || sportsCandidates.isEmpty()) return@LaunchedEffect
        val ids = sportsCandidates.map { it.id }
        while (true) {
            sportsLoading = true
            try {
                viewModel.scanSportsGuide(ids) { batch -> sportsSchedules = sportsSchedules + batch }
            } finally {
                sportsLoading = false
            }
            delay(5 * 60_000L)
        }
    }
    val historyGuide = remember(state.guide, historyStart) {
        state.guide.mapValues { (_, schedule) -> schedule.filter { it.endsAt > historyStart } }
    }
    val guide = if (sportsOnly) {
        remember(sportsCandidates, sportsSchedules, state.guide, now) {
            liveMainSportsListings(sportsCandidates, state.guide + sportsSchedules, now)
        }
    } else {
        historyGuide
    }

    val sportsIds = if (sportsOnly) guide.keys else emptySet()
    val items = remember(channels, channelIndex, sportsIds, section, group, query, state.history) {
        val filtered = when {
            group != null -> channels.filter { it.group == group }
            section == LiveTvSection.FAVOURITES -> state.history.favourites.mapNotNull(channelIndex::get)
            section == LiveTvSection.RECENT -> state.history.recent.mapNotNull(channelIndex::get)
            sportsOnly -> sportsIds.mapNotNull(channelIndex::get)
            else -> channels
        }
        filtered.filter { it.matchesLiveTvSearch(query) }
    }
    val rowState = rememberLazyListState()
    val visibleIds by remember {
        derivedStateOf { rowState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
    }
    val railState = rememberLazyListState()
    val categoryFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var railFocusRequest by remember { mutableIntStateOf(0) }
    var highlightedId by remember { mutableStateOf(restoreId) }
    var browsingTime by rememberSaveable { mutableLongStateOf(initialBrowse?.getLong("time") ?: 0L) }
    var windowAnchor by rememberSaveable { mutableLongStateOf(initialBrowse?.getLong("anchor") ?: 0L) }
    val targetRow = highlightedId?.let { highlighted -> items.firstOrNull { it.id == highlighted } } ?: items.firstOrNull()
    val programmeTime = browsingTime.takeIf { !sportsOnly && it != 0L }?.coerceAtLeast(historyStart) ?: now
    val highlighted = targetRow?.takeIf { highlightedId == it.id }
    val currentProgrammes = guide[highlighted?.id].orEmpty()
    val browsableSchedules = remember(currentProgrammes, guide, visibleIds) {
        if (currentProgrammes.isNotEmpty()) listOf(currentProgrammes) else visibleIds.mapNotNull(guide::get)
    }
    val selectedProgramme = currentProgrammes.firstOrNull { it.isOnAir(programmeTime) }
    val sectionKey = if (section == LiveTvSection.GUIDE) LiveTvSection.ALL else section

    fun showCategories() {
        categoriesVisible = true
        railFocusRequest++
    }

    fun clearTimeSelection() {
        browsingTime = 0L
        windowAnchor = 0L
    }

    fun resetList() {
        clearTimeSelection()
        highlightedId = null
    }

    fun play(channel: LiveChannel) {
        val browse = Bundle().apply {
            putString("section", section.name)
            putString("group", group)
            putString("query", query)
            putBoolean("categories", categoriesVisible)
            putLong("time", browsingTime)
            putLong("anchor", windowAnchor)
        }
        if (onPlay(channel, if (items.any { it.id == channel.id }) items else listOf(channel), browse)) {
            restorePlaybackFocus = true
        }
    }

    fun select(channel: LiveChannel, programme: LiveProgramme?) {
        val selectedAt = System.currentTimeMillis()
        if (programme == null || programme.isOnAir(selectedAt)) {
            play(channel)
            return
        }

        val event = LiveSportsEvent(channel, programme)
        if (programme.endsAt <= selectedAt) {
            val saved = recordings.firstOrNull {
                it.profileId == state.profileId && it.sourceKey == state.sourceKey &&
                    it.status.playable && it.matches(channel, programme)
            }
            val playback = saved?.let(viewModel::playRecording) ?: viewModel.playReplay(event)
            if (playback != null && onPlayArchive(playback)) return
        }
        eventDetails = event
    }

    LaunchedEffect(section, group, query) {
        if (placedFocus) rowState.scrollToItem(0)
    }
    LaunchedEffect(groups) {
        if (group != null && group !in groups) group = null
    }
    LaunchedEffect(sportsOnly, items, highlightedId) {
        if (sportsOnly && highlightedId != null && items.none { it.id == highlightedId }) {
            highlightedId = null
            if (!categoriesVisible) showCategories()
        }
    }
    LaunchedEffect(items, restoreId, sportsLoading) {
        if (placedFocus || items.isEmpty() && restoreId != null && (state.loading || sportsOnly && sportsLoading)) {
            return@LaunchedEffect
        }
        val restoreIndex = items.indexOfFirst { it.id == restoreId }
        if (restoreId != null && restoreIndex < 0 && (group != null || query.isNotBlank() || section != LiveTvSection.ALL)) {
            section = LiveTvSection.ALL
            group = null
            query = ""
            return@LaunchedEffect
        }
        if (restoreIndex >= 0) {
            rowState.scrollToItem(restoreIndex)
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == restoreId } }.first { it }
        }
        withFrameNanos { }
        if (restoreIndex >= 0 || !categoriesVisible && items.isNotEmpty()) {
            val focused = runCatching { rowFocus.requestFocus() }.getOrDefault(false)
            if (!focused && categoriesVisible) runCatching { categoryFocus.requestFocus() }
        } else if (categoriesVisible) {
            runCatching { categoryFocus.requestFocus() }
        }
        placedFocus = true
        restorePlaybackFocus = false
    }
    LaunchedEffect(railFocusRequest, categoriesVisible) {
        if (!categoriesVisible || railFocusRequest == 0) return@LaunchedEffect
        val groupIndex = groups.indexOf(group)
        val index = if (groupIndex < 0) GuideSections.indexOf(sectionKey) else GuideSections.size + 1 + groupIndex
        railState.scrollToItem(index.coerceAtLeast(0))
        withFrameNanos { }
        runCatching { categoryFocus.requestFocus() }
    }
    LaunchedEffect(rowState, state.sourceKey, sportsOnly, now / 60_000L) {
        if (sportsOnly) return@LaunchedEffect
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .collectLatest { ids ->
                if (ids.isNotEmpty()) {
                    delay(160)
                    viewModel.requestGuide(ids, full = true)
                }
            }
    }
    BackHandler(
        playingId == null && !categoriesVisible && !searching && !showRecordings &&
            !choosingDate && !choosingColour && eventDetails == null && submittedSearch == null
    ) { showCategories() }

    BoxWithConstraints(modifier.fillMaxSize().background(GuideRail)) {
        val detailsHeight = maxHeight * GUIDE_HEADER_SHARE
        val previewSize = if (playingId == null) null else guidePreviewSize(maxWidth, maxHeight)
        val railWidth = maxWidth * .244f
        val channelWidth = (maxWidth * .273f).coerceAtMost(272.dp)
        val timelineWidth = (maxWidth - channelWidth - if (categoriesVisible) railWidth else 0.dp).coerceAtLeast(1.dp)
        val halfHourWidth = (maxWidth * .18f).coerceIn(128.dp, 184.dp)
        val windowDuration = (timelineWidth.value.toDouble() / halfHourWidth.value * GUIDE_STEP_MS)
            .roundToLong().coerceAtLeast(1L)
        val start = windowAnchor.takeIf { !sportsOnly && it != 0L } ?: guideTimeFloor(now)
        val window = GuideWindow(start.coerceAtLeast(historyStart), windowDuration, timelineWidth)
        val bounds = remember(browsableSchedules, now, historyStart) {
            val earliest = browsableSchedules.mapNotNull { it.firstOrNull()?.startsAt }.minOrNull() ?: now
            val latest = browsableSchedules.mapNotNull { it.lastOrNull()?.endsAt }.maxOrNull() ?: now
            guideTimeFloor(minOf(earliest, now)).coerceAtLeast(historyStart) to maxOf(latest, now)
        }
        val pageDuration = (window.duration / GUIDE_STEP_MS).coerceAtLeast(1L) * GUIDE_STEP_MS

        fun reveal(time: Long) {
            browsingTime = time.coerceAtLeast(historyStart)
            if (browsingTime !in window.start until window.end) windowAnchor = guideTimeFloor(browsingTime)
        }

        fun page(forward: Boolean) {
            val next = if (forward) window.start + pageDuration else (window.start - pageDuration).coerceAtLeast(bounds.first)
            windowAnchor = next
            browsingTime = next
        }

        Column(Modifier.fillMaxSize()) {
            val status = state.error ?: R.string.live_tv_refreshing.takeIf { state.loading }
            LiveTvGuideHeader(
                channel = highlighted, programme = selectedProgramme, date = programmeTime, now = now,
                status = status?.let { stringResource(it) },
                sportsOnly = sportsOnly,
                canGoEarlier = window.start > bounds.first,
                canGoLater = window.end < bounds.second,
                categoriesVisible = categoriesVisible,
                onSearch = { searching = true },
                onChooseDate = { choosingDate = true },
                onChooseColour = { choosingColour = true },
                onNow = ::clearTimeSelection,
                onEarlier = { page(false) },
                onLater = { page(true) },
                onCategories = ::showCategories,
                onWatch = highlighted?.let { channel -> { play(channel) } },
                previewSize = previewSize,
                playingChannel = channelIndex[playingId],
                connections = connections,
                onDismiss = onDismiss,
                modifier = Modifier.height(detailsHeight)
            )
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (categoriesVisible) {
                    LiveTvCategoryRail(
                        groups, railState, sectionKey, group, categoryFocus, firstFocus,
                        onSection = { selected ->
                            if (selected == LiveTvSection.RECORDINGS) {
                                showRecordings = true
                            } else {
                                section = selected
                                group = null
                                query = ""
                                resetList()
                            }
                        },
                        onGroup = { selected ->
                            group = selected
                            query = ""
                            resetList()
                        },
                        enterChannels = { items.isNotEmpty() && runCatching { rowFocus.requestFocus() }.getOrDefault(false) },
                        modifier = Modifier.width(railWidth).fillMaxHeight()
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (query.isNotBlank()) stringResource(R.string.live_tv_search_results, query.trim())
                                else group ?: stringResource(sectionKey.label),
                                modifier = Modifier.width(channelWidth).padding(horizontal = 10.dp),
                                color = GuideAccent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            GuideRuler(window)
                        }
                        LazyColumn(state = rowState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                            if (items.isEmpty()) {
                                item {
                                    val empty = when {
                                        query.isNotBlank() -> R.string.live_tv_search_empty
                                        section == LiveTvSection.FAVOURITES -> R.string.live_tv_favourites_empty
                                        section == LiveTvSection.RECENT -> R.string.live_tv_recent_empty
                                        sportsOnly && sportsLoading -> R.string.live_tv_sports_checking
                                        sportsOnly -> R.string.live_tv_sports_empty
                                        else -> R.string.live_tv_no_matching_channels
                                    }
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(empty), color = GuideMuted)
                                        if (query.isNotBlank()) {
                                            GuideButton(stringResource(R.string.live_tv_clear_search), { query = ""; resetList() })
                                        }
                                    }
                                }
                            }
                            items(items, key = { it.id }) { channel ->
                                LiveTvGuideRow(
                                    channel = channel,
                                    programmes = guide[channel.id].orEmpty(),
                                    sportsOnly = sportsOnly,
                                    window = window,
                                    selectedTime = programmeTime,
                                    now = now,
                                    channelWidth = channelWidth,
                                    favourite = channel.id in favouriteIds,
                                    categoryRailVisible = categoriesVisible,
                                    onChannelFocus = { highlightedId = channel.id },
                                    onProgrammeFocus = {
                                        highlightedId = channel.id
                                        categoriesVisible = false
                                    },
                                    onBrowse = ::reveal,
                                    onOpenCategories = ::showCategories,
                                    onSelect = { programme -> select(channel, programme) },
                                    onProgrammeOptions = { programme -> eventDetails = LiveSportsEvent(channel, programme) },
                                    onFavourite = { viewModel.toggleFavourite(channel.id) },
                                    modifier = if (channel.id == targetRow?.id) {
                                        Modifier.focusRequester(rowFocus).then(
                                            if (categoriesVisible) Modifier else Modifier.focusRequester(firstFocus)
                                        )
                                    } else Modifier
                                )
                            }
                        }
                    }
                    GuideNowLine(window, now, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }

    if (choosingDate) {
        val dates = remember(browsableSchedules, historyStart) {
            earlierGuideDateTargets(browsableSchedules, now, state.guideHistoryDays, zone)
        }
        val dateLabel = formatGuideDate(programmeTime)
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.live_tv_guide_choose_date),
            subtitle = if (dates.isEmpty()) stringResource(R.string.live_tv_guide_no_history) else null,
            options = listOf(SettingsPickerOption(0L, stringResource(R.string.live_tv_guide_today))) +
                dates.map { SettingsPickerOption(it, formatGuideDate(it)) },
            selectedValue = dates.firstOrNull { formatGuideDate(it) == dateLabel } ?: 0L,
            onOptionSelected = { time ->
                choosingDate = false
                if (time == 0L) clearTimeSelection() else {
                    browsingTime = time
                    windowAnchor = guideTimeFloor(time)
                }
            },
            onDismiss = { choosingDate = false }
        )
    }
    if (choosingColour) {
        GuideColourPicker(
            selected = state.guideColour,
            onSelect = {
                viewModel.setGuideColour(it)
                choosingColour = false
            },
            onDismiss = { choosingColour = false }
        )
    }
    if (searching) {
        LiveTvSearchDialog(
            initialQuery = searchText,
            onSubmit = {
                searchText = it.trim()
                searching = false
                if (searchText.isEmpty()) {
                    query = ""
                    resetList()
                } else {
                    submittedSearch = searchText
                }
            },
            onDismiss = { searching = false }
        )
    }
    submittedSearch?.let { term ->
        var selectedDay by remember(term) { mutableStateOf<LocalDate?>(null) }
        var choosingDay by remember(term) { mutableStateOf(false) }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = remember(today, state.guideHistoryDays) {
            (0..state.guideHistoryDays).map { today.minusDays(it.toLong()) }
        }
        val effectiveDay = selectedDay?.takeIf { it in days }
        val allDaysLabel = stringResource(R.string.live_tv_search_all_days)
        val todayLabel = stringResource(R.string.live_tv_guide_today)
        fun dayLabel(day: LocalDate?): String = when (day) {
            null -> allDaysLabel
            today -> todayLabel
            else -> formatGuideDate(day.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        val programmes by produceState<List<LiveSportsEvent>?>(null, term, effectiveDay, state.sourceKey) {
            value = null
            value = viewModel.searchProgrammes(term, effectiveDay)
        }
        if (choosingDay) {
            SettingsSingleChoiceDialog(
                title = stringResource(R.string.live_tv_search_day),
                options = (listOf(null) + days).map { SettingsPickerOption(it, dayLabel(it)) },
                selectedValue = effectiveDay,
                onOptionSelected = {
                    selectedDay = it
                    choosingDay = false
                },
                onDismiss = { choosingDay = false }
            )
        } else if (eventDetails == null) {
            val matchingChannels = remember(channels, term) { channels.count { it.matchesLiveTvSearch(term) } }
            LiveTvSearchResultsDialog(
                term = term,
                programmes = programmes,
                channelCount = matchingChannels,
                now = now,
                dayLabel = dayLabel(effectiveDay),
                onChooseDay = { choosingDay = true },
                onSelect = { eventDetails = it },
                onChannels = {
                    submittedSearch = null
                    section = LiveTvSection.ALL
                    group = null
                    query = term
                    resetList()
                },
                onSearchAgain = {
                    submittedSearch = null
                    searching = true
                },
                onDismiss = { submittedSearch = null }
            )
        }
    }
    eventDetails?.let { event ->
        LiveTvProgrammeDialog(
            event = event,
            viewModel = viewModel,
            recording = recordings.firstOrNull {
                it.profileId == state.profileId && it.sourceKey == state.sourceKey &&
                    it.matches(event.channel, event.programme)
            },
            onWatch = {
                submittedSearch = null
                play(event.channel)
            },
            onPlayArchive = { archive ->
                onPlayArchive(archive).also { if (it) submittedSearch = null }
            },
            onDismiss = { eventDetails = null }
        )
    }
    if (showRecordings) {
        LiveTvRecordingsDialog(viewModel, state.profileId, onPlayArchive) { showRecordings = false }
    }
}

@Composable
private fun LiveTvCategoryRail(
    groups: List<String>,
    state: LazyListState,
    section: LiveTvSection,
    group: String?,
    selectedFocus: FocusRequester,
    firstFocus: FocusRequester,
    onSection: (LiveTvSection) -> Unit,
    onGroup: (String) -> Unit,
    enterChannels: () -> Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = state,
        modifier = modifier.background(GuideRail),
        contentPadding = PaddingValues(start = 10.dp, top = 3.dp, end = 8.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(GuideSections, key = { it.name }) { item ->
            val selected = group == null && section == item
            val label = stringResource(item.label)
            GuideCategoryItem(
                if (item == LiveTvSection.FAVOURITES) "★  $label" else label,
                selected, selectedFocus, firstFocus, enterChannels
            ) { onSection(item) }
        }
        if (groups.isNotEmpty()) {
            item("category-heading") {
                Text(
                    stringResource(R.string.live_tv_guide_categories),
                    modifier = Modifier.padding(start = 8.dp, top = 9.dp, bottom = 3.dp),
                    color = GuideMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        items(groups, key = { "group-$it" }) { item ->
            GuideCategoryItem(item, group == item, selectedFocus, firstFocus, enterChannels) { onGroup(item) }
        }
    }
}

@Composable
private fun GuideCategoryItem(
    label: String,
    selected: Boolean,
    selectedFocus: FocusRequester,
    firstFocus: FocusRequester,
    enterChannels: () -> Boolean,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(20.dp).then(if (selected) Modifier.background(GuideAccent) else Modifier))
        GuideButton(
            label, onClick,
            Modifier.weight(1f)
                .padding(start = 4.dp)
                .then(if (selected) Modifier.focusRequester(selectedFocus).focusRequester(firstFocus) else Modifier)
                .onPreviewKeyEvent {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && enterChannels()
                },
            startAligned = true
        )
    }
}


@Composable
private fun LiveTvSearchDialog(initialQuery: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initialQuery) }
    val input = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.live_tv_search)) {
        InputField(
            value = value,
            onValueChange = { value = it },
            placeholder = stringResource(R.string.live_tv_search_hint),
            modifier = Modifier.fillMaxWidth().focusRequester(input).onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && results.requestFocus()
            },
            imeAction = ImeAction.Done,
            onImeAction = { onSubmit(value) }
        )
        LaunchedEffect(Unit) { input.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = { onSubmit(value) }, modifier = Modifier.focusRequester(results)) {
                Text(stringResource(R.string.live_tv_show_results))
            }
        }
    }
}


@Composable
private fun LiveTvSearchResultsDialog(
    term: String,
    programmes: List<LiveSportsEvent>?,
    channelCount: Int,
    now: Long,
    dayLabel: String,
    onChooseDay: () -> Unit,
    onSelect: (LiveSportsEvent) -> Unit,
    onChannels: () -> Unit,
    onSearchAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember(term) { FocusRequester() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_search_results, term),
        width = 640.dp
    ) {
        val results = programmes.orEmpty()
        GuideButton(stringResource(R.string.live_tv_search_day_value, dayLabel), onChooseDay)
        if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 255.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(vertical = 3.dp)
            ) {
                itemsIndexed(results, key = { index, _ -> index }) { index, event ->
                    val programme = event.programme
                    Button(
                        onClick = { onSelect(event) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        scale = ButtonDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(
                                        R.string.live_tv_guide_event_time,
                                        event.channel.name,
                                        "${formatGuideDate(programme.startsAt)} ${formatProgrammeTime(programme.startsAt)}",
                                        formatProgrammeTime(programme.endsAt)
                                    ),
                                    color = GuideMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (programme.canReplay(event.channel, now)) {
                                Text(stringResource(R.string.live_tv_play_replay), color = GuideAccent)
                            }
                        }
                    }
                }
            }
        } else if (programmes == null || channelCount == 0) {
            Text(
                stringResource(
                    if (programmes == null) R.string.live_tv_search_programmes_loading
                    else R.string.live_tv_search_no_matches
                ),
                color = GuideMuted
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (channelCount > 0) {
                GuideButton(
                    pluralStringResource(R.plurals.live_tv_search_matching_channels, channelCount, channelCount),
                    onChannels,
                    if (results.isEmpty()) Modifier.focusRequester(first) else Modifier
                )
            }
            GuideButton(
                stringResource(R.string.live_tv_search_again), onSearchAgain,
                if (results.isEmpty() && channelCount == 0) Modifier.focusRequester(first) else Modifier
            )
        }
        LaunchedEffect(programmes) {
            withFrameNanos { }
            first.requestFocus()
        }
    }
}
