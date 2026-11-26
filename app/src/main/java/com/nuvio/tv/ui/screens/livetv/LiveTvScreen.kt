package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.data.livetv.nowAndNext
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

private enum class LiveTvSection(val label: Int) {
    ALL(R.string.live_tv_all),
    FAVOURITES(R.string.live_tv_favourites),
    RECENT(R.string.live_tv_recent),
    GUIDE(R.string.live_tv_guide),
    SPORTS(R.string.live_tv_sports)
}

@Composable
internal fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel) -> Boolean
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.setLiveTvVisible(true)
        onDispose {
            viewModel.setLiveTvVisible(false)
            viewModel.closePhoneSetup()
        }
    }
    key(state.profileId) { LiveTvContent(state, viewModel, onPlay) }
    phone?.let { XtreamPhoneDialog(it, viewModel::startPhoneSetup, viewModel::savePhonePlaylist, viewModel::closePhoneSetup) }
}

@Composable
private fun LiveTvContent(
    state: LiveTvState,
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel) -> Boolean
) {
    val channels = state.catalog?.channels.orEmpty()
    val groups = remember(channels) { channels.mapNotNull { it.group }.distinct().sorted() }
    var section by rememberSaveable { mutableStateOf(LiveTvSection.ALL) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var setupMenu by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var eventDetails by remember { mutableStateOf<LiveSportsEvent?>(null) }
    var restoreChannelOnReturn by rememberSaveable { mutableStateOf(false) }
    val restoreOnEntry = remember { restoreChannelOnReturn }
    var initialFocusPlaced by remember { mutableStateOf(false) }
    val firstFocus = LocalContentFocusRequester.current
    val channelFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val now = liveGuideClock()
    val channelsById = remember(channels) { channels.associateBy { it.id } }
    val sportsChannels = remember(channels) { channels.filter { it.isSports } }
    val candidates = remember(channels, channelsById, section, state.history, group) {
        when {
            group != null -> channels.filter { it.group == group }
            section == LiveTvSection.FAVOURITES -> state.history.favourites.mapNotNull(channelsById::get)
            section == LiveTvSection.RECENT -> state.history.recent.mapNotNull(channelsById::get)
            section == LiveTvSection.SPORTS -> sportsChannels
            else -> channels
        }
    }
    val visible = remember(candidates, query) { candidates.filter { it.matchesLiveTvSearch(query) } }
    val restoreId = state.lastTunedChannelId.takeIf { restoreOnEntry && !initialFocusPlaced }
    val showSports = section == LiveTvSection.SPORTS && group == null
    val sportsSeedIds = remember(sportsChannels) { sportsChannels.take(12).map { it.id } }
    val events = remember(showSports, sportsChannels, state.guide, now) {
        if (showSports) featuredSportsEvents(sportsChannels, state.guide, now, 6) else emptyList()
    }
    val play: (LiveChannel) -> Unit = { channel ->
        if (onPlay(channel)) restoreChannelOnReturn = true
    }

    LaunchedEffect(section, query, group, channels.isNotEmpty()) {
        if ((!restoreOnEntry || initialFocusPlaced) && channels.isNotEmpty()) listState.scrollToItem(0)
    }
    LaunchedEffect(restoreId, visible, state.loading) {
        if (initialFocusPlaced || (state.loading && channels.isEmpty())) return@LaunchedEffect
        if (restoreId != null && restoreId in channelsById) {
            val index = visible.indexOfFirst { it.id == restoreId }
            if (index < 0) {
                section = LiveTvSection.ALL
                group = null
                query = ""
                return@LaunchedEffect
            }
            listState.scrollToItem(index)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == restoreId } }.first { it }
            withFrameNanos { }
            runCatching { channelFocus.requestFocus() }
        } else {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
        initialFocusPlaced = true
        restoreChannelOnReturn = false
    }
    LaunchedEffect(listState, state.sourceKey, now / 60_000) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .collectLatest { ids ->
                if (ids.isNotEmpty()) {
                    delay(180)
                    viewModel.requestGuide(ids)
                }
            }
    }
    LaunchedEffect(showSports, state.sourceKey, sportsSeedIds, now / 60_000) {
        if (showSports) viewModel.requestGuide(sportsSeedIds)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.nav_live_tv), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            if (channels.isNotEmpty()) {
                Button(onClick = { searching = true }) { Text(stringResource(R.string.live_tv_search)) }
            }
            Button(
                onClick = {
                    viewModel.clearError()
                    setupMenu = true
                },
                modifier = Modifier.focusRequester(firstFocus),
                enabled = !state.loading
            ) {
                Text(stringResource(if (state.hasSource) R.string.live_tv_playlist else R.string.live_tv_add_source))
            }
            if (state.hasSource) {
                Button(onClick = viewModel::refresh, enabled = !state.loading) { Text(stringResource(R.string.live_tv_refresh)) }
            }
        }
        val status = state.error ?: if (state.loading) {
            if (channels.isEmpty()) R.string.live_tv_loading else R.string.live_tv_refreshing
        } else null
        status?.let { Text(stringResource(it), color = NuvioTheme.colors.TextSecondary) }

        if (channels.isEmpty()) {
            if (!state.loading && state.error == null) {
                Text(stringResource(R.string.live_tv_empty), color = NuvioTheme.colors.TextSecondary)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.width(178.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(LiveTvSection.entries, key = { it.name }) { next ->
                        LiveTvFilter(stringResource(next.label), section == next && group == null) {
                            section = next
                            group = null
                        }
                    }
                    items(groups, key = { "group-$it" }) { next ->
                        LiveTvFilter(next, group == next) { group = next }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (query.isBlank()) group ?: stringResource(section.label)
                            else stringResource(R.string.live_tv_search_results, query.trim()),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (query.isNotBlank()) {
                            Button(onClick = { query = "" }) { Text(stringResource(R.string.live_tv_clear_search)) }
                        }
                    }
                    if (showSports) {
                        LiveTvSportsStrip(events, now, R.string.live_tv_sports_guide_empty) { event ->
                            if (event.programme.isOnAir(now)) play(event.channel) else eventDetails = event
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(4.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (visible.isEmpty()) {
                            item {
                                val empty = when {
                                    query.isNotBlank() -> R.string.live_tv_search_empty
                                    section == LiveTvSection.FAVOURITES -> R.string.live_tv_favourites_empty
                                    section == LiveTvSection.RECENT -> R.string.live_tv_recent_empty
                                    section == LiveTvSection.SPORTS -> R.string.live_tv_no_sports
                                    else -> R.string.live_tv_no_matching_channels
                                }
                                Text(stringResource(empty), color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                        items(visible, key = { it.id }) { channel ->
                            val (current, next) = state.guide[channel.id].orEmpty().nowAndNext(now)
                            LiveTvChannelRow(
                                channel = channel,
                                current = current,
                                next = next,
                                favourite = channel.id in state.history.favourites,
                                showGuide = section == LiveTvSection.GUIDE && group == null,
                                modifier = if (channel.id == restoreId) Modifier.focusRequester(channelFocus) else Modifier,
                                onPlay = { play(channel) },
                                onFavourite = { viewModel.toggleFavourite(channel.id) }
                            )
                        }
                    }
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
            },
            onDismiss = { searching = false }
        )
    }
    if (setupMenu) {
        NuvioDialog(
            onDismiss = { setupMenu = false },
            title = stringResource(R.string.live_tv_xtream_title)
        ) {
            Button(onClick = {
                setupMenu = false
                viewModel.startPhoneSetup()
            }) { Text(stringResource(R.string.live_tv_setup_phone)) }
            Button(onClick = {
                setupMenu = false
                connecting = true
            }) { Text(stringResource(R.string.live_tv_setup_remote)) }
        }
    }
    if (connecting) {
        XtreamSourceDialog(
            hasSource = state.hasSource,
            error = state.error,
            onConnect = { server, username, password ->
                if (viewModel.connect(server, username, password)) connecting = false
            },
            onRemove = {
                connecting = false
                viewModel.disconnect()
            },
            onDismiss = {
                connecting = false
                viewModel.clearError()
            }
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
private fun LiveTvChannelRow(
    channel: LiveChannel,
    current: LiveProgramme?,
    next: LiveProgramme?,
    favourite: Boolean,
    showGuide: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onFavourite: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPlay,
            modifier = modifier.weight(1f).heightIn(min = 54.dp),
            scale = ButtonDefaults.scale(focusedScale = 1f)
        ) {
            if (showGuide) {
                Text(channel.name, modifier = Modifier.weight(.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) { GuideCell(R.string.live_tv_now, current) }
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) { GuideCell(R.string.live_tv_next, next) }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current?.let {
                        Text(
                            stringResource(R.string.live_tv_now_line, it.title),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        val description = stringResource(
            if (favourite) R.string.live_tv_unfavourite_channel else R.string.live_tv_favourite_channel, channel.name
        )
        Button(
            onClick = onFavourite,
            modifier = Modifier.semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            scale = ButtonDefaults.scale(focusedScale = 1f)
        ) { Text(if (favourite) "★" else "☆", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun GuideCell(label: Int, programme: LiveProgramme?) {
    Text(
        if (programme == null) stringResource(label) else stringResource(
            R.string.live_tv_guide_label_time, stringResource(label), formatProgrammeTime(programme.startsAt)
        ),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1
    )
    Text(
        programme?.title ?: stringResource(R.string.live_tv_guide_unavailable),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun LiveTvFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
private fun XtreamPhoneDialog(
    state: XtreamPhoneSetupState,
    onRestart: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val count = state.channelCount
    val qr = remember(state.url) { state.url?.let { QrCodeGenerator.generate(it, 512) } }
    val saveFocus = remember { FocusRequester() }
    NuvioDialog(
        title = stringResource(R.string.live_tv_setup_phone),
        subtitle = stringResource(R.string.live_tv_phone_help),
        onDismiss = onDismiss,
        width = 560.dp,
        contentSpacing = 10.dp
    ) {
        if (count == null) {
            qr?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = stringResource(R.string.live_tv_phone_qr),
                    modifier = Modifier.size(164.dp).align(Alignment.CenterHorizontally)
                )
            }
        }
        when {
            count != null -> Text(pluralStringResource(R.plurals.live_tv_phone_verified, count, count))
            state.testing -> Text(stringResource(R.string.live_tv_phone_testing))
            state.message != null -> Text(stringResource(state.message), color = NuvioTheme.colors.TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            if (count != null) {
                Button(onClick = onSave, modifier = Modifier.focusRequester(saveFocus)) {
                    Text(stringResource(R.string.live_tv_phone_save))
                }
                LaunchedEffect(Unit) { saveFocus.requestFocus() }
            } else if (state.url == null && state.message != null) {
                Button(onClick = onRestart) { Text(stringResource(R.string.live_tv_phone_new_code)) }
            }
        }
    }
}

@Composable
private fun XtreamSourceDialog(
    hasSource: Boolean,
    error: Int?,
    onConnect: (String, String, String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_xtream_title),
        subtitle = stringResource(R.string.live_tv_xtream_help),
        width = 600.dp,
        contentSpacing = 10.dp
    ) {
        val serverFocus = remember { FocusRequester() }
        val usernameFocus = remember { FocusRequester() }
        val passwordFocus = remember { FocusRequester() }
        val connectFocus = remember { FocusRequester() }

        fun fieldNavigation(
            target: FocusRequester,
            up: FocusRequester?,
            down: FocusRequester
        ): Modifier = Modifier.fillMaxWidth().focusRequester(target).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> up?.requestFocus() ?: false
                Key.DirectionDown -> down.requestFocus()
                else -> false
            }
        }

        InputField(
            value = server,
            onValueChange = { server = it },
            placeholder = stringResource(R.string.live_tv_server),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            isPassword = server.contains("password=", ignoreCase = true),
            onImeAction = { usernameFocus.requestFocus() },
            modifier = fieldNavigation(serverFocus, null, usernameFocus)
        )
        InputField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.live_tv_username),
            imeAction = ImeAction.Next,
            onImeAction = { passwordFocus.requestFocus() },
            modifier = fieldNavigation(usernameFocus, serverFocus, passwordFocus)
        )
        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.live_tv_password),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            onImeAction = { connectFocus.requestFocus() },
            modifier = fieldNavigation(passwordFocus, usernameFocus, connectFocus)
        )
        error?.let { Text(stringResource(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            if (hasSource) {
                Button(onClick = onRemove) { Text(stringResource(R.string.live_tv_remove_source)) }
            }
            Button(
                onClick = { onConnect(server, username, password) },
                modifier = Modifier.focusRequester(connectFocus)
            ) {
                Text(stringResource(R.string.live_tv_connect))
            }
        }
    }
}
