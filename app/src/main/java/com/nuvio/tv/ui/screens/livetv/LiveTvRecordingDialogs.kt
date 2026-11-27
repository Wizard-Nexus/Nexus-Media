package com.nuvio.tv.ui.screens.livetv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveRecording
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.data.livetv.RecordingRequest
import com.nuvio.tv.data.livetv.RecordingStatus
import com.nuvio.tv.data.livetv.canReplay
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LiveTvProgrammeDialog(
    event: LiveSportsEvent,
    viewModel: LiveTvViewModel,
    recording: LiveRecording?,
    onWatch: () -> Unit,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstAction = remember(event) { FocusRequester() }
    var saving by remember(event) { mutableStateOf(false) }
    var result by remember(event) { mutableStateOf<RecordingRequest?>(null) }
    val now = liveGuideClock()
    val programme = event.programme
    val ended = programme.endsAt <= now
    val canReplay = programme.canReplay(event.channel, now)
    val isSaved = recording?.status?.playable == true
    val canRecord = !ended && recording?.status?.let { it.active || it == RecordingStatus.SCHEDULED || it.playable } != true

    NuvioDialog(
        onDismiss = onDismiss,
        title = programme.title,
        subtitle = stringResource(
            R.string.live_tv_guide_event_time, event.channel.name,
            programmeDate(programme.startsAt), formatProgrammeTime(programme.endsAt)
        )
    ) {
        programme.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        val status = recording?.status?.label
        val message = when (result) {
            RecordingRequest.PERMISSION -> R.string.live_tv_recording_permission_help
            RecordingRequest.OVERLAP -> R.string.live_tv_recording_overlap
            RecordingRequest.STORAGE -> R.string.live_tv_recording_save_error
            RecordingRequest.UNAVAILABLE -> R.string.live_tv_recording_unavailable
            else -> status ?: when {
                ended && !canReplay -> R.string.live_tv_replay_unavailable
                !ended -> R.string.live_tv_recording_device_help
                else -> null
            }
        }
        message?.let { Text(stringResource(it), color = GuideMuted, style = MaterialTheme.typography.bodySmall) }
        if (result == RecordingRequest.PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GuideButton(stringResource(R.string.live_tv_recording_schedule_permission), onClick = {
                val packageUri = Uri.fromParts("package", context.packageName, null)
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
                }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val closeFocus = if (!canRecord && !isSaved && !canReplay) Modifier.focusRequester(firstAction) else Modifier
            GuideButton(stringResource(R.string.action_close), onDismiss, closeFocus)
            if (programme.isOnAir(now)) {
                GuideButton(stringResource(R.string.live_tv_watch_channel), onClick = {
                    onDismiss()
                    onWatch()
                })
            }
            if (canRecord) {
                GuideButton(
                    stringResource(R.string.live_tv_record_programme), onClick = {
                        saving = true
                        scope.launch {
                            try {
                                result = viewModel.recordProgramme(event)
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(firstAction), enabled = !saving
                )
            } else if (isSaved || canReplay) {
                GuideButton(
                    stringResource(if (isSaved) R.string.live_tv_play_recording else R.string.live_tv_play_replay),
                    onClick = {
                        val playback = recording?.let(viewModel::playRecording) ?: viewModel.playReplay(event)
                        if (playback != null && onPlayArchive(playback)) onDismiss()
                        else result = RecordingRequest.UNAVAILABLE
                    },
                    modifier = Modifier.focusRequester(firstAction)
                )
            }
        }
        LaunchedEffect(event, canRecord, isSaved, canReplay) { firstAction.requestFocus() }
    }
}

@Composable
internal fun LiveTvRecordingsDialog(
    viewModel: LiveTvViewModel,
    profileId: Int,
    onPlayArchive: (LiveTvArchivePlayback) -> Boolean,
    onDismiss: () -> Unit
) {
    val all by viewModel.recordings.collectAsStateWithLifecycle()
    val entries = remember(profileId, all) {
        val mine = all.filter { it.profileId == profileId }
        val (upcoming, previous) = mine.partition { it.status.active || it.status == RecordingStatus.SCHEDULED }
        upcoming.sortedBy { it.runAt } + previous.sortedByDescending { it.startsAt }
    }
    var pendingDelete by remember { mutableStateOf<LiveRecording?>(null) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val delete = pendingDelete
    if (delete != null) {
        NuvioDialog(
            onDismiss = { pendingDelete = null },
            title = stringResource(R.string.live_tv_delete_recording),
            subtitle = delete.title
        ) {
            val cancelFocus = remember { FocusRequester() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuideButton(stringResource(R.string.action_cancel), { pendingDelete = null }, Modifier.focusRequester(cancelFocus))
                GuideButton(stringResource(R.string.action_delete), onClick = {
                    viewModel.stopOrRemoveRecording(delete)
                    pendingDelete = null
                })
            }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        }
    } else {
        NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.live_tv_recordings), width = 590.dp) {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.live_tv_recording_empty), color = GuideMuted)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (unavailable == entry.id) stringResource(R.string.live_tv_recording_file_unavailable)
                                    else stringResource(
                                        R.string.live_tv_recording_entry_subtitle,
                                        entry.channelName, programmeDate(entry.startsAt), stringResource(entry.status.label)
                                    ),
                                    color = GuideMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (entry.status.playable) {
                                GuideButton(stringResource(R.string.live_tv_play_recording), onClick = {
                                    val archive = viewModel.playRecording(entry)
                                    if (archive != null && onPlayArchive(archive)) onDismiss()
                                    else unavailable = entry.id
                                })
                            }
                            val action = when (entry.status) {
                                RecordingStatus.SCHEDULED -> R.string.live_tv_cancel_recording
                                RecordingStatus.RECORDING, RecordingStatus.STOPPING -> R.string.live_tv_stop_recording
                                else -> R.string.action_delete
                            }
                            GuideButton(
                                stringResource(action),
                                onClick = {
                                    if (entry.status.playable) pendingDelete = entry
                                    else viewModel.stopOrRemoveRecording(entry)
                                },
                                enabled = entry.status != RecordingStatus.STOPPING
                            )
                        }
                    }
                }
            }
            GuideButton(stringResource(R.string.action_close), onDismiss, Modifier.focusRequester(firstFocus))
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
        }
    }
}

@Composable
private fun programmeDate(time: Long): String = remember(time) {
    DateTimeFormatter.ofPattern("EEE, d MMM HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
}

@get:StringRes
private val RecordingStatus.label: Int
    get() = when (this) {
        RecordingStatus.SCHEDULED -> R.string.live_tv_recording_scheduled
        RecordingStatus.RECORDING -> R.string.live_tv_recording_running
        RecordingStatus.STOPPING -> R.string.live_tv_recording_stopping
        RecordingStatus.COMPLETE -> R.string.live_tv_recording_complete
        RecordingStatus.PARTIAL -> R.string.live_tv_recording_partial
        RecordingStatus.FAILED -> R.string.live_tv_recording_failed
        RecordingStatus.MISSED -> R.string.live_tv_recording_missed
    }
