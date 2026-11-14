package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.graphics.Color
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.layout.Reveal
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.core.codex.CodexMode
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.core.account.NewApiBenefits
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.SvgIcon
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.iconButton

/** 显示用的中文名与说明（原型 MODELS / EFFORTS / IMG_MODELS / PERMS）。 */
object Labels {
    fun model(id: String, wb: CodexWorkbench): String =
        if (id.startsWith(NewApiBenefits.MODEL_PREFIX)) NewApiBenefits.displayModel(id) + " · 免费"
        else wb.models.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id

    /** 模型说明与标签：按名字认出常见几类。 */
    fun modelInfo(id: String, name: String): Pair<String, String?> {
        val n = (id + " " + name).lowercase()
        return when {
            id.startsWith(NewApiBenefits.MODEL_PREFIX) -> "登录福利 · 思考不计入额度" to "免费"
            "deepseek" in n -> "便宜，适合批量改数值" to "低价"
            "codex" in n -> "大量改文件、跑命令时更稳" to null
            "mini" in n || "flash" in n || "haiku" in n -> "简单问答和小改动，省额度" to null
            else -> "复杂任务：多步改模组、连着出图出地图" to null
        }
    }

    val efforts = listOf("minimal" to "最低", "low" to "低", "medium" to "中等", "high" to "高", "xhigh" to "超高")

    fun effort(id: String): String = efforts.firstOrNull { it.first == id }?.second ?: id

    /** 生图模型：说明、标签、提醒。 */
    fun imageInfo(id: String): Triple<String?, String?, String?> = if (id.startsWith(NewApiBenefits.MODEL_PREFIX))
        Triple("每次 1 张 1024 PNG · 与其他生成工具共享每日次数", "免费", null) else when (id) {
        "gpt-image-1" -> Triple("理解提示词最好，适合单位和建筑贴图", "推荐", null)
        "flux-kontext-pro" -> Triple("擅长在已有贴图上局部修改", null, null)
        "seedream-4" -> Triple("便宜，适合批量出草图", "低价", null)
        "midjourney-v7" -> Triple("风格化强，适合封面和宣传图", null, "单张较贵")
        else -> Triple(null, null, null)
    }

    /** 原型的三种模式 ↔ Codex 审批模式。 */
    val modes = listOf(
        Triple(CodexMode.AUTO, "自动", "由${AppBrand.identity.assistant}处理权限决策"),
        Triple(CodexMode.CODE, "逐次审批", "命令和文件修改由你确认"),
        Triple(CodexMode.ASK, "只读", "只查看和回答，不修改文件"),
    )
    val modeIcons = mapOf(CodexMode.AUTO to (PI.bolt to K.c("E0864F")), CodexMode.CODE to (PI.codeBrackets to K.c("9B7BF0")), CodexMode.ASK to (PI.eye to K.c("3FB5A5")))

    fun mode(m: CodexMode): String = modes.firstOrNull { it.first == m }?.second ?: if (m == CodexMode.FULL) "完全访问" else m.label
}

/** 底部弹层的各页内容（原型里的选择模型 / 思考强度 / 生图模型 / 选择模式 / 添加上下文 / 步骤 / 用量）与对话菜单。 */
object Sheets {

    /** 原型 pickRows / models 的一行：可带彩色图标、标签、提醒、说明，选中项右侧打勾。 */
    private fun option(
        label: String, desc: String?, selected: Boolean, icon: Pair<String, Color>? = null, tagText: String? = null,
        warn: String? = null, highlight: Boolean = false, minHeight: Float = 60f, gap: Float = 14f, onPick: () -> Unit,
    ): Tap = Tap(K.surface, 0f, onPick).apply {
        margin(dp(12f), dp(18f), dp(12f), dp(18f))
        minH = dp(minHeight)
        name = label
        if (icon != null) add(K.svg(icon.first, 24f, icon.second, 1.7f)).padRight(dp(gap))
        val on = highlight && selected
        val col = Table().left()
        val titleRow = Table()
        titleRow.add(K.text(label, 17f, if (on) K.accentLight else K.text, lh = 17f).apply { setEllipsis(true) }).minWidth(0f)
        if (tagText != null) titleRow.add(chip(tagText)).padLeft(dp(8f))
        if (warn != null) titleRow.add(warnChip(warn)).padLeft(dp(8f))
        col.add(titleRow).left().minWidth(0f).row()
        // 说明在窄屏上换行（浏览器默认行为），不把整行撑出卡片
        if (desc != null) col.add(K.para(desc, 13f, if (on) K.accentText else K.subtle, lh = 16.9f)).growX().left().padTop(dp(6f))
        add(col).growX().minWidth(0f).left()
        if (selected) add(K.svg(PI.check, 22f, K.accentText, 2f)).padLeft(dp(gap))
    }

    /** 灰色小标签（推荐 / 低价）：12px 600，内边距 2 7，圆角 6。 */
    private fun chip(text: String) = Table(K.fill(K.surface3, 6f)).apply {
        add(K.text(text, 12f, K.muted, 600, 15.6f))
        margin(dp(2f), dp(7f), dp(2f), dp(7f))
    }

    /** 橙色提醒标签（单张较贵）。 */
    private fun warnChip(text: String) = Table(K.fill(K.dl("3A2614", "F6E7D3"), 6f)).apply {
        add(K.svg(PI.warn, 12f, K.c("E9A23B"), 2.2f)).padRight(dp(4f))
        add(K.text(text, 12f, K.c("E9A23B"), 600, 15.6f))
        margin(dp(2f), dp(8f), dp(2f), dp(8f))
    }

    /** 带当前值和箭头的入口行（思考强度 / 生图模型）。 */
    private fun valueRow(label: String, value: String, onClick: () -> Unit) = Tap(K.surface, 0f, onClick).apply {
        margin(0f, dp(18f), 0f, dp(18f))
        minH = dp(60f)
        name = label
        add(K.text(label, 17f, K.text, lh = 17f)).left()
        add(K.text(value, 16f, K.muted, lh = 16f).apply { setEllipsis(true); setAlignment(arc.util.Align.right) }).growX().minWidth(0f).padLeft(dp(10f))
        add(K.svg(PI.chevronRight, 18f, K.subtle, 2f)).padLeft(dp(10f))
    }

    private fun note(text: String) = K.para(text, 14f, K.subtle, lh = 22f)

    // ---- 选择模型 ----

    fun model(s: FusedShell) {
        s.host.refreshBenefits()
        s.sheets.open(modelPage(s))
    }

    /** 第一层只放四行「名称 · 当前值 ›」，各自点进下一级选（语言模型和生图模型一样收纳，不再平铺一长串）。 */
    private fun modelPage(s: FusedShell) = SheetPage("选择模型", modelBody(s), height = 420f)

    private fun modelBody(s: FusedShell): Element = Table().top().apply {
        var key = ""
        fun key() = listOf(s.model, s.effort, s.host.imageModel(), s.host.embeddingModel(), s.host.benefitsState().data?.textAvailable, s.host.chatModels().size).joinToString("|")
        var nextCheck = 0L
        fun build() { key = key(); clearChildren(); add(modelRows(s)).growX() }
        build()
        update {
            val now = System.nanoTime()
            if (now < nextCheck) return@update
            nextCheck = now + 500_000_000L
            if (key() != key) build()
        }
    }

    private fun currentModelName(s: FusedShell): String {
        val id = s.model
        return if (id.startsWith(NewApiBenefits.MODEL_PREFIX)) NewApiBenefits.displayModel(id) + " · 免费"
        else s.host.chatModels().firstOrNull { it.id == id }?.name?.ifBlank { null } ?: id
    }

    private fun modelRows(s: FusedShell): Element = Table().top().apply {
        val g = ListGroup()
        g.row(valueRow("语言模型", currentModelName(s)) { chatModel(s) })
        g.row(valueRow("思考强度", Labels.effort(s.effort)) { effort(s) })
        g.row(valueRow("生图模型", NewApiBenefits.displayModel(s.host.imageModel())) { imageModel(s) })
        g.row(valueRow("向量模型", s.host.embeddingModel().ifBlank { "未开启" }) { embeddingModel(s) })
        add(g).growX().row()
        val g2 = ListGroup()
        g2.row(valueRow("订阅计划", if (s.host.benefitsState().data?.textAvailable == true) "查看额度" else "查看状态") {
            s.sheets.close()
            s.openTool("订阅计划", BenefitsPage(s.host), s.page)
        })
        add(g2).growX().padTop(dp(14f))
    }

    /** 语言模型列表（二级页）；福利状态、模型列表变了就重建。 */
    private fun chatModel(s: FusedShell) {
        val body = Table().top().apply {
            var state = s.host.benefitsState()
            var models = s.host.chatModels()
            var nextCheck = 0L
            add(chatModelList(s)).growX()
            update {
                val now = System.nanoTime()
                if (now < nextCheck) return@update
                nextCheck = now + 500_000_000L
                val next = s.host.benefitsState()
                val nextModels = s.host.chatModels()
                if (next != state || nextModels != models) {
                    state = next; models = nextModels
                    clearChildren()
                    add(chatModelList(s)).growX()
                }
            }
        }
        s.sheets.push(SheetPage("语言模型", body, height = 540f))
    }

    private fun chatModelList(s: FusedShell): Element = Table().top().apply {
        val all = s.host.chatModels()
        val models = if (all.any { it.id == s.model }) all else listOf(io.github.zenthxsin.rustwright.core.codex.CodexModel(s.model, s.model, emptyList(), null)) + all
        val g = ListGroup()
        for (m in models) {
            val free = m.id.startsWith(NewApiBenefits.MODEL_PREFIX)
            val available = !free || all.any { it.id == m.id }
            val name = if (free) NewApiBenefits.displayModel(m.id) else m.name.ifBlank { m.id }
            val (desc, tag) = Labels.modelInfo(m.id, name)
            g.row(option(name, if (available) desc else "免费额度暂不可用，查看福利状态", m.id == s.model, tagText = tag, minHeight = 68f, gap = 12f) {
                if (!available) { s.notify("免费额度暂不可用，请查看福利状态"); return@option }
                s.setModel(m.id)
                s.sheets.pop()
                s.sheets.replaceTop(modelPage(s))
            })
        }
        add(g).growX()
    }

    private fun effort(s: FusedShell) {
        val supported = s.host.chatModels().firstOrNull { it.id == s.model }?.efforts?.takeIf { it.isNotEmpty() }
        val list = Labels.efforts.filter { supported == null || it.first in supported }
        val body = Table().top().apply {
            val g = ListGroup()
            for ((id, label) in list) g.row(option(label, null, id == s.effort) {
                s.setEffort(id)
                s.sheets.pop()
                s.sheets.replaceTop(modelPage(s))
            })
            add(g).growX().row()
            add(note(if (s.model.startsWith(NewApiBenefits.MODEL_PREFIX)) "思考不计入福利额度。投入程度越高，通常等待越久。" else "投入程度越高，回复越详尽，但耗时越长，也会更快消耗余额。"))
                .growX().padTop(dp(14f)).padLeft(dp(6f)).padRight(dp(6f))
        }
        s.sheets.push(SheetPage("思考强度", body, height = 540f))
    }

    /** 向量模型：长期记忆和语义检索用；最后一项「关闭」。 */
    private fun embeddingModel(s: FusedShell) {
        val list = s.host.embeddingModels()
        if (list.isEmpty()) { s.notify("还没拉到模型列表，登录后再试"); return }
        val body = Table().top().apply {
            val g = ListGroup()
            for (m in list + "") g.row(option(m.ifEmpty { "关闭" }, null, m == s.host.embeddingModel()) {
                s.host.setEmbeddingModel(m)
                s.sheets.pop()
                s.sheets.replaceTop(modelPage(s))
            })
            add(g).growX().row()
            add(note("用于${AppBrand.identity.assistant}的长期记忆和模组文件语义检索。关闭后不影响对话。")).growX().padTop(dp(14f)).padLeft(dp(6f)).padRight(dp(6f))
        }
        s.sheets.push(SheetPage("向量模型", body, height = 540f))
    }

    private fun imageModel(s: FusedShell) {
        val body = Table().top().apply {
            val g = ListGroup()
            for (m in s.host.imageModels()) {
                val (desc, tag, warn) = Labels.imageInfo(m)
                g.row(option(NewApiBenefits.displayModel(m), desc, m == s.host.imageModel(), tagText = tag, warn = warn) {
                    s.host.setImageModel(m)
                    s.sheets.pop()
                    s.sheets.replaceTop(modelPage(s))
                })
            }
            add(g).growX().row()
            add(note("免费对话固定使用登录福利生图；普通对话和手动页使用所选线路。标有「免费」的模型每次生成 1 张 1024 PNG，与其他生成工具共享每日次数。普通模型按线路计费。"))
                .growX().padTop(dp(14f)).padLeft(dp(6f)).padRight(dp(6f))
        }
        s.sheets.push(SheetPage("生图模型", body, height = 540f))
    }

    // ---- 加号：添加上下文 ----

    fun attach(s: FusedShell) {
        s.sheets.open(SheetPage("添加上下文", attachBody(s), height = attachHeight(s)))
    }

    /** 每多一个扩展动作多一行（circleRow 72 + 分隔）。 */
    private fun attachHeight(s: FusedShell) = 380f + 73f * s.host.attachActions().size

    private fun attachBody(s: FusedShell): Element = Table().top().apply {
        val tiles = Table()
        val list = listOf(Triple(PI.camera, "相机", "camera"), Triple(PI.img, "照片", "photo"), Triple(PI.fileUp, "文件", "file"))
        list.forEachIndexed { i, (icon, label, kind) ->
            val t = Tap(null, 14f) { s.sheets.close(); s.attachFrom(kind) }
            t.border = K.line
            t.name = label
            t.add(K.svg(icon, 24f, K.text, 1.5f)).row()
            t.add(K.text(label, 16f, K.text, lh = 16f)).padTop(dp(10f))
            tiles.add(t).growX().uniformX().height(dp(96f)).padLeft(if (i > 0) dp(6f) else 0f).padRight(if (i < list.lastIndex) dp(6f) else 0f)
        }
        add(tiles).growX().row()
        val g = ListGroup()
        g.row(circleRow(PI.layers, "产物库", "把已有的贴图、地图、单位加进对话") { s.sheets.close(); s.show(FusedShell.Page.LIBRARY) })
        g.row(circleRow(PI.bolt, "模式", Labels.mode(s.mode)) { mode(s) })
        for (a in s.host.attachActions()) g.row(circleRow(a.icon, a.label, a.desc) { s.sheets.close(); s.host.ui?.let(a.run) })
        add(g).growX().padTop(dp(14f))
    }

    private fun circleRow(icon: String, title: String, sub: String, onClick: () -> Unit) = Tap(K.surface, 0f, onClick).apply {
        margin(dp(12f), dp(16f), dp(12f), dp(16f))
        minH = dp(72f)
        name = title
        add(Table(K.fill(K.surface3, 22f)).apply { add(K.svg(icon, 20f, K.text, 1.6f)) }).size(dp(44f)).padRight(dp(14f))
        val col = Table().left()
        col.add(K.text(title, 17f, K.text, lh = 17f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        col.add(K.para(sub, 14f, K.subtle, lh = 20f)).growX().minWidth(0f).left().padTop(dp(6f))
        add(col).growX().minWidth(0f).left()
        add(K.svg(PI.chevronRight, 18f, K.subtle, 2f)).padLeft(dp(14f))
    }

    /** 从设置直接打开「选择模式」。 */
    fun modeSheet(s: FusedShell) {
        s.sheets.open(SheetPage("选择模式", modeBody(s) { s.sheets.close() }, sub = "选择${AppBrand.identity.assistant}的工作方式", height = 420f))
    }

    private fun mode(s: FusedShell) {
        s.sheets.push(SheetPage("选择模式", modeBody(s) {
            s.sheets.pop()
            s.sheets.replaceTop(SheetPage("添加上下文", attachBody(s), height = attachHeight(s)))
        }, sub = "选择${AppBrand.identity.assistant}的工作方式", height = 420f))
    }

    private fun modeBody(s: FusedShell, after: () -> Unit) = Table().top().apply {
        val g = ListGroup()
        for ((m, label, desc) in Labels.modes) g.row(option(label, desc, m == s.mode, Labels.modeIcons[m], highlight = true) {
            s.setMode(m)
            after()
        })
        add(g).growX()
    }

    // ---- 步骤时间线与详情 ----

    fun steps(s: FusedShell, items: List<CodexItem>) {
        // 原型：ol padding 4 10 8；每行 图标列 24 + 间距 16；动词 16/26，对象等宽 13/26，右侧小箭头
        val body = Table().top().left()
        // 紧凑排列：图标 20、行高 22、行间 10，竖线只连接相邻两行
        body.margin(dp(2f), dp(10f), dp(8f), dp(10f))
        items.forEachIndexed { i, it ->
            val row = Tap(null, 8f) { stepDetail(s, it) }
            row.name = "step-$i"
            val rail = Table().top()
            rail.add(K.svg(stepIcon(it), 20f, K.muted, 1.6f)).padTop(dp(1f)).row()
            if (i < items.lastIndex) rail.add(VLine()).width(dp(1f)).growY().minHeight(dp(6f)).padTop(dp(4f)).padBottom(dp(2f))
            row.add(rail).width(dp(22f)).growY().top()
            val textRow = Table().left().top()
            textRow.add(K.text(stepVerb(it), 15f, K.text, lh = 22f)).left()
            stepTarget(it)?.let { t -> textRow.add(K.mono(t, 13f, K.subtle, 22f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padLeft(dp(8f)) }
                ?: textRow.add().growX()
            textRow.add(K.svg(PI.chevronRight, 15f, K.dim, 2f)).padLeft(dp(8f))
            row.add(textRow).growX().minWidth(0f).top().left().padLeft(dp(14f)).padBottom(dp(10f))
            body.add(row).growX().row()
        }
        s.sheets.open(SheetPage(ChatView.summary(items), body, height = 560f))
    }

    private fun stepDetail(s: FusedShell, it: CodexItem) {
        // 原型：说明 / 命令（带复制）/ 输出 三块；padding 0 6，块间距 10，标题 14/600 灰；内容深底圆角 10，内边距 14 16
        val body = Table().top().left()
        body.margin(0f, dp(6f), 0f, dp(6f))
        var first = true
        fun block(title: String, content: Element, copyText: String? = null, bare: Boolean = false) {
            val head = Table()
            head.add(K.text(title, 14f, K.muted, 600)).left().growX()
            if (copyText != null) head.add(iconButton(PI.copy, 19f, 40f, K.muted, stroke = 1.6f) { s.host.copy(copyText); s.notify("已复制") }.apply { name = "复制命令" })
            body.add(head).growX().padTop(if (first) 0f else dp(22f)).minHeight(if (copyText != null) dp(40f) else 0f).row()
            first = false
            if (bare) { body.add(content).growX().minWidth(0f).padTop(dp(10f)).row(); return }
            val box = Table(K.fill(K.bubble, 10f))
            box.margin(dp(14f), dp(16f), dp(14f), dp(16f))
            box.add(content).growX().minWidth(0f).left()
            body.add(box).growX().minWidth(0f).padTop(dp(10f)).row()
        }
        fun code(text: String, highlight: Boolean) = arc.scene.ui.Label(
            if (highlight) io.github.zenthxsin.rustwright.ui.kit.Highlight.shell(text) else text.replace("[", "[["),
            arc.scene.ui.Label.LabelStyle(io.github.zenthxsin.rustwright.ui.designsystem.RwTheme.richMono(13f, 20f), if (highlight) K.dl("D6D4CC", "2B2B28") else K.muted),
        ).apply { setWrap(true) }

        val (desc, cmd, out) = stepParts(it)
        block("说明", K.para(desc, 15f, K.text, lh = 22f))
        if (cmd != null) block("命令", code(cmd, it.type == "commandExecution" || it.type == "fileChange"), copyText = cmd)
        if (out != null) block("输出", code(out, false))
        StepPreview.files(s, it).takeIf { f -> f.isNotEmpty() }?.let { f -> block("预览", StepPreview.block(s, f), bare = true) }
        val status = when {
            it.running -> "进行中"
            it.failed -> "失败" + (it.exitCode?.let { c -> "（退出码 $c）" } ?: "")
            else -> "已完成"
        }
        s.sheets.push(SheetPage(stepKind(it), body, sub = status, height = 560f))
    }

    /** 说明、命令、输出（原型 stepDetail 的 desc / cmd / out）。 */
    private fun stepParts(i: CodexItem): Triple<String, String?, String?> = when (i.type) {
        "commandExecution" -> Triple(
            if (isRead(i)) "查看文件内容" else "在虚拟环境里运行命令",
            bareCommand(i.command.orEmpty()),
            i.output.takeLast(4000).trimEnd().ifEmpty { if (i.running) "…" else "（没有输出）" } + (i.exitCode?.takeIf { c -> c != 0 }?.let { c -> "\n退出码 $c" } ?: ""),
        )
        "fileChange" -> Triple(
            if (i.changes.size == 1) "修改 " + i.changes[0].path.substringAfterLast('/') else "修改 ${i.changes.size} 个文件",
            i.changes.joinToString("\n") { ch -> "apply_patch <<EOF\n*** ${if (ch.kind == "add") "Add" else "Update"} File: ${ch.path}\n" + ch.diff.trimEnd() + "\nEOF" }.take(6000),
            i.changes.joinToString("\n") { ch -> "已写入 ${ch.path}（+${ch.added} -${ch.removed} 行）" },
        )
        "dynamicToolCall", "mcpToolCall" -> Triple(
            stepVerb(i).removePrefix("已").removePrefix("正在") + (i.tool?.let { t -> "（$t）" } ?: ""),
            (i.tool ?: "tool") + " " + i.arguments.orEmpty(),
            (i.result ?: i.error)?.takeLast(4000) ?: if (i.running) "…" else null,
        )
        "reasoning" -> Triple("${AppBrand.identity.assistant}的思考过程", null, i.text.ifBlank { null })
        "agentMessage" -> Triple("${AppBrand.identity.assistant}中途的回复", null, stripCitations(i.text).ifBlank { null })
        "imageView" -> Triple("${AppBrand.identity.assistant}查看了一张图片", imagePath(i), null)
        else -> Triple(i.type, null, i.text.ifBlank { null })
    }

    /** imageView 的图片路径（新记录 text 就是路径；旧记录是整段 JSON，从里面取）。 */
    private fun imagePath(i: CodexItem): String? =
        i.text.takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
            ?: Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(i.text)?.groupValues?.get(1)

    /** 去掉 Codex 包的 `/bin/bash -lc "…"` 外壳，显示真正的命令。 */
    fun bareCommand(cmd: String): String {
        val m = Regex("""^\s*(?:/usr)?(?:/bin/)?(?:ba|z)?sh\s+-l?c\s+(.*)$""", RegexOption.DOT_MATCHES_ALL).find(cmd) ?: return cmd
        var inner = m.groupValues[1].trim()
        if (inner.length >= 2 && (inner.first() == '"' && inner.last() == '"' || inner.first() == '\'' && inner.last() == '\'')) {
            inner = inner.substring(1, inner.length - 1)
            if (m.groupValues[1].trim().first() == '"') inner = inner.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return inner
    }

    /** 只读命令（每一段都是 cat / ls / head / sed -n / rg …）算「读取」。 */
    private fun isRead(i: CodexItem): Boolean {
        val c = bareCommand(i.command.orEmpty())
        val segs = c.split(Regex("&&|\\|\\||;|\\|")).map { it.trim() }.filter { it.isNotEmpty() }
        val readers = listOf("cat", "ls", "head", "tail", "wc", "rg", "grep", "find", "tree", "stat", "file", "pwd")
        return segs.isNotEmpty() && segs.all { seg -> val w = seg.split(Regex("\\s+")).first(); w in readers || (w == "sed" && seg.contains(" -n")) }
    }

    /** 原型 stepDetail.kind：读取文件 / 写入文件 / 生成贴图 / 生成地图 / Bash。 */
    private fun stepKind(i: CodexItem) = when (i.type) {
        "commandExecution" -> if (isRead(i)) "读取文件" else "Bash"
        "fileChange" -> "写入文件"
        "reasoning" -> "思考"
        "agentMessage" -> "回复"
        "imageView" -> "查看图片"
        "dynamicToolCall", "mcpToolCall" -> i.tool ?: "工具"
        else -> "步骤"
    }

    /** 原型步骤图标：读 eye、写 pen、贴图 img、地图 map、命令 term。 */
    private fun stepIcon(i: CodexItem) = when (i.type) {
        "commandExecution" -> if (isRead(i)) PI.eye else PI.term
        "fileChange" -> PI.pen
        "reasoning" -> PI.eye
        "agentMessage" -> PI.chat
        "imageView" -> PI.img
        "dynamicToolCall", "mcpToolCall" -> PI.pen
        else -> PI.info
    }

    private fun stepVerb(i: CodexItem) = when (i.type) {
        "commandExecution" -> if (isRead(i)) (if (i.running) "正在读取" else "已读取") else if (i.running) "正在运行" else "已运行"
        "fileChange" -> if (i.running) "正在写入" else "已写入"
        "reasoning" -> "已思考"
        "agentMessage" -> "回复"
        "imageView" -> "查看图片"
        "dynamicToolCall", "mcpToolCall" -> if (i.running) "正在调用" else "已调用"
        else -> i.type
    }

    private fun stepTarget(i: CodexItem): String? = when (i.type) {
        "commandExecution" -> i.command?.let { bareCommand(it) }?.lineSequence()?.firstOrNull()
        "fileChange" -> i.changes.joinToString("、") { it.path.substringAfterLast("/workspace/") }.ifEmpty { null }
        "dynamicToolCall", "mcpToolCall" -> i.tool
        "agentMessage" -> stripCitations(i.text).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        "imageView" -> imagePath(i)?.substringAfterLast('/')
        else -> null
    }

    // ---- 自动检查结果 ----

    /** 每个文件一组：文件名 + 「第 N 行 · 问题」；底部「交给助手修」在当前对话里直接发出修复请求。 */
    fun check(s: FusedShell, r: TurnCheck) {
        val body = Table().top()
        body.margin(0f, dp(10f), dp(20f), dp(10f))
        for ((file, problems) in r.files.filter { it.second.isNotEmpty() }) {
            body.add(K.text(file, 15f, K.muted, 500)).left().pad(dp(6f), dp(6f), dp(8f), dp(6f)).row()
            val g = ListGroup()
            for (p in problems.take(30)) {
                val row = Table(K.fill(K.surface, 0f))
                row.margin(dp(12f), dp(16f), dp(12f), dp(16f))
                row.add(Dot(if (p.severity == "error") K.danger else K.c("E9A23B"))).size(dp(8f)).top().padTop(dp(7f)).padRight(dp(12f))
                val col = Table().left()
                val where = listOfNotNull(p.line.takeIf { it > 0 }?.let { "第 $it 行" } ?: "整个文件", p.context).joinToString(" · ")
                col.add(K.text(where, 13f, K.subtle, lh = 13f)).left().row()
                col.add(K.para(p.message, 15f, K.text, lh = 22f)).growX().left().padTop(dp(4f))
                row.add(col).growX().minWidth(0f)
                g.row(row)
            }
            body.add(g).growX().padBottom(dp(14f)).row()
        }
        val fix = Tap(K.light, 12f) {
            s.sheets.close()
            s.send(r.fixRequest(errorsOnly = false))
        }
        fix.name = "交给${AppBrand.identity.assistant}修"
        fix.add(K.text("交给${AppBrand.identity.assistant}修", 16f, K.onLight, 600))
        body.add(fix).growX().height(dp(48f)).pad(dp(4f), dp(6f), 0f, dp(6f))
        s.sheets.open(SheetPage("检查结果", arc.scene.ui.ScrollPane(body).apply { setScrollingDisabledX(true) }, height = 560f))
    }

    // ---- 用量 ----

    /**
     * 用量弹层。[context] 为 true 时（对话里点右上角圆圈）先显示当前对话的上下文窗口；
     * 从设置进来看的是账号用量：累计 token、订阅额度、余额，不显示上下文。
     */
    fun usage(s: FusedShell, context: Boolean = false) {
        val body = Table().top().apply { margin(0f, dp(10f), 0f, dp(10f)) }
        fun h3(text: String, top: Float) = body.add(K.text(text, 15f, K.muted, 500)).left().pad(dp(top), dp(6f), dp(10f), dp(6f)).row()

        val c = s.conversation()
        if (context && c != null) {
            val total = (c.contextWindow.takeIf { it > 0 } ?: 400_000L).toFloat()
            val used = c.contextTokens.toFloat().coerceAtMost(total)
            val pct = (used / total * 100f).toInt()
            h3("上下文", 4f)
            // 只有一条：已使用多少（不展开明细）
            val card = Table(K.fill(K.surface, 14f))
            card.margin(dp(16f), dp(16f), dp(14f), dp(16f))
            val top = Table()
            top.add(K.text("上下文窗口", 17f, K.text, 500, 17f)).growX().left()
            top.add(K.text("已使用 $pct%", 15f, K.muted, lh = 15f)).padLeft(dp(8f))
            card.add(top).growX().row()
            card.add(Bar(used / total, K.blue, K.dl("1B2A44", "DCE4F2"))).growX().height(dp(6f)).padTop(dp(12f)).row()
            // 本对话累计：输入 · 输出 · 缓存命中率（缓存的输入占全部输入的比例）
            val u = c.totalUsage
            // 输入要算上缓存命中的部分。OpenAI 的 inputTokens 本来就含缓存；有的中转站报的是不含缓存的，
            // 这时 total ≈ 输入 + 缓存 + 输出（或缓存比输入还多），就把缓存加回去
            val excludesCache = u.cached > u.input || (u.total > 0 && u.total >= u.input + u.cached + u.output)
            val input = if (excludesCache) u.input + u.cached else u.input
            val cacheRate = if (input > 0) (u.cached * 100 / input).toInt() else 0
            card.add(K.text("输入 ${fmtUnit(input)}   输出 ${fmtUnit(u.output)}   缓存率 $cacheRate%", 14f, K.subtle, lh = 14f)).left().padTop(dp(12f))
            body.add(card).growX().row()
        } else {
            // 账号用量：所有对话累计用掉的 token
            val convs = s.host.wb.conversations.filter { it.totalTokens > 0 }
            val tokens = convs.sumOf { it.totalTokens }
            h3("累计", 4f)
            val card = Table(K.fill(K.surface, 14f))
            card.margin(dp(16f))
            val top = Table()
            top.add(K.text("已用 token", 17f, K.text, 500, 17f)).growX().left()
            // 和设置页那一行同一种写法：「584.8 万」
            top.add(K.text(if (tokens >= 10_000) String.format("%.1f 万", tokens / 10_000.0) else "$tokens", 17f, K.text, 600, 17f))
            card.add(top).growX().row()
            card.add(K.text("${convs.size} 个对话", 14f, K.subtle, lh = 14f)).left().padTop(dp(10f))
            body.add(card).growX().row()
        }

        // 免费福利（NewAPI 登录福利）：按「已使用 % + 进度条 + 何时重置」的样子直接列在用量里
        val benefits = Table()
        var shownBenefits: BenefitsState? = null
        benefits.update {
            val st = s.host.benefitsState()
            if (st != shownBenefits) { shownBenefits = st; buildBenefits(s, benefits, st) }
        }
        body.add(benefits).growX().row()
        s.host.refreshBenefits()

        h3("余额", 22f)
        val bal = Table(K.fill(K.surface, 14f))
        bal.margin(dp(16f))
        bal.add(K.text("账户余额", 17f, K.text, 500, 17f)).growX().left()
        bal.add(K.text(s.host.balance()?.let { if (it.startsWith("¥") || it.startsWith("$")) it else "¥$it" } ?: "—", 17f, K.text, 600, 17f))
        body.add(bal).growX().row()
        body.add().height(dp(20f)).row()

        // 原型：刷新图标每点一次多转 360°，0.6s cubic-bezier(.2,.8,.2,1)
        val refresh = SvgIcon(PI.refresh, 22f, K.text, 1.7f)
        val turn = Smooth(0f, 0.6f, Interp.standard)
        refresh.update { refresh.rotation = -turn.update(arc.Core.graphics.deltaTime) }
        val refreshBtn = iconButton(refresh, 48f) { turn.to(turn.target() + 360f); if (!s.host.benefitsState().loading) s.host.refreshBenefits() }
        s.sheets.open(SheetPage("用量", body, right = refreshBtn))
    }

    private fun buildBenefits(s: FusedShell, into: Table, state: BenefitsState) {
        into.clearChildren()
        fun h3(text: String) = into.add(K.text(text, 15f, K.muted, 500)).left().pad(dp(22f), dp(6f), dp(10f), dp(6f)).row()
        val data = state.data
        val balance = data?.balance?.takeIf { state.loggedIn && !state.failed }
        if (balance == null) {
            h3("免费福利")
            into.add(valueRow(state.summary(), if (state.loading) "读取中…" else if (state.loggedIn) "查看" else "去登录") {
                s.sheets.close()
                s.openTool("订阅计划", BenefitsPage(s.host), s.page)
            }).growX().row()
            return
        }
        // 参考 Claude 用量页：每块一张卡，只留「已使用 %」、进度条和一行重置时间；明细在「订阅计划」里看
        // 三张额度卡同属「套餐用量」一组
        h3("套餐用量")
        into.add(quotaCard("5 小时", balance.fiveHours, state, null, compact = true)).growX().row()
        into.add(quotaCard("7 天", balance.sevenDays, state, null, compact = true)).growX().padTop(dp(10f)).row()
        into.add(quotaCard("生成", balance.generation, state, balance.dayTimezone, compact = true)).growX().padTop(dp(10f)).row()
    }

    /** 一张额度卡：标题 · 已使用 %，进度条，下面是剩余和重置时间（每秒刷新）。 */
    internal fun quotaCard(title: String, w: io.github.zenthxsin.rustwright.core.account.BenefitWindow, state: BenefitsState, timezone: String?, extra: String? = null, compact: Boolean = false): Table {
        val card = Table(K.fill(K.surface, 14f))
        card.margin(dp(16f))
        val used = if (w.limit > 0) ((w.used + w.reserved).toFloat() / w.limit).coerceIn(0f, 1f) else 0f
        val top = Table()
        top.add(K.text(title, 17f, K.text, 500, 17f)).growX().left()
        top.add(K.text("已使用 ${Math.round(used * 100)}%", 15f, K.muted, lh = 15f))
        card.add(top).growX().row()
        card.add(Bar(used, K.blue, K.dl("1B2A44", "DCE4F2"))).growX().height(dp(6f)).padTop(dp(12f)).row()
        val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
        if (!compact) card.add(K.text("剩余 ${nf.format(w.remaining)} / ${nf.format(w.limit)}" + (extra?.let { " · $it" } ?: ""), 13f, K.subtle, lh = 15f).apply { setEllipsis(true) })
            .growX().minWidth(0f).left().padTop(dp(12f)).row()
        fun text() = if (compact) BenefitsPage.resetShort(w, state, timezone) else BenefitsPage.resetText(w, state, timezone)
        val reset = K.text(text(), if (compact) 14f else 13f, K.subtle, lh = 15f).apply { setEllipsis(true) }
        var lastSecond = Long.MIN_VALUE
        reset.update {
            val second = System.nanoTime() / 1_000_000_000L
            if (second != lastSecond) { lastSecond = second; reset.setText(text()) }
        }
        card.add(reset).growX().minWidth(0f).left().padTop(dp(if (compact) 12f else 6f))
        return card
    }

    private fun clipSpend(g: ListGroup) {
        g.clipRadius = dp(14f)
    }

    private fun spendRow(label: String, value: String, sub: String) = Table(K.fill(K.surface, 0f)).apply {
        margin(dp(14f), dp(16f), dp(14f), dp(16f))
        val top = Table()
        top.add(K.text(label, 16f, K.text, lh = 16f)).growX().left()
        top.add(K.text(value, 16f, K.text, lh = 16f))
        add(top).growX().row()
        add(K.text(sub, 13f, K.subtle, lh = 15.6f)).left().padTop(dp(8f))
    }

    /** token 数自动升单位：999、1.2k、3.4m、1.1b。 */
    private fun fmtUnit(v: Long): String {
        fun f(x: Double, u: String) = String.format("%.1f", x).removeSuffix(".0") + u
        return when {
            v >= 1_000_000_000L -> f(v / 1e9, "b")
            v >= 1_000_000L -> f(v / 1e6, "m")
            v >= 1_000L -> f(v / 1e3, "k")
            else -> v.toString()
        }
    }

    private fun fmtK(v: Float): String = when {
        v >= 1_000_000f -> String.format("%.1fM", v / 1_000_000f).replace(".0M", "M")
        v >= 1000f -> String.format("%.0fk", v / 1000f)
        else -> v.toInt().toString()
    }

    private fun fmtWan(v: Long): String = if (v >= 10_000) String.format("%.1f 万", v / 10_000.0) else v.toString()

    // ---- 对话菜单（右上角下拉） ----

    fun chatMenu(s: FusedShell) {
        val c = s.conversation() ?: return
        s.menu.show(listOf(
            MenuHost.Item("查看产物", PI.layers) { s.show(FusedShell.Page.LIBRARY) },
            MenuHost.Item("打开终端", PI.term) {
                s.openToolId("terminal", FusedShell.Page.CHAT)
            },
            MenuHost.Item("重命名", PI.edit) {
                s.launch {
                    io.github.zenthxsin.rustwright.ui.widgets.Dialogs.input("重命名对话", initial = c.title)?.takeIf { it.isNotBlank() }?.let { s.host.wb.renameConversation(c.id, it.trim()) }
                }
            },
            MenuHost.Item("导出项目", PI.up) { s.currentProject()?.let { p -> s.launch { s.host.exportProjectSource(p) } } },
            MenuHost.Item("归档", PI.archive, K.danger) {
                s.host.wb.setArchived(c.id, true)
                s.newChat()
                s.notify("已归档")
            },
        ))
    }
}

/**
 * 右上角下拉菜单（原型「对话菜单」）：宽 210，深底圆角 14 带描边和阴影，每项 50 高；
 * 点外面任意处关闭，出现时轻微下滑淡入。
 */
class MenuHost : arc.scene.ui.layout.WidgetGroup() {
    /** [checked] 时文字和图标变蓝、右边打勾（筛选菜单）；[divider] 时上面加一条分隔线（分组）。 */
    class Item(val label: String, val icon: String, val color: Color = K.text, val checked: Boolean = false, val divider: Boolean = false, val tap: () -> Unit)

    // 原型 rwin .15s ease：从下方 6px 处淡入上移（只有出现时有动画，关闭直接消失）
    private val shown = Smooth(0f, 0.15f, Interp.ease)
    /** 贴着这个控件弹出（长按消息）；null 时在右上角（对话菜单）。 */
    private var anchor: arc.scene.Element? = null
    private val panel = object : Table() {
        override fun drawBackground(x: Float, y: Float) {
            val a = color.a * parentAlpha
            arc.graphics.g2d.Shapes.shadow(x, y, width, height, dp(14f), 0f, -dp(12f), dp(32f), 0f, Color(0f, 0f, 0f, 0.45f * a))
            arc.graphics.g2d.Shapes.rect(x, y, width, height, dp(14f), Color(K.dl("262624", "F3F2EE")).a(a), dp(1f), Color(K.line).a(a))
        }
    }

    init {
        setFillParent(true)
        visible = false
        val catcher = Element()
        catcher.setFillParent(true)
        catcher.clicked { hide() }
        addChild(catcher)
        addChild(panel)
        panel.touchable = Touchable.enabled
    }

    private var wide = false
    private val rows = Table().top()
    private val rowsPane = arc.scene.ui.ScrollPane(rows).apply { setScrollingDisabledX(true); setOverscroll(false, false) }

    fun show(items: List<Item>, anchor: arc.scene.Element? = null) {
        this.anchor = anchor
        wide = items.any { it.checked }
        panel.clearChildren()
        rows.clearChildren()
        panel.margin(dp(6f))
        for (it in items) {
            if (it.divider) rows.add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).pad(dp(4f), dp(-6f), dp(4f), dp(-6f)).row()
            val c = if (it.checked) K.blue else it.color
            val t = Tap(null, 10f) { hide(); it.tap() }
            t.name = it.label
            t.margin(0f, dp(14f), 0f, dp(14f))
            t.add(K.svg(it.icon, 20f, Color(c).a(if (it.checked) 1f else 0.8f), 1.6f)).padRight(dp(16f))
            t.add(K.text(it.label, 16f, c, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padBottom(dp(2f))
            if (it.checked) t.add(K.svg(PI.check, 18f, K.blue, 2f)).padLeft(dp(12f))
            rows.add(t).growX().height(dp(50f)).row()
        }
        panel.add(rowsPane).grow()
        visible = true
        shown.snap(0f)
        shown.to(1f)
        invalidate()
    }

    fun hide() {
        shown.snap(0f)
        visible = false
    }

    val isShown get() = visible && shown.target() == 1f

    override fun act(delta: Float) {
        super.act(delta)
        if (!visible) return
        shown.update(delta)
        if (shown.get() <= 0.001f && shown.target() == 0f) visible = false
        invalidate()
    }

    override fun layout() {
        val w = dp(if (wide) 240f else 210f)
        panel.width = w
        rows.validate()
        // 项目多时菜单可滚动，最高到屏幕下沿留一点
        val h = (rows.prefHeight + dp(12f)).coerceAtMost(height - dp(120f))
        val p = shown.get()
        val a = anchor?.takeIf { it.scene != null }
        if (a != null) {
            // 右边和消息对齐，放在消息下方；下面放不下就放上方
            val lo = a.localToAscendantCoordinates(this, arc.math.geom.Vec2(0f, 0f))
            val x = (lo.x + a.width - w).coerceIn(dp(12f), (width - w - dp(12f)).coerceAtLeast(dp(12f)))
            var y = lo.y - dp(8f) - h
            if (y < dp(12f)) y = lo.y + a.height + dp(8f)
            y = y.coerceIn(dp(12f), (height - h - dp(12f)).coerceAtLeast(dp(12f)))
            panel.setBounds(x, y - (1f - p) * dp(6f), w, h)
        } else panel.setBounds(width - dp(12f) - w, height - dp(60f) - h - (1f - p) * dp(6f), w, h)
        panel.color.a = p
    }
}

/** 竖线（时间线）。 */
private class VLine : Element() {
    init {
        touchable = Touchable.disabled
    }

    override fun draw() {
        arc.graphics.g2d.Draw.color(K.line.r, K.line.g, K.line.b, parentAlpha)
        arc.graphics.g2d.Fill.crect(x, y, width, height)
        arc.graphics.g2d.Draw.reset()
    }
}

/** 卡片内的 2px 分隔（页面底色）。 */
private class Sep : Element() {
    override fun draw() {
        arc.graphics.g2d.Draw.color(K.bg.r, K.bg.g, K.bg.b, parentAlpha)
        arc.graphics.g2d.Fill.crect(x, y, width, height)
        arc.graphics.g2d.Draw.reset()
    }
}

/** 小圆点。 */
private class Dot(private val c: Color) : Element() {
    override fun draw() = arc.graphics.g2d.Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(c).a(parentAlpha))
}

/** 进度条（圆角两端）。 */
class Bar(var value: Float, private val fill: Color, private val track: Color) : Element() {
    override fun draw() {
        val a = parentAlpha * color.a
        arc.graphics.g2d.Shapes.rect(x, y, width, height, height / 2f, Color(track).a(a))
        val w = (width * value.coerceIn(0f, 1f)).coerceAtLeast(if (value > 0f) height else 0f)
        if (w > 0f) arc.graphics.g2d.Shapes.rect(x, y, w, height, height / 2f, Color(fill).a(a))
    }
}
