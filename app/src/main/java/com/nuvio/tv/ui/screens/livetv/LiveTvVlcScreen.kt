package com.nuvio.tv.ui.screens.livetv

import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewStub
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.nowAndNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.R as VlcResources
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private enum class VlcPlayback { OPENING, PLAYING, PAUSED, FAILED }

@Composable
internal fun LiveTvVlcScreen(
    viewModel: LiveTvViewModel,
    profileId: Int,
    initialChannelId: Long,
    onExit: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    if (state.profileId != profileId) {
        LaunchedEffect(profileId, state.profileId) { onExit() }
        return
    }

    var channelId by rememberSaveable { mutableStateOf(initialChannelId) }
    val channelIndex = remember(state.catalog) { state.catalog?.channels.orEmpty().associateBy { it.id } }
    val current = channelIndex[channelId]
    val streamUrl = remember(profileId, channelId, state.sourceKey, state.catalog) {
        viewModel.streamUrl(profileId, channelId)
    }
    val previous = state.history.previousChannel(channelId, channelIndex::containsKey)?.let(channelIndex::get)
    val currentProgramme = state.guide[channelId].orEmpty().nowAndNext(liveGuideClock()).first
    val context = LocalContext.current.applicationContext
    val playbackLock = remember { Mutex() }
    val playerFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var showControls by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var menuInteraction by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var nativePlayer by remember(streamUrl, retry) { mutableStateOf<MediaPlayer?>(null) }
    var playback by remember(streamUrl, retry) { mutableStateOf(VlcPlayback.OPENING) }

    fun tune(channel: LiveChannel) {
        if (viewModel.tune(channel) != null) {
            channelId = channel.id
            showPicker = false
            showControls = false
        }
    }

    LaunchedEffect(channelId, state.sourceKey) { viewModel.requestGuide(listOf(channelId)) }
    LaunchedEffect(layout, foreground, streamUrl, retry, state.loading && current == null) {
        val surface = layout ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        if (streamUrl == null) {
            playback = if (state.loading) VlcPlayback.OPENING else VlcPlayback.FAILED
            return@LaunchedEffect
        }
        val playingChannelId = channelId
        // Finish releasing the old channel before opening another provider connection.
        playbackLock.withLock {
            playback = VlcPlayback.OPENING
            var library: LibVLC? = null
            var engine: MediaPlayer? = null
            var attached = false
            try {
                withContext(Dispatchers.IO) {
                    // LibVLC appends device-specific audio and chroma options to this list.
                    val vlc = LibVLC(context, arrayListOf("--quiet", "--no-video-title-show"))
                    library = vlc
                    val player = MediaPlayer(vlc)
                    engine = player
                    val media = Media(vlc, Uri.parse(streamUrl))
                    try {
                        media.setHWDecoderEnabled(true, false)
                        player.media = media
                    } finally {
                        media.release()
                    }
                }
                val player = checkNotNull(engine)
                player.attachViews(surface, null, true, false)
                attached = true
                surface.requestLayout()
                surface.postInvalidateOnAnimation()
                var reported = false
                player.setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> playback = VlcPlayback.PLAYING
                        MediaPlayer.Event.Paused -> playback = VlcPlayback.PAUSED
                        MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> playback = VlcPlayback.FAILED
                        MediaPlayer.Event.Vout -> if (event.voutCount > 0 && !reported) {
                            reported = true
                            viewModel.channelRendered(profileId, playingChannelId)
                        }
                    }
                }
                nativePlayer = player
                player.play()
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                playback = VlcPlayback.FAILED
            } catch (_: LinkageError) {
                playback = VlcPlayback.FAILED
            } finally {
                nativePlayer = null
                engine?.setEventListener(null)
                if (attached) engine?.detachViews()
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        engine?.release()
                    } finally {
                        library?.release()
                    }
                }
            }
        }
    }

    val failed = playback == VlcPlayback.FAILED
    val controlsVisible = showControls || failed
    LaunchedEffect(controlsVisible, failed, showPicker) {
        if (showPicker) return@LaunchedEffect
        withFrameNanos { }
        val focus = when {
            failed -> retryFocus
            controlsVisible -> controlsFocus
            else -> playerFocus
        }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(showControls, showPicker, playback, menuInteraction) {
        if (showControls && !showPicker && playback == VlcPlayback.PLAYING) {
            delay(7_000)
            showControls = false
        }
    }
    BackHandler {
        if (showControls && !failed) showControls = false else onExit()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(playerFocus)
            .onPreviewKeyEvent { event ->
                if (showPicker) return@onPreviewKeyEvent false
                val key = event.nativeKeyEvent
                if (showControls && key.action == KeyEvent.ACTION_DOWN) menuInteraction++
                handleLiveTvRemoteKey(
                    key,
                    controlsVisible = controlsVisible,
                    onStep = { offset -> viewModel.adjacentChannel(profileId, channelId, offset)?.let(::tune) },
                    onOpenControls = { showControls = true }
                )
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { showControls = !showControls } }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
            factory = { context ->
                VLCVideoLayout(context).apply {
                    keepScreenOn = true
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    // Attach both surfaces with the layout so their first frame doesn't depend on
                    // a later Compose redraw after LibVLC starts.
                    val video = findViewById<ViewStub>(VlcResources.id.surface_stub).inflate() as SurfaceView
                    val subtitles = findViewById<ViewStub>(VlcResources.id.subtitles_surface_stub).inflate() as SurfaceView
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        video.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                        subtitles.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                    }
                }
            },
            update = { layout = it }
        )
        if (playback == VlcPlayback.OPENING) {
            Text(
                text = stringResource(R.string.live_tv_vlc_loading),
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }
        if (controlsVisible) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.82f)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                current?.let { Text(it.name, color = Color.White, style = MaterialTheme.typography.titleLarge) }
                currentProgramme?.let {
                    Text(stringResource(R.string.live_tv_now_line, it.title), color = Color.White)
                }
                if (failed) Text(stringResource(R.string.live_tv_vlc_error), color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (failed) {
                        Button(onClick = { retry++ }, modifier = Modifier.focusRequester(retryFocus)) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    Button(onClick = { showPicker = true }, modifier = Modifier.focusRequester(controlsFocus)) {
                        Text(stringResource(R.string.live_tv_channel_picker))
                    }
                    previous?.let { channel ->
                        Button(onClick = { tune(channel) }) {
                            Text(stringResource(R.string.live_tv_previous_channel))
                        }
                    }
                    if (playback == VlcPlayback.PLAYING || playback == VlcPlayback.PAUSED) {
                        val paused = playback == VlcPlayback.PAUSED
                        Button(onClick = {
                            if (paused) nativePlayer?.play() else nativePlayer?.pause()
                            menuInteraction++
                        }) {
                            Text(stringResource(if (paused) R.string.live_tv_vlc_resume else R.string.live_tv_vlc_pause))
                        }
                    }
                }
            }
        }
    }
    if (showPicker) LiveTvChannelPicker(viewModel, channelId, ::tune) { showPicker = false }
}
