package com.nuvio.tv.data.livetv

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LiveTvRecordingReceiver : BroadcastReceiver() {
    @Inject internal lateinit var recordings: LiveTvRecordings

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CAPTURE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val capture = Intent(context, LiveTvRecordingService::class.java).putExtra(EXTRA_ID, id)
                if (runCatching { ContextCompat.startForegroundService(context, capture) }.isFailure) {
                    runAsync { recordings.failedToStart(id) }
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> runAsync { recordings.restoreAlarms() }
        }
    }

    private fun runAsync(block: () -> Unit) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } finally {
                result.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_CAPTURE = "app.nexusmedia.tv.RECORD_PROGRAMME"
        const val EXTRA_ID = "recording-id"
    }
}
