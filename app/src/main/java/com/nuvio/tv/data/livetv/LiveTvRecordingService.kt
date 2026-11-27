package com.nuvio.tv.data.livetv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nuvio.tv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class LiveTvRecordingService : Service() {
    @Inject internal lateinit var recordings: LiveTvRecordings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureSlots = Semaphore(MAX_SIMULTANEOUS_RECORDINGS)
    private var pending = 0
    private var newestStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(LiveTvRecordingReceiver.EXTRA_ID)
        newestStart = startId
        // The alarm may be starting us while there is no activity in the foreground.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }.isSuccess
        pending++
        scope.launch {
            try {
                if (id != null) {
                    if (foreground) captureSlots.withPermit { capture(id) }
                    else withContext(Dispatchers.IO) { recordings.failedToStart(id) }
                }
            } finally {
                if (--pending == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(newestStart)
                } else if (foreground) {
                    updateNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun capture(id: String) {
        // Claim within the same IO operation, so cancellation cannot leave the entry claimed
        // without running the finalizer.
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val entry = recordings.claim(id, startedAt) ?: return@withContext
            var completed = false
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusMedia:recording")
            try {
                wakeLock.acquire((entry.endsAt - startedAt).coerceAtLeast(1_000L) + STALL_TIMEOUT_MS)
                updateNotification()
                val destination = recordings.captureFile(id)
                val parent = checkNotNull(destination.parentFile)
                if (!parent.isDirectory) parent.mkdirs()
                if (!parent.isDirectory || parent.usableSpace < MIN_FREE_BYTES) return@withContext
                val url = recordings.streamFor(entry) ?: return@withContext
                completed = writeTransportStream(entry, url, destination, parent) &&
                    startedAt - entry.startsAt <= START_GRACE_MS
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // Finalize any playable bytes already on disk.
            } catch (_: LinkageError) {
                // A device without the native recorder cannot start the capture.
            } finally {
                try {
                    withContext(NonCancellable) { recordings.finish(id, completed) }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    private suspend fun writeTransportStream(entry: LiveRecording, url: String, destination: File, directory: File): Boolean {
        val engine = LibVLC(this, arrayListOf("--quiet", "--no-video-title-show", "--vout=dummy", "--aout=dummy"))
        try {
            val player = MediaPlayer(engine)
            try {
                val media = Media(engine, Uri.parse(url))
                try {
                    media.setHWDecoderEnabled(false, false)
                    // Remux the provider's encoded streams into a local TS. No frame decoding is needed.
                    media.addOption(":sout=#standard{access=file,mux=ts,dst=" + destination.absolutePath + "}")
                    media.addOption(":sout-all")
                    player.media = media
                } finally {
                    media.release()
                }
                val streamEnded = AtomicBoolean(false)
                player.setEventListener { event ->
                    if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.EncounteredError) {
                        streamEnded.set(true)
                    }
                }
                player.play()
                var lastSize = 0L
                var lastWrite = SystemClock.elapsedRealtime()
                while (recordings.shouldContinue(entry.id) && !streamEnded.get()) {
                    val wallTime = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val size = destination.length()
                    if (size > lastSize) {
                        lastSize = size
                        lastWrite = elapsed
                    }
                    if (elapsed - lastWrite >= STALL_TIMEOUT_MS || directory.usableSpace < MIN_FREE_BYTES) return false
                    if (wallTime >= entry.endsAt) return elapsed - lastWrite <= END_GRACE_MS
                    delay(minOf(1_000L, entry.endsAt - wallTime))
                }
                return false
            } finally {
                player.setEventListener(null)
                try {
                    player.stop()
                } finally {
                    player.release()
                }
            }
        } finally {
            engine.release()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val active = recordings.entries.value.filter { it.status.active }
        val detail = when (active.size) {
            0 -> getString(R.string.live_tv_recording_starting)
            1 -> active.single().title
            else -> resources.getQuantityString(R.plurals.live_tv_recordings_active, active.size, active.size)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.live_tv_recordings))
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, getString(R.string.live_tv_recordings), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "nexus_tv_recordings"
        const val NOTIFICATION_ID = 6104
        const val MIN_FREE_BYTES = 96L * 1024L * 1024L
        const val STALL_TIMEOUT_MS = 30_000L
        const val START_GRACE_MS = 35_000L
        const val END_GRACE_MS = 18_000L
    }
}
