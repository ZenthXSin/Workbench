package io.github.zenthxsin.rustwright.platform.android

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 一次回车只换一行：按下触发，抬起只吞掉不再触发。 */
class EnterDecisionTest {
    @Test fun constantsMatchAndroid() {
        assertEquals(KeyEvent.KEYCODE_ENTER, EnterDecision.KEY_ENTER)
        assertEquals(KeyEvent.KEYCODE_NUMPAD_ENTER, EnterDecision.KEY_NUMPAD_ENTER)
        assertEquals(KeyEvent.ACTION_DOWN, EnterDecision.ACTION_DOWN)
        assertEquals(EditorInfo.IME_NULL, EnterDecision.IME_NULL)
        assertEquals(EditorInfo.IME_ACTION_NONE, EnterDecision.IME_ACTION_NONE)
    }

    @Test fun enterKeyFiresOnceAcrossDownAndUp() {
        val down = EnterDecision.of(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0)
        val up = EnterDecision.of(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, 0)
        assertEquals(EnterDecision(consume = true, fire = true), down)
        assertEquals(EnterDecision(consume = true, fire = false), up)
    }

    @Test fun repeatDoesNotFire() {
        assertEquals(false, EnterDecision.of(0, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 1).fire)
    }

    @Test fun editorActionWithoutKeyFires() {
        assertEquals(EnterDecision(true, true), EnterDecision.of(EditorInfo.IME_ACTION_DONE, null, null, 0))
        assertEquals(EnterDecision(false, false), EnterDecision.of(EditorInfo.IME_NULL, null, null, 0))
        assertEquals(EnterDecision(false, false), EnterDecision.of(EditorInfo.IME_ACTION_NONE, null, null, 0))
    }

    @Test fun otherKeysPassThrough() {
        assertEquals(EnterDecision(false, false), EnterDecision.of(0, KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0))
    }
}
