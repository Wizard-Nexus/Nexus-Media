@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import kotlinx.coroutines.launch

@Composable
fun TmdbSettingsScreen(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.tmdb_title),
        subtitle = stringResource(R.string.tmdb_subtitle)
    ) {
        TmdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val hasLocalKey by viewModel.hasLocalApiKey.collectAsStateWithLifecycle()
    val hasApiKey = hasLocalKey || viewModel.hasBuiltInApiKey

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.tmdb_title),
            subtitle = stringResource(R.string.tmdb_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val tmdbListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = tmdbListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "tmdb_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_enable_title),
                        subtitle = stringResource(R.string.tmdb_enable_subtitle),
                        checked = uiState.enabled && hasApiKey,
                        onToggle = {
                            if (hasApiKey) viewModel.onEvent(TmdbSettingsEvent.ToggleEnabled(!uiState.enabled))
                            else showApiKeyDialog = true
                        },
                        modifier = Modifier
                            .padding(top = NuvioTheme.spacing.xxs)
                            .then(
                                if (initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                })
                    )
                }

                item(key = "tmdb_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.nexus_tmdb_key_title),
                        subtitle = stringResource(R.string.nexus_tmdb_key_help),
                        value = stringResource(if (hasApiKey) R.string.nexus_tmdb_key_connected else R.string.nexus_tmdb_key_missing),
                        onClick = { showApiKeyDialog = true }
                    )
                }

                item(key = "tmdb_modern_home_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_modern_home_title),
                        subtitle = stringResource(R.string.tmdb_modern_home_subtitle),
                        checked = uiState.modernHomeEnabled,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleModernHomeEnabled(!uiState.modernHomeEnabled)
                            )
                        }
                    )
                }

                item(key = "tmdb_enrich_continue_watching") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_enrich_continue_watching_title),
                        subtitle = stringResource(R.string.tmdb_enrich_continue_watching_subtitle),
                        checked = uiState.enrichContinueWatching,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleEnrichContinueWatching(!uiState.enrichContinueWatching)
                            )
                        }
                    )
                }

                item(key = "tmdb_language") {
                    val languageName = AVAILABLE_TMDB_LANGUAGES
                        .find { it.code == uiState.language }
                        ?.displayName
                        ?: uiState.language.uppercase()
                    SettingsActionRow(
                        title = stringResource(R.string.tmdb_language_title),
                        subtitle = stringResource(R.string.tmdb_language_subtitle),
                        value = languageName,
                        enabled = uiState.enabled,
                        onClick = { showLanguageDialog = true }
                    )
                }

                item(key = "tmdb_artwork") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_artwork_title),
                        subtitle = stringResource(R.string.tmdb_artwork_subtitle),
                        checked = uiState.useArtwork,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
                    )
                }

                item(key = "tmdb_basic_info") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_basic_info_title),
                        subtitle = stringResource(R.string.tmdb_basic_info_subtitle),
                        checked = uiState.useBasicInfo,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
                    )
                }

                item(key = "tmdb_details") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_details_title),
                        subtitle = stringResource(R.string.tmdb_details_subtitle),
                        checked = uiState.useDetails,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDetails(!uiState.useDetails)) }
                    )
                }

                item(key = "tmdb_credits") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_credits_title),
                        subtitle = stringResource(R.string.tmdb_credits_subtitle),
                        checked = uiState.useCredits,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
                    )
                }

                item(key = "tmdb_productions") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_productions_title),
                        subtitle = stringResource(R.string.tmdb_productions_subtitle),
                        checked = uiState.useProductions,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleProductions(!uiState.useProductions)) }
                    )
                }

                item(key = "tmdb_networks") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_networks_title),
                        subtitle = stringResource(R.string.tmdb_networks_subtitle),
                        checked = uiState.useNetworks,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleNetworks(!uiState.useNetworks)) }
                    )
                }

                item(key = "tmdb_episodes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_episodes_title),
                        subtitle = stringResource(R.string.tmdb_episodes_subtitle),
                        checked = uiState.useEpisodes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
                    )
                }

                item(key = "tmdb_season_posters") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_season_posters_title),
                        subtitle = stringResource(R.string.tmdb_season_posters_subtitle),
                        checked = uiState.useSeasonPosters,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleSeasonPosters(!uiState.useSeasonPosters)) }
                    )
                }

                if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                    item(key = "tmdb_trailers") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tmdb_trailers_title),
                            subtitle = stringResource(R.string.tmdb_trailers_subtitle),
                            checked = uiState.useTrailers,
                            enabled = uiState.enabled,
                            onToggle = {
                                viewModel.onEvent(
                                    TmdbSettingsEvent.ToggleTrailers(!uiState.useTrailers)
                                )
                            }
                        )
                    }
                }

                item(key = "tmdb_more_like_this") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_more_like_this_title),
                        subtitle = stringResource(R.string.tmdb_more_like_this_subtitle),
                        checked = uiState.useMoreLikeThis,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleMoreLikeThis(!uiState.useMoreLikeThis)
                            )
                        }
                    )
                }

                item(key = "tmdb_collections") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_collections_title),
                        subtitle = stringResource(R.string.tmdb_collections_subtitle),
                        checked = uiState.useCollections,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleCollections(!uiState.useCollections)
                            )
                        }
                    )
                }

            }
            SettingsVerticalScrollIndicators(state = tmdbListState)
            }
        }
    }

    if (showApiKeyDialog) {
        TmdbApiKeyDialog(
            hasLocalKey = hasLocalKey,
            onSave = viewModel::saveApiKey,
            onRemove = viewModel::removeApiKey,
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.tmdb_language_dialog_title),
            selectedLanguage = uiState.language,
            showNoneOption = false,
            onLanguageSelected = { language ->
                viewModel.onEvent(TmdbSettingsEvent.SetLanguage(language ?: "en"))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun TmdbApiKeyDialog(
    hasLocalKey: Boolean,
    onSave: suspend (String) -> Int?,
    onRemove: suspend () -> Int?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var key by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun submit(operation: suspend () -> Int?) {
        if (working) return
        working = true
        message = null
        scope.launch {
            try {
                message = operation()
                if (message == null) onDismiss()
            } finally {
                working = false
            }
        }
    }

    NuvioDialog(
        onDismiss = { if (!working) onDismiss() },
        title = stringResource(R.string.nexus_tmdb_key_title),
        subtitle = stringResource(R.string.nexus_tmdb_key_help)
    ) {
        InputField(
            value = key,
            onValueChange = { key = it },
            placeholder = stringResource(R.string.nexus_tmdb_key_placeholder),
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = { if (key.isNotBlank()) saveFocus.requestFocus() },
            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus)
        )
        val status = message ?: R.string.nexus_tmdb_key_verifying.takeIf { working }
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = { submit { onSave(key) } }, enabled = !working && key.isNotBlank(), modifier = Modifier.focusRequester(saveFocus)) {
                Text(stringResource(R.string.nexus_tmdb_key_save))
            }
            if (hasLocalKey) {
                Button(onClick = { submit(onRemove) }, enabled = !working) {
                    Text(stringResource(R.string.nexus_tmdb_key_remove))
                }
            }
        }
        LaunchedEffect(Unit) { inputFocus.requestFocus() }
    }
}
