package com.nuvio.tv.ui.screens.livetv

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.nowAndNext
import com.nuvio.tv.ui.screens.player.LivePlaybackWatchClock
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
import kotlin.math.roundToLong

private enum class VlcPlayback { OPENING, PLAYING, PAUSED, FAILED }

@Composable
internal fun LiveTvVlcScreen(
    viewModel: LiveTvViewModel,
    profileId: Int,
    initialChannelId: Long,
    initialBrowse: Bundle?,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean,
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
    val previous = state.history.previousChannel(channelId) { id ->
        channelIndex[id]?.let { it.group !in state.categories.hidden } == true
    }?.let(channelIndex::get)
    val currentProgramme = state.guide[channelId].orEmpty().nowAndNext(liveGuideClock()).first
    val context = LocalContext.current.applicationContext
    val playbackLock = remember { Mutex() }
    val playerFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var showControls by remember { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(initialBrowse != null) }
    var browseOnOpen by remember { mutableStateOf(initialBrowse) }
    val guideVisible = showGuide && current != null
    var menuInteraction by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var startOverArchive by remember { mutableStateOf<LiveTvArchivePlayback?>(null) }
    var showSeekFeedback by remember(channelId) { mutableStateOf(false) }
    val watchClock = remember(channelId) { LivePlaybackWatchClock() }
    var watchedMs by remember(channelId) { mutableLongStateOf(0L) }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var nativePlayer by remember(streamUrl, retry) { mutableStateOf<MediaPlayer?>(null) }
    var playback by remember(streamUrl, retry) { mutableStateOf(VlcPlayback.OPENING) }

    fun tune(channel: LiveChannel, channelList: List<LiveChannel>? = null): Boolean {
        if (startOverArchive != null) return false
        if (viewModel.tune(channel, channelList) == null) return false
        channelId = channel.id
        showControls = false
        return true
    }

    fun seekBy(deltaMs: Long) {
        val player = nativePlayer
        val window = player?.liveTvSeekWindow()
        if (player != null && window != null) {
            val target = window.seekBy(deltaMs)
            if (target != window.positionMs) {
                val length = player.length
                if (length > 0L) player.setPosition((target.toDouble() / length).toFloat().coerceIn(0f, 1f), false)
            }
        }
        if (!showControls) showSeekFeedback = true
        menuInteraction++
    }

    fun startLiveProgramme(programme: LiveProgramme) {
        if (startOverArchive != null) return
        val player = nativePlayer
        val target = player?.liveTvSeekWindow()?.programmeStart(programme.startsAt, System.currentTimeMillis())
        if (player != null && target != null) {
            player.setTime(target, false)
            player.play()
            menuInteraction++
            return
        }
        startOverArchive = viewModel.programmeStartOver(profileId, channelId, programme)
        if (startOverArchive == null) {
            Toast.makeText(
                context, R.string.live_tv_start_over_unavailable, Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showFullscreen() {
        showGuide = false
        browseOnOpen = null
    }

    LaunchedEffect(channelId, state.sourceKey) { viewModel.requestGuide(listOf(channelId)) }
    LaunchedEffect(layout, foreground, streamUrl, retry, state.loading && current == null, state.stereoPcm, startOverArchive) {
        val archive = startOverArchive
        if (archive != null) {
            playbackLock.withLock {
                if (!onPlayArchive(archive)) startOverArchive = null
            }
            return@LaunchedEffect
        }
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
                    val vlc = LibVLC(context, arrayListOf("--quiet", "--no-video-title-show", "--stats"))
                    library = vlc
                    val player = MediaPlayer(vlc)
                    engine = player
                    val audioDevice = if (state.stereoPcm) "stereo" else "pcm"
                    if (!player.setAudioOutput("android_audiotrack") || !player.setAudioOutputDevice(audioDevice)) {
                        Log.w("LiveTvVlc", "Could not configure the requested PCM audio output")
                    }
                    val media = Media(vlc, Uri.parse(streamUrl))
                    try {
                        media.setHWDecoderEnabled(true, false)
                        media.addOption(":network-caching=750")
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
    val controlsVisible = (showControls || failed) && !guideVisible
    val window by rememberLiveTvSeekWindow(
        !guideVisible && !failed && (controlsVisible || showSeekFeedback), nativePlayer
    ) { nativePlayer?.liveTvSeekWindow() }
    LaunchedEffect(channelId, playback, controlsVisible, showSeekFeedback) {
        do {
            watchedMs = watchClock.watchedDurationMs(
                isLive = true,
                isPlaying = playback == VlcPlayback.PLAYING,
                nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
            delay(1_000)
        } while (controlsVisible || showSeekFeedback)
    }
    LaunchedEffect(showSeekFeedback, showControls, guideVisible, menuInteraction) {
        if (showSeekFeedback && !showControls && !guideVisible) delay(1_500)
        showSeekFeedback = false
    }
    LaunchedEffect(controlsVisible, failed, guideVisible) {
        if (guideVisible) return@LaunchedEffect
        withFrameNanos { }
        val focus = when {
            failed -> retryFocus
            controlsVisible -> controlsFocus
            else -> playerFocus
        }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(showControls, guideVisible, playback, menuInteraction) {
        if (showControls && !guideVisible && playback == VlcPlayback.PLAYING) {
            delay(7_000)
            showControls = false
        }
    }
    BackHandler {
        when {
            guideVisible -> showFullscreen()
            showControls && !failed -> showControls = false
            else -> onExit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(playerFocus)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (guideVisible) {
                    if (key.keyCode != KeyEvent.KEYCODE_ESCAPE) return@onPreviewKeyEvent false
                    if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) showFullscreen()
                    return@onPreviewKeyEvent true
                }
                if (showControls && key.action == KeyEvent.ACTION_DOWN) menuInteraction++
                handleLiveTvRemoteKey(
                    key,
                    controlsVisible = controlsVisible,
                    onStep = { offset -> viewModel.adjacentChannel(profileId, channelId, offset)?.let { tune(it) } },
                    onSeek = ::seekBy,
                    onOpenControls = { showControls = true }
                )
            }
            .focusable(enabled = !guideVisible)
            .then(if (guideVisible) Modifier else Modifier.pointerInput(Unit) {
                detectTapGestures { showControls = !showControls }
            })
    ) {
        Box(if (guideVisible) Modifier.liveTvGuidePreview() else Modifier.fillMaxSize()) {
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
            if (guideVisible) {
                when (playback) {
                    VlcPlayback.OPENING -> LiveTvPreviewStatus(R.string.live_tv_vlc_loading)
                    VlcPlayback.FAILED -> LiveTvPreviewStatus(R.string.live_tv_preview_unavailable)
                    else -> Unit
                }
            } else if (playback == VlcPlayback.OPENING) {
                Text(
                    text = stringResource(R.string.live_tv_vlc_loading),
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
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
                if (failed) {
                    Text(stringResource(R.string.live_tv_vlc_error), color = Color.White)
                } else {
                    LiveTvSeekBar(window, focusRequester = seekFocus, downFocusRequester = controlsFocus, onSeek = ::seekBy)
                }
                val seekUp = if (window == null) Modifier else Modifier.focusProperties { up = seekFocus }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (failed) {
                        Button(onClick = { retry++ }, modifier = Modifier.focusRequester(retryFocus)) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    Button(
                        onClick = {
                            showControls = false
                            showGuide = true
                        },
                        modifier = seekUp.focusRequester(controlsFocus)
                    ) {
                        Text(stringResource(R.string.live_tv_channel_picker))
                    }
                    previous?.let { channel ->
                        Button(onClick = { tune(channel) }, modifier = seekUp) {
                            Text(stringResource(R.string.live_tv_previous_channel))
                        }
                    }
                    if (playback == VlcPlayback.PLAYING || playback == VlcPlayback.PAUSED) {
                        val paused = playback == VlcPlayback.PAUSED
                        Button(onClick = {
                            if (paused) nativePlayer?.play() else nativePlayer?.pause()
                            menuInteraction++
                        }, modifier = seekUp) {
                            Text(stringResource(if (paused) R.string.live_tv_vlc_resume else R.string.live_tv_vlc_pause))
                        }
                    }
                    if (!failed) {
                        Spacer(Modifier.weight(1f))
                        if (currentProgramme != null) {
                            LiveTvStartOverButton({ startLiveProgramme(currentProgramme) }, seekUp) { menuInteraction++ }
                        }
                        LiveTvFpsReadout { nativePlayer?.liveTvVideoSample() }
                        LiveTvEdgeButton(window, watchedMs, onClick = {
                            retry++
                            menuInteraction++
                        }, modifier = seekUp)
                    }
                }
            }
        } else if (showSeekFeedback && !guideVisible) {
            LiveTvSeekFeedback(window, watchedMs, Modifier.align(Alignment.BottomCenter))
        }
        if (guideVisible) {
            LiveTvPlaybackGuide(
                state, viewModel, channelId, browseOnOpen,
                onTune = { channel, list, _ -> tune(channel, list) },
                onPlayArchive = onPlayArchive,
                onFullscreen = ::showFullscreen,
                modifier = Modifier.zIndex(4f)
            )
        }
    }
}

private fun MediaPlayer.liveTvVideoSample(): LiveTvVideoSample {
    val media = media
    val rendered = try {
        media?.stats?.displayedPictures?.takeIf { it > 0 }
    } finally {
        media?.release()
    }
    val track = currentVideoTrack
    val fps = if (track != null && track.frameRateDen > 0) track.frameRateNum.toDouble() / track.frameRateDen else null
    return LiveTvVideoSample(this, rendered, fps)
}

private fun MediaPlayer.liveTvSeekWindow(): LiveTvSeekWindow? {
    if (!isSeekable) return null
    val length = length
    val fraction = position
    if (length <= 0L || fraction !in 0f..1f) return null
    // Leave time for the final segment to buffer. A fractional position follows the sliding playlist.
    val edge = length - minOf(6_000L, length / 4)
    return LiveTvSeekWindow(0L, edge, (length * fraction.toDouble()).roundToLong())
}
