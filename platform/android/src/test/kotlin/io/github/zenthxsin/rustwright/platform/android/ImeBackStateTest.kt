package io.github.zenthxsin.rustwright.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBackStateTest {
    @Test fun delayedVisibleInsetsAfterBackDoNotConsumeTheNextPageBack() {
        val ime = ImeBackState()
        ime.inputRequested()
        ime.visibilityChanged(true)
        assertTrue(ime.consumeBack())
        ime.visibilityChanged(true) // hide animation still occupies the window
        assertFalse(ime.consumeBack())
        ime.visibilityChanged(false)
        assertFalse(ime.consumeBack())
        ime.inputRequested()
        ime.visibilityChanged(true)
        assertTrue(ime.consumeBack())
    }

    @Test fun systemImeCallbackCanHideKeyboardWithoutAlsoNavigatingThePage() {
        val ime = ImeBackState()
        ime.inputRequested()
        ime.visibilityChanged(true)
        ime.visibilityChanged(false) // Android's IME callback consumed the first gesture
        assertFalse(ime.consumeBack()) // the next app callback may navigate
    }

    @Test fun leavingAFieldDoesNotLeaveAStaleKeyboardBackPending() {
        val ime = ImeBackState()
        ime.inputRequested()
        ime.visibilityChanged(true)
        ime.inputEnded()
        ime.visibilityChanged(true) // late insets from the keyboard being dismissed
        assertFalse(ime.consumeBack())
        ime.visibilityChanged(false)
        ime.inputRequested()
        ime.visibilityChanged(true)
        assertTrue(ime.consumeBack())
    }
}
