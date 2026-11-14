package io.github.zenthxsin.rustwright.platform.android

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import arc.Core
import arc.input.ImeClient
import arc.input.TextInputBridge

/**
 * 内联输入法桥：一个不可见的 EditText 承接系统输入法（拼音组合、候选词、联想、剪贴板），
 * 每次变化把 文本 / 选区 / 组合区 回写给获得焦点的 [ImeClient]（Arc 输入框或代码编辑器）。
 *
 * 线程：begin/sync/end 由 Arc GL 线程调用，这里切到 UI 线程；回写再经 [Core.app] post 回 GL 线程。
 */
class AndroidTextInputBridge(
    private val activity: Activity,
    private val container: FrameLayout,
) : TextInputBridge {

    private val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private lateinit var proxy: ProxyEditText

    /** 当前目标字段，仅在 UI 线程读写。 */
    private var target: ImeClient? = null
    /** 程序写入 proxy 时屏蔽回写，避免回环。 */
    private var suppress = false
    private val imeBack = ImeBackState()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * 延迟收键盘：焦点从一个输入框换到另一个（如输入框一行 / 两行切换）时 Arc 先 end 再 begin，
     * 立刻 hide 再 show 两个请求会被系统乱序处理，键盘闪一下就没了。等一会儿还没人要键盘才真的收。
     */
    private val hideLater = Runnable {
        if (target == null) {
            proxy.clearFocus()
            imm.hideSoftInputFromWindow(proxy.windowToken, 0)
        }
    }
    /** Updated by window insets on the UI thread. */
    var keyboardVisible: Boolean
        get() = imeBack.visible
        set(value) = imeBack.visibilityChanged(value)

    fun dismissKeyboardForBack(): Boolean {
        if (!::proxy.isInitialized || !imeBack.consumeBack()) return false
        imm.hideSoftInputFromWindow(proxy.windowToken, 0)
        val field = target
        proxy.clearFocus()
        target = null
        Core.app?.post {
            if (field != null && Core.scene?.keyboardFocus === field) Core.scene.setKeyboardFocus(null)
        }
        return true
    }

    fun install() {
        proxy = ProxyEditText(activity).apply {
            alpha = 0f
            background = null
            isCursorVisible = false
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) = push()
            })
            onSelection = { push() }
            // 输入法的回车 / 完成：TextView 在回车键「按下」和「抬起」时各调一次这个监听，抬起那次 actionId 是
            // IME_NULL(0)，原来的判断（≠ IME_ACTION_NONE）把它也当成一次动作，一次回车换两行。
            // 现在：带按键事件的只认按下那次（抬起也吃掉，免得 EditText 自己再插一个换行）；不带按键事件的才看 actionId。
            setOnEditorActionListener { _, actionId, event ->
                val f = target ?: return@setOnEditorActionListener false
                val d = EnterDecision.of(actionId, event?.keyCode, event?.action, event?.repeatCount ?: 0)
                if (d.fire) Core.app.post { f.imeAction() }
                d.consume
            }
        }
        container.addView(proxy, FrameLayout.LayoutParams(1, 1))
    }

    override fun begin(field: ImeClient) {
        val snapshot = Snapshot.of(field)
        activity.runOnUiThread {
            ui.removeCallbacks(hideLater)
            imeBack.inputRequested()
            target = field
            apply(snapshot)
            proxy.requestFocus()
            imm.restartInput(proxy)
            imm.showSoftInput(proxy, InputMethodManager.SHOW_IMPLICIT)
            if (android.os.Build.VERSION.SDK_INT < 30) keyboardVisible = true
        }
    }

    override fun sync(field: ImeClient) {
        val snapshot = Snapshot.of(field)
        activity.runOnUiThread {
            if (target !== field) return@runOnUiThread
            apply(snapshot)
            imm.restartInput(proxy)
        }
    }

    override fun end(field: ImeClient) {
        activity.runOnUiThread {
            if (target !== field) return@runOnUiThread
            target = null
            imeBack.inputEnded()
            ui.removeCallbacks(hideLater)
            ui.postDelayed(hideLater, 120)
        }
    }

    private fun apply(s: Snapshot) {
        suppress = true
        try {
            proxy.inputType = when {
                s.password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                s.multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            proxy.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                (if (s.multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE)
            proxy.setText(s.text)
            val len = s.text.length
            proxy.setSelection(s.selStart.coerceIn(0, len), s.selEnd.coerceIn(0, len))
        } finally {
            suppress = false
        }
    }

    private fun push() {
        if (suppress) return
        val f = target ?: return
        val e = proxy.text ?: return
        val text = e.toString()
        val selStart = Selection.getSelectionStart(e)
        val selEnd = Selection.getSelectionEnd(e)
        val compStart = BaseInputConnection.getComposingSpanStart(e)
        val compEnd = BaseInputConnection.getComposingSpanEnd(e)
        Core.app.post { f.imeUpdate(text, minOf(selStart, selEnd), maxOf(selStart, selEnd), compStart, compEnd) }
    }

    private data class Snapshot(
        val text: String,
        val selStart: Int,
        val selEnd: Int,
        val multiline: Boolean,
        val password: Boolean,
    ) {
        companion object {
            fun of(f: ImeClient): Snapshot =
                Snapshot(f.imeText(), f.imeSelectionStart(), f.imeSelectionEnd(), f.imeMultiline(), f.imePassword())
        }
    }

    private class ProxyEditText(context: Context) : EditText(context) {
        var onSelection: (() -> Unit)? = null

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onSelection?.invoke()
        }
    }
}

/**
 * 输入法回车怎么处理（纯函数，便于单测）。TextView 在回车键「按下」和「抬起」时各调一次编辑动作监听，
 * 抬起那次 actionId 是 IME_NULL(0)：原来按「≠ IME_ACTION_NONE」判断，把抬起也当成一次动作，一次回车换两行。
 * 带按键事件的只认按下那次（抬起也吃掉，免得 EditText 自己再插换行）；不带按键事件的才看 actionId。
 */
internal data class EnterDecision(val consume: Boolean, val fire: Boolean) {
    companion object {
        const val KEY_ENTER = 66          // KeyEvent.KEYCODE_ENTER
        const val KEY_NUMPAD_ENTER = 160  // KeyEvent.KEYCODE_NUMPAD_ENTER
        const val ACTION_DOWN = 0         // KeyEvent.ACTION_DOWN
        const val IME_NULL = 0            // EditorInfo.IME_NULL
        const val IME_ACTION_NONE = 1     // EditorInfo.IME_ACTION_NONE

        fun of(actionId: Int, keyCode: Int?, keyAction: Int?, repeat: Int): EnterDecision = when {
            keyCode != null && keyCode != KEY_ENTER && keyCode != KEY_NUMPAD_ENTER -> EnterDecision(consume = false, fire = false)
            keyCode != null -> EnterDecision(consume = true, fire = keyAction == ACTION_DOWN && repeat == 0)
            actionId != IME_ACTION_NONE && actionId != IME_NULL -> EnterDecision(consume = true, fire = true)
            else -> EnterDecision(consume = false, fire = false)
        }
    }
}
