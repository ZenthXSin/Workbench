package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.account.BenefitBalance
import io.github.zenthxsin.rustwright.core.account.BenefitWindow
import io.github.zenthxsin.rustwright.core.account.NewApiBenefits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BenefitsStateTest {
    private val window = BenefitWindow(1_000_000, 100, 200, 999_700, 1_800_000_000, 1_800_018_000)
    private val balance = BenefitBalance(window, window, window, "Asia/Shanghai", emptyMap(), 0, 0, false)

    @Test fun freeConversationNeverMakesRemoteTitleRequest() = runTest {
        var calls = 0
        val remote: suspend (String) -> String? = { calls++; "模型概括" }
        assertEquals(null, conversationTitle("workbench-free/deepseek-test", "用户首句", remote))
        assertEquals(0, calls)
        assertEquals("模型概括", conversationTitle("paid-model", "用户首句", remote))
        assertEquals(1, calls)
    }

    @Test fun countdownUsesServerTimeAndElapsedTimeWithoutGrantingQuota() {
        val empty = window.copy(remaining = 0)
        val data = NewApiBenefits(true, true, balance = balance.copy(fiveHours = empty), serverTime = empty.resetsAt - 10)
        val oldSnapshot = BenefitsState(loggedIn = true, data = data, receivedNanos = System.nanoTime() - 60_000_000_000L)
        assertTrue(BenefitsPage.resetText(empty, oldSnapshot, null).contains("请刷新确认额度"))
        assertFalse(oldSnapshot.data!!.textAvailable)
        val justRead = oldSnapshot.copy(receivedNanos = System.nanoTime())
        assertTrue(BenefitsPage.resetText(empty, justRead, null).contains("约 1 分钟后重置"))
        assertFalse(justRead.data!!.textAvailable)
    }

    @Test fun missingServerTimeDisplaysFixedResetAndZeroStartNeverInventsTimer() {
        val state = BenefitsState(loggedIn = true, data = NewApiBenefits(true, true, balance = balance))
        val text = BenefitsPage.resetText(window, state, "Asia/Shanghai")
        assertTrue(text.endsWith("重置（Asia/Shanghai）"))
        assertFalse(text.contains("后重置"))
        assertEquals("尚未开始 · 首次使用后开始计时", BenefitsPage.resetText(window.copy(startsAt = 0, resetsAt = 0), state, null))
    }

    @Test fun reviewTakesPriorityOverAvailableBalance() {
        val review = BenefitsState(loggedIn = true, data = NewApiBenefits(true, true, balance = balance.copy(needsReview = true)))
        assertEquals("用量待核对，免费调用已暂停", review.summary())
        assertFalse(review.data!!.available)
        assertEquals("登录后查看", BenefitsState.SignedOut.summary())
        assertEquals("福利尚未开放", BenefitsState(loggedIn = true, data = NewApiBenefits(false)).summary())
    }
}
