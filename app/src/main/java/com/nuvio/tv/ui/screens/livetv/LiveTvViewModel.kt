package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.XtreamCatalog
import com.nuvio.tv.data.livetv.XtreamClient
import com.nuvio.tv.data.livetv.XtreamException
import com.nuvio.tv.data.livetv.XtreamProblem
import com.nuvio.tv.data.livetv.XtreamSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

internal data class LiveTvState(
    val catalog: XtreamCatalog? = null,
    val hasSource: Boolean = false,
    val loading: Boolean = false,
    @StringRes val error: Int? = null
)

@HiltViewModel
internal class LiveTvViewModel @Inject constructor(
    @ApplicationContext context: Context,
    profileManager: ProfileManager
) : ViewModel() {
    val profileId = profileManager.activeProfileId.value
    private val storage = ProtectedProfilePreferences(context, "nexus_iptv", "app.nexusmedia.tv.iptv.v1")
    private val client = XtreamClient()
    private var source: XtreamSource? = null
    private val _state = MutableStateFlow(LiveTvState(loading = true))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    storage.read(profileId)?.let {
                        val json = Json.parseToJsonElement(it).jsonObject
                        XtreamSource.from(
                            json.getValue("server").jsonPrimitive.content,
                            json.getValue("username").jsonPrimitive.content,
                            json.getValue("password").jsonPrimitive.content
                        )
                    }
                }.getOrNull()
            }
            source = saved
            _state.value = LiveTvState(hasSource = saved != null)
            saved?.let { load(it, save = false) }
        }
    }

    fun connect(server: String, username: String, password: String): Boolean {
        if (_state.value.loading) return false
        val nextSource = XtreamSource.from(server, username, password)
        if (nextSource == null) {
            _state.update { it.copy(error = R.string.live_tv_invalid_source) }
            return false
        }
        load(nextSource, save = true)
        return true
    }

    fun refresh() {
        source?.let { load(it, save = false) }
    }

    fun streamUrl(channel: LiveChannel): String? {
        val format = _state.value.catalog?.format ?: return null
        return source?.streamUrl(channel.id, format)?.toString()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun disconnect() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { storage.write(profileId, null) }
                source = null
                _state.value = LiveTvState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = R.string.live_tv_storage_error) }
            }
        }
    }

    private fun load(nextSource: XtreamSource, save: Boolean) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val catalog = try {
                withContext(Dispatchers.IO) { client.load(nextSource) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: XtreamException) {
                val message = when (failure.problem) {
                    XtreamProblem.SIGN_IN -> R.string.live_tv_credentials_rejected
                    XtreamProblem.NO_CHANNELS -> R.string.live_tv_no_channels
                    XtreamProblem.RESPONSE -> R.string.live_tv_connection_error
                }
                _state.update { it.copy(loading = false, error = message) }
                return@launch
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = R.string.live_tv_connection_error) }
                return@launch
            }

            if (save) {
                try {
                    withContext(Dispatchers.IO) {
                        val value = buildJsonObject {
                            put("server", nextSource.server.toString())
                            put("username", nextSource.username)
                            put("password", nextSource.password)
                        }
                        storage.write(profileId, value.toString())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(loading = false, error = R.string.live_tv_storage_error) }
                    return@launch
                }
            }
            source = nextSource
            _state.value = LiveTvState(catalog = catalog, hasSource = true)
        }
    }
}
