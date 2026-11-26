package com.nuvio.tv.ui.screens.livetv

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

private enum class LiveTvSection(val label: Int) {
    ALL(R.string.live_tv_all),
    FAVOURITES(R.string.live_tv_favourites),
    RECENT(R.string.live_tv_recent),
    GUIDE(R.string.live_tv_guide),
    SPORTS(R.string.live_tv_sports)
}

private val GuideSections = listOf(LiveTvSection.FAVOURITES, LiveTvSection.ALL, LiveTvSection.RECENT, LiveTvSection.SPORTS)

@Composable
internal fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel, List<LiveChannel>) -> Boolean,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.setLiveTvVisible(true)
        onDispose { viewModel.setLiveTvVisible(false) }
    }
    val firstFocus = LocalContentFocusRequester.current
    if (state.catalog?.channels.isNullOrEmpty()) {
        LiveTvEmptyGuide(state, firstFocus, onOpenSettings)
    } else {
        key(state.profileId, state.sourceKey) {
            LiveTvGuideContent(state, viewModel, firstFocus, onPlay)
        }
    }
}

@Composable
internal fun LiveTvChannelPicker(
    viewModel: LiveTvViewModel,
    currentId: Long,
    onPlay: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        key(state.profileId, state.sourceKey, currentId) {
            LiveTvGuideContent(
                state, viewModel, firstFocus,
                onPlay = { channel, _ ->
                    if (channel.id == currentId) onDismiss() else onPlay(channel)
                    true
                },
                initialChannelId = currentId,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun LiveTvEmptyGuide(state: LiveTvState, firstFocus: FocusRequester, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(GuideRail), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(stringResource(R.string.live_tv_guide), style = MaterialTheme.typography.headlineLarge)
            val message = state.error ?: if (state.loading) R.string.live_tv_loading else R.string.live_tv_empty
            Text(stringResource(message), color = GuideMuted)
            if (!state.loading) {
                GuideButton(stringResource(R.string.live_tv_open_settings), onOpenSettings, Modifier.focusRequester(firstFocus))
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
    onPlay: (LiveChannel, List<LiveChannel>) -> Boolean,
    initialChannelId: Long? = null,
    onDismiss: (() -> Unit)? = null
) {
    val channels = state.catalog?.channels.orEmpty()
    val channelIndex = remember(channels) { channels.associateBy { it.id } }
    val groups = remember(channels) { channels.mapNotNull { it.group }.distinct() }
    val sports = remember(channels) { channels.filter { it.isSports } }
    val favouriteIds = remember(state.history.favourites) { state.history.favourites.toHashSet() }
    var section by rememberSaveable { mutableStateOf(LiveTvSection.ALL) }
    var group by rememberSaveable { mutableStateOf(channelIndex[initialChannelId]?.group) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var eventDetails by remember { mutableStateOf<LiveSportsEvent?>(null) }
    var categoriesVisible by rememberSaveable { mutableStateOf(initialChannelId == null) }
    var restorePlaybackFocus by rememberSaveable { mutableStateOf(false) }
    var placedFocus by remember { mutableStateOf(false) }
    val restoreId = remember { initialChannelId ?: state.lastTunedChannelId.takeIf { restorePlaybackFocus } }

    val items = remember(channels, channelIndex, sports, section, group, query, state.history) {
        val filtered = when {
            group != null -> channels.filter { it.group == group }
            section == LiveTvSection.FAVOURITES -> state.history.favourites.mapNotNull(channelIndex::get)
            section == LiveTvSection.RECENT -> state.history.recent.mapNotNull(channelIndex::get)
            section == LiveTvSection.SPORTS -> sports
            else -> channels
        }
        filtered.filter { it.matchesLiveTvSearch(query) }
    }
    val rowState = rememberLazyListState()
    val railState = rememberLazyListState()
    val categoryFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var railFocusRequest by remember { mutableIntStateOf(0) }
    var highlightedId by remember { mutableStateOf(restoreId) }
    var browsingTime by rememberSaveable { mutableLongStateOf(0L) }
    var windowAnchor by rememberSaveable { mutableLongStateOf(0L) }
    val now = liveGuideClock()
    val targetRow = highlightedId?.let { highlighted -> items.firstOrNull { it.id == highlighted } } ?: items.firstOrNull()
    val programmeTime = browsingTime.takeIf { it != 0L } ?: now
    val highlighted = targetRow?.takeIf { highlightedId == it.id }
    val currentProgrammes = state.guide[highlighted?.id].orEmpty()
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
        if (onPlay(channel, items)) restorePlaybackFocus = true
    }

    fun select(channel: LiveChannel, programme: LiveProgramme?) {
        if (programme == null || programme.isOnAir(now)) {
            play(channel)
        } else {
            eventDetails = LiveSportsEvent(channel, programme)
        }
    }

    LaunchedEffect(section, group, query) {
        if (placedFocus) rowState.scrollToItem(0)
    }
    LaunchedEffect(groups) {
        if (group != null && group !in groups) group = null
    }
    LaunchedEffect(items, restoreId) {
        if (placedFocus || items.isEmpty() && restoreId != null && state.loading) return@LaunchedEffect
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
    LaunchedEffect(rowState, state.sourceKey, now / 60_000L) {
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .collectLatest { ids ->
                if (ids.isNotEmpty()) {
                    delay(160)
                    viewModel.requestGuide(ids, full = true)
                }
            }
    }
    val showSports = section == LiveTvSection.SPORTS && group == null && highlighted == null
    val sportsSeeds = remember(sports) { sports.take(12).map { it.id } }
    LaunchedEffect(showSports, state.sourceKey, sportsSeeds, now / 60_000L) {
        if (showSports) viewModel.requestGuide(sportsSeeds, full = true)
    }
    val featured = remember(showSports, sports, state.guide, now) {
        if (showSports) featuredSportsEvents(sports, state.guide, now, 6) else emptyList()
    }
    BackHandler(!categoriesVisible && !searching && eventDetails == null) { showCategories() }

    BoxWithConstraints(Modifier.fillMaxSize().background(GuideRail)) {
        val detailsHeight = maxHeight * .405f
        val railWidth = maxWidth * .244f
        val channelWidth = (maxWidth * .273f).coerceAtMost(272.dp)
        val timelineWidth = (maxWidth - channelWidth - if (categoriesVisible) railWidth else 0.dp).coerceAtLeast(1.dp)
        val halfHourWidth = (maxWidth * .18f).coerceIn(128.dp, 184.dp)
        val windowDuration = (timelineWidth.value.toDouble() / halfHourWidth.value * GUIDE_STEP_MS)
            .roundToLong().coerceAtLeast(1L)
        val window = GuideWindow(windowAnchor.takeIf { it != 0L } ?: guideTimeFloor(now), windowDuration, timelineWidth)
        val bounds = remember(currentProgrammes, state.guide, now) {
            val listings = if (currentProgrammes.isEmpty()) state.guide.values else listOf(currentProgrammes)
            val earliest = listings.minOfOrNull { it.firstOrNull()?.startsAt ?: now } ?: now
            val latest = listings.maxOfOrNull { it.lastOrNull()?.endsAt ?: now } ?: now
            guideTimeFloor(minOf(earliest, now)) to maxOf(latest, now)
        }
        val pageDuration = (window.duration / GUIDE_STEP_MS).coerceAtLeast(1L) * GUIDE_STEP_MS

        fun reveal(time: Long) {
            browsingTime = time
            if (time !in window.start until window.end) windowAnchor = guideTimeFloor(time)
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
                canGoEarlier = window.start > bounds.first,
                canGoLater = window.end < bounds.second,
                categoriesVisible = categoriesVisible,
                onSearch = { searching = true },
                onNow = ::clearTimeSelection,
                onEarlier = { page(false) },
                onLater = { page(true) },
                onCategories = ::showCategories,
                onWatch = highlighted?.let { channel -> { play(channel) } },
                onDismiss = onDismiss,
                modifier = Modifier.height(detailsHeight)
            ) {
                if (showSports && featured.isNotEmpty()) {
                    LiveTvSportsStrip(featured, now) { event -> select(event.channel, event.programme) }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (categoriesVisible) {
                    LiveTvCategoryRail(
                        groups, railState, sectionKey, group, categoryFocus, firstFocus,
                        onSection = { selected ->
                            section = selected
                            group = null
                            query = ""
                            resetList()
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
                                        section == LiveTvSection.SPORTS -> R.string.live_tv_no_sports
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
                                    programmes = state.guide[channel.id].orEmpty(),
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

    if (searching) {
        LiveTvSearchDialog(
            initialQuery = query,
            onSubmit = {
                section = LiveTvSection.ALL
                group = null
                query = it
                searching = false
                resetList()
            },
            onDismiss = { searching = false }
        )
    }
    eventDetails?.let { event ->
        LiveTvEventDialog(
            event,
            onWatch = {
                eventDetails = null
                play(event.channel)
            },
            onDismiss = { eventDetails = null }
        )
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
