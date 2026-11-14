package com.tutu.prayertv

import android.service.dreams.DreamService

/** The TV screen saver: Settings → Device Preferences → Screen saver → Prayer & Hadith. Any button wakes the TV. */
class PrayerDream : DreamService() {
    private var board: BoardView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val b = BoardView(this, dream = true)
        board = b
        setContentView(b)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        board?.start()
    }

    override fun onDreamingStopped() {
        board?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        board?.stop()
        board = null
        super.onDetachedFromWindow()
    }
}
