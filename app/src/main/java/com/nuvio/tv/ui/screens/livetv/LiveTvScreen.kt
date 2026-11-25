package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = state.catalog?.channels.orEmpty()
    val groups = remember(channels) { channels.mapNotNull { it.group }.distinct().sorted() }
    var sports by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val activeGroup = group?.takeIf { it in groups }
    val visible = remember(channels, sports, activeGroup) {
        channels.filter { (activeGroup == null || it.group == activeGroup) && (!sports || it.isSports) }
    }
    val firstFocus = LocalContentFocusRequester.current

    LaunchedEffect(state.loading) {
        if (!state.loading) firstFocus.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 38.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    viewModel.clearError()
                    connecting = true
                },
                modifier = Modifier.focusRequester(firstFocus),
                enabled = !state.loading
            ) {
                Text(stringResource(if (state.hasSource) R.string.live_tv_change_source else R.string.live_tv_add_source))
            }
            if (state.hasSource) {
                Button(onClick = viewModel::refresh, enabled = !state.loading) {
                    Text(stringResource(R.string.live_tv_refresh))
                }
            }
        }
        if (state.loading) {
            Text(stringResource(R.string.live_tv_loading), color = NuvioTheme.colors.TextSecondary)
        }
        state.error?.let { Text(stringResource(it), color = NuvioTheme.colors.TextPrimary) }

        if (channels.isEmpty() && !state.loading && state.error == null) {
            Text(stringResource(R.string.live_tv_empty), color = NuvioTheme.colors.TextSecondary)
        }
        if (channels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.width(228.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        LiveTvFilter(stringResource(R.string.live_tv_all), !sports && activeGroup == null) {
                            sports = false
                            group = null
                        }
                    }
                    item {
                        LiveTvFilter(stringResource(R.string.live_tv_sports), sports) {
                            sports = true
                            group = null
                        }
                    }
                    items(groups, key = { it }) { name ->
                        LiveTvFilter(name, activeGroup == name) {
                            sports = false
                            group = name
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (visible.isEmpty()) {
                        item { Text(stringResource(R.string.live_tv_no_sports), color = NuvioTheme.colors.TextSecondary) }
                    }
                    items(visible) { channel ->
                        Button(
                            onClick = { onPlay(channel) },
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
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
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
