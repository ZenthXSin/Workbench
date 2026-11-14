package io.github.zenthxsin.rustwright.ui.kit

import arc.scene.Element
import io.github.zenthxsin.rustwright.ui.kit.reactive.*
import kotlin.test.*

class ReactiveTest {
    private val queued = ArrayList<Runnable>()
    private fun frame() { val q = queued.toList(); queued.clear(); q.forEach { it.run() } }

    @BeforeTest fun setUp() { Reactive.poster = { queued += it } }
    @AfterTest fun tearDown() { Reactive.flush(); queued.clear() }

    @Test fun effectRunsOnceImmediatelyAndOnlyAgainWhenADependencyChanges() {
        val a = signal(1); val b = signal("x")
        var runs = 0; var seen = 0
        effect { runs++; seen = a.value }
        assertEquals(1, runs); assertEquals(1, seen)
        b.value = "y"; frame()
        assertEquals(1, runs, "没读过的信号变了不该重跑")
        a.value = 1; frame()
        assertEquals(1, runs, "值相同不该重跑")
        a.value = 2; frame()
        assertEquals(2, runs); assertEquals(2, seen)
    }

    @Test fun severalChangesInOneFrameRerunOnce() {
        val a = signal(0); var runs = 0
        effect { a.value; runs++ }
        repeat(10) { a.value = it + 1 }
        assertEquals(1, runs, "下一帧才重跑")
        frame()
        assertEquals(2, runs)
    }

    @Test fun derivedIsLazyAndTracksItsOwnDependencies() {
        val first = signal("ada"); val last = signal("lovelace"); var computes = 0
        val full = derived { computes++; first.value + " " + last.value }
        assertEquals(0, computes, "没人读就不算")
        var shown = ""
        effect { shown = full.value }
        assertEquals("ada lovelace", shown); assertEquals(1, computes)
        last.value = "byron"; frame()
        assertEquals("ada byron", shown); assertEquals(2, computes)
    }

    @Test fun dependenciesFollowTheBranchActuallyTaken() {
        val useA = signal(true); val a = signal(1); val b = signal(10)
        var seen = 0; var runs = 0
        effect { runs++; seen = if (useA.value) a.value else b.value }
        useA.value = false; frame()
        assertEquals(10, seen)
        a.value = 2; frame()
        assertEquals(2, runs, "切到 b 分支以后 a 变了不该重跑")
        b.value = 11; frame()
        assertEquals(11, seen)
    }

    @Test fun disposedEffectStopsAndUntrackedReadsDoNotSubscribe() {
        val a = signal(0); val b = signal(0); var runs = 0
        val d = effect { a.value; Reactive.untracked { b.value }; runs++ }
        b.value = 1; frame(); assertEquals(1, runs)
        d.dispose()
        a.value = 1; frame(); assertEquals(1, runs)
    }

    @Test fun ownerOffScreenPausesAndCatchesUpWhenItActsAgain() {
        val a = signal(0); var runs = 0; var seen = -1
        val owner = Element()
        effect(owner) { seen = a.value; runs++ }
        assertEquals(1, runs, "创建时立刻跑一次")
        a.value = 1; a.value = 2; frame()
        assertEquals(1, runs, "不在画面上：先不跑")
        a.value = 3; frame()
        assertEquals(1, runs, "暂停期间已退订，再变也不排队")
        owner.act(0.016f)
        assertEquals(2, runs); assertEquals(3, seen, "回到画面（开始 act）时补跑一次，拿到最新值")
    }

    @Test fun propertyDelegateReadsAndWritesTheSignal() {
        val s = signal(1)
        var x by s
        var seen = 0
        effect { seen = x }
        x = 5; frame()
        assertEquals(5, seen)
    }

    @Test fun batchDefersNotificationUntilTheEnd() {
        val a = signal(0); val b = signal(0); val sums = ArrayList<Int>()
        effect { sums += a.value + b.value }
        Reactive.batch { a.value = 1; b.value = 2 }
        frame()
        assertEquals(listOf(0, 3), sums)
    }

    @Test fun autoGridColumnsFollowWidth() {
        val g = AutoGrid(minCell = 160f, gap = 10f, maxColumns = 4)
        assertEquals(1, g.columns(100f))
        assertEquals(2, g.columns(330f))
        assertEquals(2, g.columns(499f), "3 列要 3×160 + 2×10 = 500")
        assertEquals(3, g.columns(500f))
        assertEquals(4, g.columns(3000f))
    }

    @Test fun flowRowWrapsWhenALineIsFull() {
        class Box(val w: Float) : Element() { override fun getPrefWidth() = w; override fun getPrefHeight() = 10f }
        val row = FlowRow(hgap = 5f, vgap = 4f)
        repeat(4) { row.add(Box(40f)) }
        row.setSize(100f, 0f)
        assertEquals(10f + 4f + 10f, row.prefHeight, "两个一行，共两行")
        row.setSize(200f, 0f)
        assertEquals(10f, row.prefHeight, "放得下就一行")
        row.layout()
        assertEquals(listOf(0f, 45f, 90f, 135f), (row.children as Iterable<Element>).map { it.x })
    }
}
