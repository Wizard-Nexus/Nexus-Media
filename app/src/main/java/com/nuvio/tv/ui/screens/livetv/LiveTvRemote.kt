package com.nuvio.tv.ui.screens.livetv

import android.view.KeyEvent
import com.nuvio.tv.ui.screens.player.PlayerScrubRates

internal fun handleLiveTvRemoteKey(
    event: KeyEvent,
    controlsVisible: Boolean,
    onStep: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenControls: () -> Unit
): Boolean {
    if (controlsVisible) return false
    if (handleLiveTvSeekKey(event, onSeek)) return true
    val released = event.action == KeyEvent.ACTION_UP && !event.isCanceled
    return when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (released) onStep(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (released) onOpenControls()
            true
        }
        else -> false
    }
}

internal fun handleLiveTvSeekKey(event: KeyEvent, seek: (Long) -> Unit): Boolean {
    val forwards = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> false
        KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
        seek(if (forwards) PlayerScrubRates.STEP_SHORT_MS else -PlayerScrubRates.STEP_SHORT_MS)
    }
    return true
}
