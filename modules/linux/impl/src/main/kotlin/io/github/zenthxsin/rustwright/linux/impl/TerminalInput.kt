package io.github.zenthxsin.rustwright.linux.impl

import arc.scene.ui.TextField

/**
 * 终端的输入框：把输入法「已提交」的文字增量发给终端（组合中的拼音不发），删除发 DEL；回车发 CR 并清空。
 * 只在回车时清空输入框——每次提交都清空的话，快速输入时与输入法的异步回写会产生重复字符。
 */
class TerminalInput(private val send: (String) -> Unit) : TextField() {

    /** 已经发给终端的内容（对应输入框文本的前缀）。 */
    private var sent = ""

    init {
        setMessageText("点这里输入；回车发送")
        setTextFieldListener { _, c -> if (c == '\n' || c == '\r') enter() }
    }

    override fun onImeUpdate() {
        super.onImeUpdate()
        val committed = if (composeStart >= 0) text.substring(0, composeStart.coerceAtMost(text.length)) else text
        if (committed.contains('\n') || committed.contains('\r')) {
            val before = committed.substringBefore('\n').substringBefore('\r')
            diff(before)
            enter()
            return
        }
        diff(committed)
    }

    private fun diff(committed: String) {
        if (committed == sent) return
        var p = 0
        while (p < committed.length && p < sent.length && committed[p] == sent[p]) p++
        val removed = sent.substring(p)
        val dels = removed.codePointCount(0, removed.length)
        if (dels > 0) send("\u007f".repeat(dels))
        if (p < committed.length) send(committed.substring(p))
        sent = committed
    }

    private fun enter() {
        send("\r")
        sent = ""
        setText("") // setText 会同步给输入法代理
    }
}
