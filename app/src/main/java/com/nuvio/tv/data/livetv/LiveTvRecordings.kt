package com.nuvio.tv.data.livetv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal enum class RecordingStatus {
    SCHEDULED, RECORDING, STOPPING, COMPLETE, PARTIAL, FAILED, MISSED;

    val active: Boolean get() = this == RECORDING || this == STOPPING
    val playable: Boolean get() = this == COMPLETE || this == PARTIAL
}

@Serializable
internal data class LiveRecording(
    val id: String,
    val profileId: Int,
    val sourceKey: String,
    val channelId: Long,
    val channelName: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val runAt: Long,
    val format: String,
    val status: RecordingStatus = RecordingStatus.SCHEDULED
) {
    fun matches(channel: LiveChannel, programme: LiveProgramme): Boolean =
        channelId == channel.id && startsAt == programme.startsAt
}

internal enum class RecordingRequest {
    SAVED, EXISTS, PERMISSION, OVERLAP, QUEUE_FULL, STORAGE, UNAVAILABLE
}

internal const val MAX_SIMULTANEOUS_RECORDINGS = 2
private const val MAX_SCHEDULED_RECORDINGS = 10

internal fun recordingScheduleLimit(
    entries: List<LiveRecording>,
    start: Long,
    end: Long
): RecordingRequest? {
    val overlaps = entries.filter {
        (it.status == RecordingStatus.SCHEDULED || it.status.active) && it.runAt < end && start < it.endsAt
    }
    if (overlaps.any { entry ->
            val instant = maxOf(start, entry.runAt)
            overlaps.count { instant >= it.runAt && instant < it.endsAt } >= MAX_SIMULTANEOUS_RECORDINGS
        }) return RecordingRequest.OVERLAP
    return RecordingRequest.QUEUE_FULL.takeIf { entries.count { it.status == RecordingStatus.SCHEDULED } >= MAX_SCHEDULED_RECORDINGS }
}

@Singleton
internal class LiveTvRecordings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveTvStorage: LiveTvStorage
) : ProfileScopedCredentialStore {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val preferences = context.getSharedPreferences("nexus_live_recordings", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "tv-recordings")
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(load())
    val entries = _entries.asStateFlow()

    fun canSchedule(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    @Synchronized
    fun schedule(
        profileId: Int,
        sourceKey: String,
        channel: LiveChannel,
        programme: LiveProgramme,
        format: String,
        now: Long = System.currentTimeMillis()
    ): RecordingRequest {
        val start = maxOf(programme.startsAt, now + 1_000L)
        if (start >= programme.endsAt || format !in setOf("m3u8", "ts") ||
            liveTvStorage.readSource(profileId)?.cacheKey != sourceKey) return RecordingRequest.UNAVAILABLE
        val before = _entries.value
        val matching = before.filter { it.profileId == profileId && it.sourceKey == sourceKey && it.matches(channel, programme) }
        if (matching.any { it.status == RecordingStatus.SCHEDULED || it.status.active || it.status.playable }) return RecordingRequest.EXISTS
        if (!canSchedule()) return RecordingRequest.PERMISSION
        recordingScheduleLimit(before.filter { it.endsAt > now }, start, programme.endsAt)?.let { return it }

        val entry = LiveRecording(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            sourceKey = sourceKey,
            channelId = channel.id,
            channelName = channel.name,
            title = programme.title,
            startsAt = programme.startsAt,
            endsAt = programme.endsAt,
            runAt = start,
            format = format
        )
        if (!save(before - matching.toSet() + entry)) return RecordingRequest.STORAGE
        if (!arm(entry, start)) {
            save(before)
            return if (canSchedule()) RecordingRequest.STORAGE else RecordingRequest.PERMISSION
        }
        return RecordingRequest.SAVED
    }

    @Synchronized
    fun restoreAlarms(now: Long = System.currentTimeMillis()) {
        val original = _entries.value
        val restored = original.map {
            if (it.status == RecordingStatus.SCHEDULED && it.endsAt <= now) it.copy(status = RecordingStatus.MISSED)
            else it
        }
        if (restored != original) save(restored)
        if (!canSchedule()) return
        restored.filter { it.status == RecordingStatus.SCHEDULED }
            .forEach { arm(it, maxOf(it.runAt, now + 1_000L)) }
    }

    @Synchronized
    fun claim(id: String, now: Long): LiveRecording? {
        val entry = _entries.value.firstOrNull { it.id == id && it.status == RecordingStatus.SCHEDULED } ?: return null
        if (now < entry.runAt) {
            arm(entry, entry.runAt)
            return null
        }
        if (now >= entry.endsAt) {
            replace(entry.copy(status = RecordingStatus.MISSED))
            return null
        }
        return entry.copy(status = RecordingStatus.RECORDING).takeIf(::replace)
    }

    @Synchronized
    fun failedToStart(id: String) {
        _entries.value.firstOrNull { it.id == id && it.status == RecordingStatus.SCHEDULED }?.let {
            replace(it.copy(status = RecordingStatus.FAILED))
        }
    }

    fun shouldContinue(id: String): Boolean =
        _entries.value.any { it.id == id && it.status == RecordingStatus.RECORDING }

    fun streamFor(entry: LiveRecording): String? =
        liveTvStorage.readSource(entry.profileId)?.takeIf { it.cacheKey == entry.sourceKey }
            ?.streamUrl(entry.channelId, entry.format)?.toString()

    fun captureFile(id: String): File = File(directory, "$id.part.ts")

    @Synchronized
    fun finish(id: String, completed: Boolean) {
        val entry = _entries.value.firstOrNull { it.id == id && it.status.active }
        if (entry == null) {
            captureFile(id).delete()
            return
        }
        val hasVideo = keepCapture(id)
        val status = when {
            !hasVideo -> RecordingStatus.FAILED
            completed && entry.status == RecordingStatus.RECORDING -> RecordingStatus.COMPLETE
            else -> RecordingStatus.PARTIAL
        }
        replace(entry.copy(status = status))
    }

    fun recordingFile(profileId: Int, id: String): File? {
        if (!_entries.value.any { it.id == id && it.profileId == profileId && it.status.playable }) return null
        return savedFile(id).takeIf { it.isFile && it.length() >= MIN_CAPTURE_BYTES }
    }

    @Synchronized
    fun stopOrRemove(profileId: Int, id: String) {
        val entry = _entries.value.firstOrNull { it.profileId == profileId && it.id == id } ?: return
        if (entry.status.active) {
            replace(entry.copy(status = RecordingStatus.STOPPING))
        } else {
            remove(listOf(entry))
        }
    }

    @Synchronized
    override fun removeProfile(profileId: Int) = remove(_entries.value.filter { it.profileId == profileId })

    @Synchronized
    override fun clearAllProfiles() = remove(_entries.value)

    private fun remove(records: List<LiveRecording>) {
        if (records.isEmpty() || !save(_entries.value - records.toSet())) return
        records.forEach {
            alarms.cancel(alarmIntent(it.id))
            savedFile(it.id).delete()
            captureFile(it.id).delete()
        }
    }

    private fun savedFile(id: String) = File(directory, "$id.ts")

    private fun keepCapture(id: String): Boolean {
        val capture = captureFile(id)
        val final = savedFile(id)
        if (final.isFile && final.length() >= MIN_CAPTURE_BYTES) return true
        if (capture.isFile && capture.length() >= MIN_CAPTURE_BYTES && capture.renameTo(final)) return true
        capture.delete()
        return false
    }

    private fun replace(entry: LiveRecording): Boolean = save(_entries.value.map { if (it.id == entry.id) entry else it })

    private fun save(records: List<LiveRecording>): Boolean {
        if (!preferences.edit().putString("recordings", json.encodeToString(records)).commit()) return false
        _entries.value = records
        return true
    }

    private fun load(): List<LiveRecording> {
        val stored = runCatching { json.decodeFromString<List<LiveRecording>>(preferences.getString("recordings", "[]") ?: "[]") }
            .getOrDefault(emptyList()).filter { runCatching { UUID.fromString(it.id).toString() == it.id }.getOrDefault(false) }
        val recovered = stored.map { entry ->
            when {
                entry.status.active -> entry.copy(status = if (keepCapture(entry.id)) RecordingStatus.PARTIAL else RecordingStatus.FAILED)
                entry.status == RecordingStatus.SCHEDULED && entry.endsAt <= System.currentTimeMillis() ->
                    entry.copy(status = RecordingStatus.MISSED)
                else -> entry
            }
        }
        if (recovered != stored) preferences.edit().putString("recordings", json.encodeToString(recovered)).apply()
        return recovered
    }

    private fun arm(entry: LiveRecording, time: Long): Boolean = runCatching {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent(entry.id))
    }.isSuccess

    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, LiveTvRecordingReceiver::class.java)
            .setAction(LiveTvRecordingReceiver.ACTION_CAPTURE)
            .setData(Uri.fromParts("nexus-recording", id, null))
            .putExtra(LiveTvRecordingReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val MIN_CAPTURE_BYTES = 188L * 20L
    }
}
