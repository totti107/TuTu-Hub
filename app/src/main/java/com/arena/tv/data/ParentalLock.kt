package com.arena.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Adult channels stay locked until the PIN is entered; they lock again when you leave them, after 30 minutes, or when the app restarts. */
class ParentalLock(private val settings: SettingsStore) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var until = 0L

    fun isUnlocked(): Boolean {
        if (_unlocked.value && System.currentTimeMillis() > until) _unlocked.value = false
        return _unlocked.value
    }

    /** @return true when the PIN is right (or, if none was set yet, it becomes the new PIN). */
    suspend fun tryUnlock(pin: String): Boolean {
        val saved = settings.current().parentalPin
        if (saved.isEmpty()) settings.setPin(pin) else if (saved != pin) return false
        until = System.currentTimeMillis() + 30 * 60_000L
        _unlocked.value = true
        return true
    }

    fun lock() { if (_unlocked.value) _unlocked.value = false }

    /** Checks the PIN without unlocking adult channels (e.g. the hidden channels list in Settings). */
    suspend fun verify(pin: String): Boolean {
        val saved = settings.current().parentalPin
        if (saved.isEmpty()) { settings.setPin(pin); return true }
        return saved == pin
    }
}
