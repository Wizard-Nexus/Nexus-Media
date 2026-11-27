package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val dataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val metaRepository: MetaRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val tmdb: TmdbApi,
    private val credentials: TmdbCredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TmdbSettingsUiState())
    val uiState: StateFlow<TmdbSettingsUiState> = _uiState.asStateFlow()
    private val _hasLocalApiKey = MutableStateFlow(false)
    val hasLocalApiKey = _hasLocalApiKey.asStateFlow()
    val hasBuiltInApiKey = BuildConfig.TMDB_API_KEY.isNotBlank()

    init {
        viewModelScope.launch {
            _hasLocalApiKey.value = withContext(Dispatchers.IO) { credentials.localKey() != null }
        }
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    suspend fun saveApiKey(value: String): Int? {
        val key = value.trim()
        if (key.isEmpty() || key.any(Char::isWhitespace)) return R.string.nexus_tmdb_key_invalid
        val response = try {
            tmdb.verifyApiKey(key)
        } catch (_: IOException) {
            return R.string.nexus_tmdb_key_connection_error
        }
        response.body()?.close()
        response.errorBody()?.close()
        if (!response.isSuccessful) {
            return if (response.code() == 401 || response.code() == 403) R.string.nexus_tmdb_key_invalid
            else R.string.nexus_tmdb_key_connection_error
        }
        return updateKey(key)
    }

    suspend fun removeApiKey(): Int? = updateKey(null)

    private suspend fun updateKey(key: String?): Int? {
        val saved = withContext(Dispatchers.IO) { runCatching { credentials.setLocalKey(key) }.isSuccess }
        if (!saved) return R.string.nexus_tmdb_key_save_error
        _hasLocalApiKey.value = key != null
        try {
            dataStore.setEnabled(key != null || hasBuiltInApiKey)
        } catch (_: IOException) {
            return R.string.nexus_tmdb_key_settings_error
        }
        metaRepository.clearCache()
        trailerService.clearCache()
        return null
    }

    fun onEvent(event: TmdbSettingsEvent) {
        when (event) {
            is TmdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TmdbSettingsEvent.ToggleModernHomeEnabled -> {
                update { dataStore.setModernHomeEnabled(event.enabled) }
            }
            is TmdbSettingsEvent.ToggleEnrichContinueWatching -> {
                update { dataStore.setEnrichContinueWatching(event.enabled) }
            }
            is TmdbSettingsEvent.SetLanguage -> update {
                val newLanguage = event.language.ifBlank { "en" }
                val currentLanguage = _uiState.value.language.ifBlank { "en" }
                dataStore.setLanguage(newLanguage)
                if (!newLanguage.equals(currentLanguage, ignoreCase = true)) {
                    trailerService.clearCache()
                }
            }
            is TmdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TmdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TmdbSettingsEvent.ToggleDetails -> update { dataStore.setUseDetails(event.enabled) }
            is TmdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TmdbSettingsEvent.ToggleProductions -> update { dataStore.setUseProductions(event.enabled) }
            is TmdbSettingsEvent.ToggleNetworks -> update { dataStore.setUseNetworks(event.enabled) }
            is TmdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TmdbSettingsEvent.ToggleSeasonPosters -> update { dataStore.setUseSeasonPosters(event.enabled) }
            is TmdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TmdbSettingsEvent.ToggleMoreLikeThis -> update { dataStore.setUseMoreLikeThis(event.enabled) }
            is TmdbSettingsEvent.ToggleCollections -> update { dataStore.setUseCollections(event.enabled) }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class TmdbSettingsUiState(
    val enabled: Boolean = false,
    val modernHomeEnabled: Boolean = false,
    val enrichContinueWatching: Boolean = true,
    val language: String = "en",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useSeasonPosters: Boolean = false,
    val useTrailers: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true
) {
    fun fromSettings(settings: TmdbSettings): TmdbSettingsUiState = copy(
        enabled = settings.enabled,
        modernHomeEnabled = settings.modernHomeEnabled,
        enrichContinueWatching = settings.enrichContinueWatching,
        language = settings.language,
        useArtwork = settings.useArtwork,
        useBasicInfo = settings.useBasicInfo,
        useDetails = settings.useDetails,
        useCredits = settings.useCredits,
        useProductions = settings.useProductions,
        useNetworks = settings.useNetworks,
        useEpisodes = settings.useEpisodes,
        useSeasonPosters = settings.useSeasonPosters,
        useTrailers = settings.useTrailers,
        useMoreLikeThis = settings.useMoreLikeThis,
        useCollections = settings.useCollections
    )
}

sealed class TmdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleModernHomeEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEnrichContinueWatching(val enabled: Boolean) : TmdbSettingsEvent()
    data class SetLanguage(val language: String) : TmdbSettingsEvent()
    data class ToggleArtwork(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleBasicInfo(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleDetails(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCredits(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleProductions(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleNetworks(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEpisodes(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleSeasonPosters(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleTrailers(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleMoreLikeThis(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCollections(val enabled: Boolean) : TmdbSettingsEvent()
}
