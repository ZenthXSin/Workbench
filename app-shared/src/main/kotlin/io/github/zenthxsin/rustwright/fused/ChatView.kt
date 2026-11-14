package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexApproval
import io.github.zenthxsin.rustwright.core.codex.CodexAnswer
import io.github.zenthxsin.rustwright.core.codex.CodexConversation
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.rwin
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.WorkingIndicator
import io.github.zenthxsin.rustwright.ui.kit.iconButton
import io.github.zenthxsin.rustwright.ui.kit.pill
import io.github.zenthxsin.rustwright.ui.widgets.MarkdownView

/**
 * 对话内容（原型 Chat）：用户气泡、折叠的「已运行 N 步」行、助手回复（Markdown）、审批卡、运行中提示。
 * 按轮次缓存：只有内容变化的轮次重建。
 */
class ChatView(
    private val wb: CodexWorkbench,
    private val conversation: () -> CodexConversation?,
    private val actions: ChatActions,
) : Table() {

    interface ChatActions {
        fun openSteps(turn: CodexTurn, items: List<CodexItem>)
        fun copy(text: String)
        fun regenerate(turn: CodexTurn)
        fun produced(turn: CodexTurn): List<Artifact>
        fun openArtifact(a: Artifact)
        fun openLibrary()
        /** 一轮结束后的自动检查：已有结果直接返回，否则在后台跑、跑完调 [ready]；没有要检查的文件返回 null。 */
        fun turnCheck(turn: CodexTurn, ready: () -> Unit): TurnCheck? = null
        fun openCheck(check: TurnCheck) {}
        /** 点了回复里的链接。 */
        fun openLink(url: String) {}
        /** 消息里附件路径（相对项目目录）对应的本机文件；不存在返回 null。 */
        fun attachmentFile(path: String): java.io.File? = null
        /** 在 [anchor] 旁边弹出菜单（长按用户消息）。 */
        fun popup(items: List<MenuHost.Item>, anchor: Element) {}
    }

    private val list = Table().top()
    val scroll = ScrollPane(list)
    private val cells = LinkedHashMap<String, Pair<String, Element>>()
    private val tail = Table().left()
    private val working = WorkingIndicator()
    private var shownVersion = -1
    /** 自动检查结果到了就加一，触发重排。 */
    private var checkVersion = 0
    private var shownCheck = 0
    private var shownConv: String? = null
    private var stick = true
    private val toBottom = Tap(K.surface2, 22f) { scrollToBottom() }
    private val toBottomAlpha = arc.math.Smooth(0f, 0.2f, arc.math.Interp.ease)
    private val selection = ChatSelection(scroll) { actions.copy(it) }
    // 长按：按下的位置（视口坐标）、时间、所在消息
    private var pressing = false
    private var pressMoved = false
    private var pressLong = false
    private var pressAt = 0L
    private var pressX = 0f
    private var pressY = 0f
    private var pressRoot: Element? = null
    private val pressV = arc.math.geom.Vec2()
    private val press = object : InputListener() {
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean {
            if (pointer != 0 || selection.isMenu(event.targetActor)) return false
            val v = scroll.stageToLocalCoordinates(pressV.set(event.stageX, event.stageY))
            // 按在拖柄上：自己处理，不让对话跟着滚
            if (selection.grab(v.x, v.y)) { pressing = false; event.stop(); return true }
            pressing = true; pressMoved = false; pressLong = false
            pressAt = System.currentTimeMillis(); pressX = v.x; pressY = v.y
            pressRoot = generateSequence(event.targetActor) { it.parent }.firstOrNull { it.userObject is Selectable }
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (pointer != 0) return
            val v = scroll.stageToLocalCoordinates(pressV.set(event.stageX, event.stageY))
            if (selection.dragging != 0) { selection.drag(v.x, v.y); return }
            if (!pressMoved && kotlin.math.hypot(v.x - pressX, v.y - pressY) > dp(10f)) pressMoved = true
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode) {
            if (pointer != 0) return
            val cancelled = event.stageX == Int.MIN_VALUE.toFloat()
            if (selection.dragging != 0) selection.release()
            // 点一下别处取消选区（滚动不算）
            else if (pressing && !cancelled && !pressMoved && !pressLong) selection.clear()
            pressing = false
        }
    }
    private class QuestionDraft {
        val selected = LinkedHashMap<String, LinkedHashSet<String>>()
        val text = LinkedHashMap<String, String>()
    }
    private val questionDrafts = HashMap<String, QuestionDraft>()
    private val approvalCells = HashMap<String, Pair<CodexApproval, Element>>()
    /** 已经播过入场动画的消息（原型每条消息挂载时 rwin 0.25s ease；切换对话时全部重播）。 */
    private val seen = HashSet<String>()

    private fun appear(key: String, e: Element): Element {
        if (seen.add(key)) e.rwin()
        return e
    }

    init {
        scroll.setScrollingDisabledX(true)
        scroll.setOverscroll(false, true)
        // 滚轮不触发 panning/flinging，需要单独暂停跟底；此监听在 ScrollPane 自身滚动之后执行。
        scroll.addListener(object : InputListener() {
            override fun scrolled(event: InputEvent, x: Float, y: Float, sx: Float, sy: Float): Boolean {
                if (sy != 0f) stick = scroll.isBottomEdge
                return false
            }
        })
        list.margin(dp(8f), dp(20f), dp(20f), dp(20f))
        // 不在底部时右下方浮一个「回到底部」圆钮（参考 Claude App）
        toBottom.name = "回到底部"
        toBottom.border = K.line
        toBottom.add(K.svg(PI.arrowDown, 20f, K.text, 1.8f))
        toBottom.color.a = 0f
        val stack = arc.scene.ui.layout.Stack()
        stack.add(scroll)
        stack.add(Table().bottom().apply { add(toBottom).size(dp(44f)).padBottom(dp(12f)) })
        stack.add(selection.overlay)
        stack.add(selection.menuLayer)
        add(stack).grow()
        addCaptureListener(press)
    }

    /** 取消文字选区（返回键先关它）；没有选区返回 false。 */
    fun clearSelection(): Boolean = selection.clear()

    /** 长按满 0.45 秒不动：回复直接选词（不松手可接着拖），用户消息弹「复制消息 / 选择文本」。 */
    private fun checkLongPress() {
        if (!pressing || pressMoved || pressLong || System.currentTimeMillis() - pressAt < 450) return
        pressLong = true
        val root = pressRoot ?: return
        val sel = root.userObject as? Selectable ?: return
        scroll.cancel()
        ChatSelection.takeTouch(press, this)
        if (sel.bubble) {
            selection.clear()
            actions.popup(listOf(
                MenuHost.Item("复制消息", PI.copy) { actions.copy(sel.text) },
                MenuHost.Item("选择文本", PI.selectAll) { selection.selectAll(root) },
            ), root)
        } else {
            selection.begin(root, pressX, pressY)
            selection.extendFromLongPress()
        }
    }

    /** 正在往上翻看旧内容（不在底部、没在跟随）；输入框据此收成一行。 */
    val readingBack: Boolean get() = back

    /**
     * 带回差的「在往上翻」：离底部超过 [BACK_ENTER] 才算翻上去，回到离底 [BACK_EXIT] 以内才算回来。
     * 输入框收起 / 展开会让对话区高度变 60dp 左右，回差要比这个大，否则判断来回翻转、输入框抽搐。
     */
    private var back = false

    /**
     * 翻回到底部附近（离底 [BACK_EXIT] 内）时输入框会展开、对话区变矮，底部又被顶出 60dp 左右；
     * 惯性没正好停在底边时 [stick] 还没恢复，就会停在半截、要再拉一次。记下来，手一松就贴底。
     */
    private var reachedBottom = false
    private var wasPanning = false
    private var lastScrollH = 0f
    private var lastFromBottom = 0f
    /** 重建后要放回去的位置（翻看旧内容时）和还剩几帧可等。 */
    private var holdY = 0f
    private var holdFrames = 0

    private fun updateBack() {
        val fromBottom = scroll.maxY - scroll.scrollY
        val wasBack = back
        back = when {
            stick || scroll.maxY <= dp(24f) -> false
            back -> fromBottom > BACK_EXIT
            else -> fromBottom > BACK_ENTER
        }
        if (wasBack && !back && !stick) reachedBottom = true
    }

    fun scrollToBottom() {
        stick = true
        scroll.scrollY = scroll.maxY
    }

    override fun act(delta: Float) {
        super.act(delta)
        val c = conversation()
        if (c?.id != shownConv) {
            cells.clear()
            approvalCells.clear()
            seen.clear()
            shownConv = c?.id
            shownVersion = -1
            stick = true
            reachedBottom = false
            selection.clear()
        }
        if (wb.version != shownVersion || checkVersion != shownCheck) {
            shownVersion = wb.version
            shownCheck = checkVersion
            // 助手回复时每次更新都会重建正在变的那一轮：新控件第一帧还没排好版，内容会短一下，
            // ScrollPane 把位置夹到「底部」，看起来像自己跳回了底部。翻看旧内容时记住位置，排好后放回去。
            if (!stick) { holdY = scroll.scrollY; holdFrames = 4 }
            rebuild(c)
        }
        // 拖动时不抢回位置（包括靠近底部的小幅上滑）；真正翻到底后再恢复跟随。
        checkLongPress()
        selection.act()
        val touching0 = scroll.isPanning || scroll.isFlinging || scroll.isDragging
        // 输入框展开有动画，底部会被顶出去不止 80dp：不按距离作废「回到底部」，只有手指重新按下拖动才作废
        if (scroll.isPanning && !wasPanning) reachedBottom = false
        wasPanning = scroll.isPanning
        if (holdFrames > 0) {
            if (touching0 || stick) holdFrames = 0
            else {
                holdFrames--
                if (scroll.maxY >= holdY) { scroll.scrollY = holdY; scroll.updateVisualScroll(); holdFrames = 0 }
            }
        }
        // 输入框收起 / 展开会改对话区高度；ScrollPane 按「离顶部」保位置，靠近底部时底部就被顶走或露出空白。
        // 离底在 BACK_ENTER 以内时改成按「离底部」保位置：展开后还在原来离底的地方，一次就能拉到底。
        val h = scroll.height
        if (lastScrollH > 0f && h != lastScrollH && !stick && lastFromBottom <= BACK_ENTER) {
            // 内容和位置一起挪同样的距离（输入框高度是动画，每帧只挪一点）：不夹到底边、不打断惯性和回弹，看起来是连着的
            scroll.setScrollYForce(scroll.scrollY + (lastScrollH - h))
        }
        lastScrollH = h
        if (selection.dragging != 0) { if (!scroll.isBottomEdge) stick = false }
        else if (touching0) stick = scroll.isBottomEdge
        // 还在等内容排好、位置放回去的时候，暂时「在底部」不算数
        else if (scroll.isBottomEdge && holdFrames == 0) stick = true
        // 手指还在拖 / 回弹中就不往底部拽，否则在底部继续下拉时和回弹打架、来回抽
        val touching = scroll.isPanning || scroll.isFlinging || scroll.isDragging
        if (reachedBottom && !touching && selection.dragging == 0) { reachedBottom = false; stick = true }
        // 松手 / 惯性停下时离底只差一点（BACK_EXIT 以内）：直接贴底，不留一条缝
        if (!stick && !touching && selection.dragging == 0 && holdFrames == 0 && scroll.maxY - scroll.scrollY <= BACK_EXIT) stick = true
        if (stick && !touching) scroll.scrollY = scroll.maxY
        updateBack()
        lastFromBottom = scroll.maxY - scroll.scrollY
        toBottomAlpha.to(if (readingBack) 1f else 0f).update(delta)
        toBottom.color.a = toBottomAlpha.get()
        toBottom.visible = toBottomAlpha.get() > 0.01f
        toBottom.touchable = if (readingBack) Touchable.enabled else Touchable.disabled
    }

    private fun rebuild(c: CodexConversation?) {
        val approvals = c?.approvals.orEmpty().map { approval ->
            val previous = approvalCells[approval.id]
            if (previous?.first == approval) previous.second
            else approvalCard(approval).also { approvalCells[approval.id] = approval to it }
        }
        approvalCells.keys.retainAll(c?.approvals?.map { it.id }.orEmpty().toSet())
        // 后台状态刷新不应重建正在作答的输入框，也不应让 Android 输入法失焦。
        val focused = arc.Core.scene.keyboardFocus
        approvals.firstOrNull { focused?.isDescendantOf(it) == true }?.let { card ->
            card.parent?.removeChild(card, false)
            tail.parent?.removeChild(tail, false)
        }
        list.clearChildren()
        questionDrafts.keys.retainAll(c?.approvals?.map { it.id }.orEmpty().toSet())
        if (c == null) return
        val retainedTurns = HashSet<String>()
        val lastDone = c.turns.lastOrNull { it.status != "inProgress" }
        for (t in c.turns) {
            val key = signature(t) + (if (t === lastDone) "|last" else "")
            val cached = cells[t.id]
            val first = t === c.turns.first()
            val key2 = key + (if (first) "|first" else "")
            val e = if (cached != null && cached.first == key2) cached.second else turnView(t, c, t === lastDone, first).also { cells[t.id] = key2 to it }
            retainedTurns += t.id
            list.add(e).growX().minWidth(0f).row()
        }
        cells.keys.retainAll(retainedTurns)
        tail.clearChildren()
        // 刚发出、Codex 还没回显的消息先显示出来；发送失败时也留着，免得消息凭空消失
        c.pendingPrompt?.takeIf { p -> (c.status.active || c.lastError != null) && c.turns.none { t -> t.items.any { it.type == "userMessage" && it.text.trim() == p } } }
            ?.let { tail.add(appear("u:${c.turns.size}:${it.trim()}", bubble(it))).growX().padTop(if (c.turns.isEmpty()) 0f else dp(16f)).row() }
        c.approvals.zip(approvals).forEach { (a, card) -> tail.add(appear("a:${a.id}", card)).growX().padTop(dp(12f)).row() }
        if (c.status.active && c.approvals.isEmpty()) {
            if ("working" !in seen) working.restart()
            tail.add(appear("working", working)).left().expandX().padTop(dp(16f)).row()
        }
        else seen.remove("working")
        c.lastError?.takeIf { error -> !c.status.active && c.turns.lastOrNull()?.error?.trim() != error.trim() }
            ?.let { tail.add(appear("err:$it", K.para(friendlyError(it), 15f, K.danger, lh = 23f))).growX().padTop(dp(16f)).row() }
        // 自动检查：最后一轮改过 INI 就跑一遍锈匠检查器，结果显示在回复下面
        if (!c.status.active && lastDone != null) {
            actions.turnCheck(lastDone) { arc.Core.app.post { checkVersion++ } }?.let { tail.add(appear("check:${lastDone.id}", checkRow(it))).left().padTop(dp(14f)).row() }
        }
        // 原型：对话结束后底部「产物 · N」入口
        if (!c.status.active) {
            val n = c.turns.filter { it.status != "inProgress" }.flatMap { actions.produced(it) }.distinctBy { it.path ?: it.id }.size
            if (n > 0) tail.add(appear("chip", artifactsChip(n))).left().padTop(dp(16f)).row()
        }
        list.add(tail).growX().minWidth(0f).row()
        list.add().grow()
    }

    private fun signature(t: CodexTurn): String = buildString {
        append(t.status).append(t.completedAt).append(t.items.size).append(t.error).append(t.deletedRoles.size)
        for (i in t.items) append('|').append(i.type).append(i.status).append(i.text.length).append(i.output.length)
    }

    private fun turnView(t: CodexTurn, c: CodexConversation, last: Boolean, first: Boolean): Element {
        val box = Table().top()
        // 原型：消息之间 gap 16，列表顶部只有 8 的内边距
        var gap = if (first) 0f else dp(16f)
        fun next(): Float = gap.also { gap = dp(16f) }
        val user = t.items.firstOrNull { it.type == "userMessage" }
        if (user != null && "user" !in t.deletedRoles) {
            box.add(appear("u:${c.turns.indexOf(t)}:${user.text.trim()}", bubble(user.text))).growX().padTop(next()).row()
        }
        if ("assistant" in t.deletedRoles) return box
        // 过程条目（推理、命令、文件改动、工具）按出现顺序聚成「已运行 …」行，与回复穿插
        var pending = ArrayList<CodexItem>()
        fun flush() {
            if (pending.isEmpty()) return
            val items = pending
            pending = ArrayList()
            box.add(appear("s:" + items.first().id, stepsRow(t, items, running = t.status == "inProgress" && items.last() === t.items.lastOrNull { it.type != "agentMessage" }))).left().minWidth(0f).padTop(next()).row()
        }
        val done = t.status != "inProgress"
        val lastReply = t.items.lastOrNull { it.type == "agentMessage" && it.text.isNotBlank() }
        // 产物不在对话里铺卡片，统一收进底部「产物 · N」
        // 结束了：之前的步骤和中途回复全部收进一行「已工作 X 分 Y 秒 ›」，下面一条分隔线，只留最后的交付
        val process = if (done) t.items.filter { it.type != "userMessage" && it !== lastReply && !(it.type == "agentMessage" && it.text.isBlank()) } else emptyList()
        if (done && process.isNotEmpty()) {
            box.add(appear("w:" + t.id, workedRow(t, process))).left().minWidth(0f).padTop(next()).row()
            box.add(Rule()).growX().height(dp(1f)).padTop(dp(10f)).row()
            gap = dp(14f)
            lastReply?.let { i -> box.add(appear("m:" + i.id, MarkdownView(mdStyle).apply { onLink = this@ChatView.actions::openLink; setMarkdown(stripCitations(i.text)); userObject = Selectable(false, stripCitations(i.text)) })).growX().padTop(next()).row() }
        } else for (i in t.items) {
            when (i.type) {
                "userMessage" -> {}
                "agentMessage" -> {
                    flush()
                    if (i.text.isNotBlank()) box.add(appear("m:" + i.id, MarkdownView(mdStyle).apply { onLink = this@ChatView.actions::openLink; setMarkdown(stripCitations(i.text)); userObject = Selectable(false, stripCitations(i.text)) })).growX().padTop(next()).row()
                }
                else -> pending += i
            }
        }
        if (!(done && process.isNotEmpty())) flush()
        t.error?.let { box.add(appear("e:" + t.id, K.para(friendlyError(it), 15f, K.danger, lh = 23f))).growX().padTop(next()).row() }
        if (t.status == "interrupted") box.add(appear("i:" + t.id, note("你停止了这次回复"))).growX().padTop(next()).row()
        val reply = t.items.lastOrNull { it.type == "agentMessage" }?.text
        if (last && reply != null && t.status != "inProgress") {
            val bar = Table()
            bar.add(iconButton(PI.copy, 19f, 40f, K.subtle, stroke = 1.6f) { actions.copy(stripCitations(reply)) }.apply { name = "复制" })
            bar.add(iconButton(PI.retry, 19f, 40f, K.subtle, stroke = 1.6f) { actions.regenerate(t) }.apply { name = "重新生成" }).padLeft(dp(2f))
            box.add(appear("bar:" + t.id, bar)).left().padLeft(-dp(10f)).padTop(dp(4f)).row()
        }
        return box
    }

    /** 「已检查 N 个文件，没有问题」或「检查发现 2 个错误、1 个提醒 ›」（点开看明细）。 */
    private fun checkRow(r: TurnCheck): Element {
        val ok = r.errors + r.warns == 0
        val row = Tap(null, 8f) { if (!ok) actions.openCheck(r) }
        row.name = "检查结果"
        row.add(K.svg(if (ok) PI.check else PI.warn, 16f, if (ok) K.green else K.c("E9A23B"), 2f)).padRight(dp(8f))
        val text = if (ok) "已检查 ${r.files.size} 个 INI 文件，没有问题"
            else "检查发现 " + listOfNotNull(r.errors.takeIf { it > 0 }?.let { "$it 个错误" }, r.warns.takeIf { it > 0 }?.let { "$it 个提醒" }).joinToString("、")
        row.add(K.text(text, 14f, if (ok) K.subtle else K.text, lh = 14f))
        if (!ok) row.add(K.svg(PI.chevronRight, 14f, K.subtle, 2f)).padLeft(dp(4f))
        row.margin(dp(4f), 0f, dp(4f), dp(4f))
        return row
    }

    private fun artifactsChip(n: Int) = Tap(null, 12f) { actions.openLibrary() }.apply {
        border = K.line
        name = "产物"
        margin(0f, dp(16f), 0f, dp(16f))
        add(K.svg(PI.layers, 18f, K.muted, 1.6f)).padRight(dp(10f))
        add(K.text("产物", 15f, K.text, 500, 15f)).padBottom(dp(2f))
        add(K.text(" · $n", 15f, K.subtle, lh = 15f)).padBottom(dp(2f))
    }.also { it.minH = dp(44f) }

    /** 用户消息：正文气泡 + 下面一排附件（图片缩略图 / 文件名卡片），都靠右。 */
    private fun bubble(message: String): Element {
        val (text, paths) = Attachments.split(message)
        if (paths.isEmpty()) return textBubble(text)
        val col = Table().right()
        if (text.isNotBlank()) col.add(textBubble(text)).growX().row()
        val row = Table().right()
        for (p in paths) row.add(attachmentCard(p)).padLeft(dp(8f))
        col.add(row).right().padTop(if (text.isNotBlank()) dp(8f) else 0f)
        return col
    }

    private fun attachmentCard(path: String): Element {
        val file = actions.attachmentFile(path)
        if (file != null && Attachments.isImage(path)) return Table(K.fill(K.bubble, 14f)).apply {
            name = "附件"
            add(io.github.zenthxsin.rustwright.ui.kit.ImageThumb(file)).size(dp(112f))
        }
        return Table(K.fill(K.bubble, 14f)).apply {
            name = "附件"
            margin(dp(10f), dp(12f), dp(10f), dp(14f))
            add(K.svg(if (Attachments.isImage(path)) PI.img else PI.fileUp, 18f, K.muted, 1.6f)).padRight(dp(8f))
            add(K.text(path.substringAfterLast('/').replace(Regex("^\\d{8}-\\d{6}-(\\d+-)?"), ""), 14f, K.text, lh = 14f).apply { setEllipsis(true) })
                .maxWidth(dp(180f)).minWidth(0f)
        }
    }

    private fun textBubble(text: String): Element {
        val label = K.para(text, 16f, K.text, lh = 26f)
        val b = Table().apply {
            background(K.fill(K.bubble, 20f))
            userObject = Selectable(true, text)
            add(label).growX()
            margin(dp(11f), dp(16f), dp(11f), dp(16f))
        }
        // 宽度 = 文字自然宽度，最宽 82%；按自身宽度算，不依赖父级先排好版
        val natural = text.lines().maxOf { K.measure(label.style.font, it) } + dp(32f) + dp(8f)
        return object : Table() {
            init {
                right()
                add(b).right()
            }

            override fun getPrefWidth() = parent?.width ?: super.getPrefWidth()

            override fun layout() {
                getCell(b)?.width(natural.coerceAtMost((width * 0.82f).coerceAtLeast(dp(80f))))
                super.layout()
            }
        }
    }

    /** 一轮结束后的折叠行：「已工作 44 分 31 秒 ›」（没有可靠的计时就用步骤摘要），点开看全部步骤和中途回复。 */
    private fun workedRow(t: CodexTurn, items: List<CodexItem>): Element {
        val row = Tap(null, 8f) { actions.openSteps(t, items) }
        row.name = "已工作"
        // 放不下就省略：不换行的长文字会把整个对话列表撑宽，内容左右两边都被裁掉
        row.add(K.text(worked(t) ?: summary(items.filter { it.type != "agentMessage" }.ifEmpty { items }), 15f, K.subtle, lh = 19.5f).apply { setEllipsis(true) }).minWidth(0f)
        row.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(8f))
        row.margin(dp(2f), 0f, dp(2f), dp(2f))
        return row
    }

    private class Rule : Element() {
        override fun draw() = Shapes.rect(x, y, width, height, 0f, Color(K.line).a(parentAlpha))
    }

    /** 「已运行 3 条命令，修改了 2 个文件 ›」；运行中时前面一个闪烁小点、文字是当前步骤。 */
    private fun stepsRow(t: CodexTurn, items: List<CodexItem>, running: Boolean): Element {
        val row = Tap(null, 8f) { actions.openSteps(t, items) }
        if (running) row.add(PulseDot()).size(dp(8f)).padRight(dp(8f))
        row.add(K.text(if (running) doing(items.last()) else summary(items), 15f, K.subtle, lh = 19.5f).apply { setEllipsis(true) }).minWidth(0f)
        row.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(8f))
        row.margin(dp(2f), 0f, dp(2f), dp(2f))
        return row
    }

    /** 原型的系统提示：灰色小圆点 + 13px 文字。 */
    private fun note(text: String) = Table().left().apply {
        add(object : Element() {
            override fun draw() = Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(K.subtle).a(parentAlpha))
        }).size(dp(6f)).padRight(dp(6f))
        add(K.para(text, 13f, K.subtle)).growX().left()
    }

    private fun approvalCard(a: CodexApproval): Element = Table(K.card(K.surface, 14f)).apply {
        margin(dp(14f))
        val title = when (a.kind) {
            CodexApproval.Kind.COMMAND -> "${AppBrand.identity.assistant}想运行命令"
            CodexApproval.Kind.FILE_CHANGE -> "${AppBrand.identity.assistant}想修改文件"
            CodexApproval.Kind.TOOL -> "${AppBrand.identity.assistant}想使用工具"
            CodexApproval.Kind.QUESTION -> "${AppBrand.identity.assistant}有个问题"
            else -> "需要你确认"
        }
        add(K.para(title, 15f, K.text, 600)).growX().minWidth(0f).left().row()
        a.reason?.let { add(K.para(it, 14f, K.muted)).growX().left().padTop(dp(6f)).row() }
        if (a.kind == CodexApproval.Kind.QUESTION) {
            val draft = questionDrafts.getOrPut(a.id) { QuestionDraft() }
            val answers = draft.selected
            val fields = LinkedHashMap<String, TextField>()
            val hint = K.para("", 13f, K.danger, lh = 20f)
            for (q in a.questions) {
                val selected = answers.getOrPut(q.id) { linkedSetOf() }
                add(K.para(q.question, 15f, K.text, lh = 22f)).growX().minWidth(0f).left().padTop(dp(12f)).row()
                for (option in q.options) {
                    val button = Tap(null, 10f) {
                        if (option in selected) selected.remove(option)
                        else { if (!q.multiSelect) selected.clear(); selected += option }
                        hint.setText("")
                    }.apply {
                        name = "回答选项:$option"
                        border = K.inputBorder
                        margin(dp(10f), dp(12f), dp(10f), dp(12f))
                        add(K.para(option, 14f, K.text, lh = 20f)).growX().minWidth(0f)
                        update { fill(if (option in selected) K.surface3 else null); border = if (option in selected) K.accent else K.inputBorder }
                    }
                    add(button).growX().padTop(dp(6f)).row()
                }
                if (q.allowOther || q.options.isEmpty()) {
                    val field = TextField(draft.text[q.id].orEmpty()).apply {
                        name = "回答:${q.id}"
                        setMessageText(if (q.options.isEmpty()) "输入回答" else "其他回答（可选）")
                        setPasswordMode(q.secret)
                        changed { draft.text[q.id] = text; hint.setText("") }
                    }
                    fields[q.id] = field
                    add(field).growX().minWidth(0f).height(dp(42f)).padTop(dp(8f)).row()
                }
            }
            add(hint).growX().padTop(dp(6f)).row()
            add(pill("提交回答", 42f, 12f, K.light, K.onLight, weight = 600) {
                val result = a.questions.associate { q ->
                    val typed = fields[q.id]?.text?.trim()?.takeIf { it.isNotBlank() }
                    q.id to (if (typed != null && !q.multiSelect) listOf(typed)
                        else answers[q.id].orEmpty().toList() + listOfNotNull(typed))
                }
                if (result.isEmpty() || result.values.any { it.isEmpty() }) hint.setText("请回答每个问题后提交")
                else wb.answer(a.id, CodexAnswer.Answers(result))
            }).growX().padTop(dp(8f))
            return@apply
        }
        a.command?.let {
            add(Table(K.fill(K.bubble, 10f)).apply { add(K.mono(it, 13f, K.text).apply { setWrap(true) }).growX().minWidth(0f).left(); margin(dp(10f), dp(12f), dp(10f), dp(12f)) })
                .growX().minWidth(0f).padTop(dp(10f)).row()
        }
        val buttons = Table()
        buttons.add(pill("拒绝", 40f, 12f, null, K.text) { wb.answer(a.id, CodexAnswer.Decline) }.apply { border = K.inputBorder }).growX().padRight(dp(10f))
        buttons.add(pill("允许", 40f, 12f, K.light, K.onLight, weight = 600) { wb.answer(a.id, CodexAnswer.Accept) }).growX()
        add(buttons).growX().padTop(dp(12f))
    }

    companion object {
        private val BACK_ENTER get() = dp(120f)
        private val BACK_EXIT get() = dp(16f)

        /** 助手回复：16/27 正文，行内代码浅橙，代码块深底圆角（原型 Chat）。 */
        private var mdCache: Pair<Boolean, MarkdownView.Style>? = null
        /** 按当前深浅缓存一份（换主题后重建界面时重新取）。 */
        val mdStyle: MarkdownView.Style get() = mdCache?.takeIf { it.first == RwTheme.dark }?.second ?: buildMd().also { mdCache = RwTheme.dark to it }

        private fun buildMd() = run {
            MarkdownView.Style(
                body = RwTheme.richText(16f, 400, 27f),
                heading = RwTheme.richText(18f, 600, 28f),
                code = RwTheme.monoAt(12.5f, 20f),
                text = K.text,
                muted = K.subtle,
                codeInline = K.accentLight,
                strong = K.light,
                codeText = K.dl("D6D4CC", "2B2B28"),
                codeBg = K.card(K.dl("121211", "EEEDE8"), 12f),
                quoteBar = K.inputBorder,
                rule = K.line,
                gap = 12f,
                itemGap = 4f,
                link = K.accentLight,
            )
        }

        /** 已完成的一组过程条目的摘要。 */
        /** 「已工作 44 分 31 秒」；历史记录里没有结束时间（或时长不合理）时返回 null。 */
        fun worked(t: CodexTurn): String? {
            val end = t.completedAt ?: return null
            val sec = (end - t.startedAt) / 1000
            if (sec < 1 || sec > 24 * 3600) return null
            val h = sec / 3600
            val m = sec % 3600 / 60
            val s = sec % 60
            return "已工作 " + when {
                h > 0 -> "$h 小时 $m 分"
                m > 0 -> "$m 分 $s 秒"
                else -> "$s 秒"
            }
        }

        fun summary(items: List<CodexItem>): String {
            val cmds = items.count { it.type == "commandExecution" }
            val files = items.filter { it.type == "fileChange" }.sumOf { it.changes.size.coerceAtLeast(1) }
            val tools = items.count { it.type == "dynamicToolCall" || it.type == "mcpToolCall" }
            val think = items.count { it.type == "reasoning" }
            val parts = ArrayList<String>()
            if (cmds > 0) parts += "已运行 $cmds 条命令"
            if (files > 0) parts += (if (parts.isEmpty()) "已修改 " else "修改了 ") + "$files 个文件"
            if (tools > 0) parts += (if (parts.isEmpty()) "已使用 " else "使用了 ") + "$tools 个工具"
            if (parts.isEmpty() && think > 0) return "已思考"
            return parts.joinToString("，").ifEmpty { "已完成 ${items.size} 步" }
        }

        /** 正在进行的那一步。 */
        fun doing(i: CodexItem): String = when (i.type) {
            "reasoning" -> "正在思考…"
            "commandExecution" -> "正在运行 " + shortCommand(i.command.orEmpty())
            "fileChange" -> "正在修改文件…"
            "dynamicToolCall", "mcpToolCall" -> "正在" + toolVerb(i.tool)
            else -> "正在处理…"
        }

        fun toolVerb(tool: String?): String = when {
            tool == null -> "使用工具…"
            "image" in tool -> "生成贴图…"
            "map" in tool -> "生成地图…"
            "check" in tool || "lint" in tool -> "检查模组…"
            else -> "使用 $tool…"
        }

        fun shortCommand(cmd: String): String {
            val one = cmd.lineSequence().firstOrNull().orEmpty().trim()
            return if (one.length > 28) one.take(27) + "…" else one
        }
    }
}

/** 锈橙闪烁小点（运行中的步骤行）。 */
private class PulseDot : Element() {
    private var t = 0f

    init {
        touchable = Touchable.disabled
    }

    override fun act(delta: Float) {
        super.act(delta)
        t += delta
    }

    override fun draw() {
        val a = io.github.zenthxsin.rustwright.ui.kit.pulse(t, 1f)
        Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(K.accent).a(a * parentAlpha))
    }
}

/** 把中转站 / Codex 的原始报错（状态码、request id、网址）换成一句能照着做的话。 */
internal fun friendlyError(raw: String): String {
    // 第一行是服务端原始报错，后面几行是我们自己加的说明（如「2 秒后自动重试」），原样保留
    val lines = raw.lines()
    val note = lines.drop(1).filter { it.isNotBlank() }.joinToString("\n")
    val head = friendlyLine(lines.first())
    return if (note.isEmpty()) head else "$head\n$note"
}

private fun friendlyLine(raw: String): String {
    val m = raw.lowercase()
    return when {
        "benefit_request_not_allowed" in m -> "免费线路拒绝了这次请求，可以换个付费模型，或稍后再试"
        "401" in m || "unauthorized" in m || "authorization is invalid" in m || "expired" in m -> "登录已失效，请到「设置 › 个人资料」重新登录后再试"
        "403" in m || "forbidden" in m -> "当前账号没有这个模型的权限，换个模型试试"
        "429" in m || "rate limit" in m || "quota" in m || "insufficient" in m -> "请求太频繁或额度不足，稍后再试，或到「设置 › 充值与账单」看看余额"
        "timeout" in m || "timed out" in m || "unknownhost" in m || "connection" in m || "stream disconnected" in m || "network" in m -> "网络断开了，检查网络后重试"
        "500" in m || "502" in m || "503" in m || "504" in m -> "服务暂时不可用，稍后再试"
        else -> "出错了：" + raw.lineSequence().first()
            .replace(Regex("\\s*\\(request id:[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*url:\\s*\\S+", RegexOption.IGNORE_CASE), "")
            .trim().take(160)
    }
}

/**
 * 模型联网搜索后会在回复里插入引用标记：私有区字符包着的 `cite turn123view2` 之类。
 * 我们不展示来源卡片，整段去掉（字体里也没有这些字符，会显示成方框）。
 */
internal fun stripCitations(text: String): String =
    text.replace(CITATION, "").replace(Regex("[\uE200-\uE2FF]"), "").replace(Regex("[ \t]+\n"), "\n").trimEnd()

private val CITATION = Regex("\uE200[^\uE201]*\uE201")
