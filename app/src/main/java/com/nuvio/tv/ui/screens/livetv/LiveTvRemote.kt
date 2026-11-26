package com.nuvio.tv.ui.screens.livetv

import android.view.KeyEvent

internal fun handleLiveTvRemoteKey(
    event: KeyEvent,
    controlsVisible: Boolean,
    onStep: (Int) -> Unit,
    onOpenControls: () -> Unit
): Boolean {
    val released = event.action == KeyEvent.ACTION_UP && !event.isCanceled
    return when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (released) onStep(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (controlsVisible) return false
            if (released) onOpenControls()
            true
        }
        else -> false
    }
}
