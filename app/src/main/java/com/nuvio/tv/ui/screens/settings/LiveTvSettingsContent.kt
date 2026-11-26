package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.livetv.LiveTvPlayer
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.livetv.LiveTvViewModel
import com.nuvio.tv.ui.screens.livetv.XtreamPhoneSetupState
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvSettingsContent(viewModel: LiveTvViewModel, initialFocusRequester: FocusRequester?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        onDispose { viewModel.closePhoneSetup() }
    }
    key(state.profileId) {
        var showPlayers by remember { mutableStateOf(false) }
        var connecting by remember { mutableStateOf(false) }
        val playerTitle = stringResource(R.string.live_tv_player)
        val playerHelp = stringResource(R.string.live_tv_player_help)
        val players = listOf(
            SettingsPickerOption(LiveTvPlayer.DEFAULT, stringResource(R.string.live_tv_player_default)),
            SettingsPickerOption(LiveTvPlayer.LIBVLC, "LibVLC")
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsDetailHeader(title = stringResource(R.string.nav_live_tv), subtitle = playerHelp)
            SettingsGroupCard {
                SettingsActionRow(
                    title = playerTitle,
                    subtitle = null,
                    value = players.first { it.value == state.player }.title,
                    onClick = { showPlayers = true },
                    modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                )
            }
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_playlist),
                subtitle = stringResource(
                    state.error ?: when {
                        state.loading && state.hasSource -> R.string.live_tv_refreshing
                        state.loading -> R.string.live_tv_loading
                        state.hasSource -> R.string.live_tv_source_connected
                        else -> R.string.live_tv_xtream_help
                    }
                )
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_setup_phone),
                    subtitle = null,
                    onClick = viewModel::startPhoneSetup,
                    enabled = !state.loading
                )
                SettingsActionRow(
                    title = stringResource(if (state.hasSource) R.string.live_tv_change_source else R.string.live_tv_setup_remote),
                    subtitle = null,
                    onClick = {
                        viewModel.clearError()
                        connecting = true
                    },
                    enabled = !state.loading
                )
                if (state.hasSource) {
                    SettingsActionRow(
                        title = stringResource(R.string.live_tv_refresh),
                        subtitle = null,
                        onClick = viewModel::refresh,
                        enabled = !state.loading
                    )
                }
            }
        }
        if (showPlayers) {
            SettingsSingleChoiceDialog(
                title = playerTitle,
                options = players,
                selectedValue = state.player,
                onOptionSelected = {
                    viewModel.setPlayer(it)
                    showPlayers = false
                },
                onDismiss = { showPlayers = false }
            )
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
        phone?.let {
            XtreamPhoneDialog(it, viewModel::startPhoneSetup, viewModel::savePhonePlaylist, viewModel::closePhoneSetup)
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
