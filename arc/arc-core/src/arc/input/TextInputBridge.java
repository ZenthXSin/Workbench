package arc.input;

/**
 * [Rustwright] 平台内联输入法桥。
 * <p>
 * 设置到 {@link arc.scene.ui.TextField#inputBridge} 后，移动端的 TextField 不再弹出原生对话框，
 * 而是把输入委托给平台控件（Android 上为一个不可见的 EditText），
 * 平台再通过 {@link ImeClient#imeUpdate} 回写文本、选区与组合区，从而支持中文拼音组合、候选词、剪贴板等系统能力。
 */
public interface TextInputBridge{
    /** 字段获得键盘焦点，开始接收输入并弹出软键盘。 */
    void begin(ImeClient field);

    /** Arc 侧改变了文本或光标（如触摸移动光标、程序修改文本），同步给平台控件。 */
    void sync(ImeClient field);

    /** 字段失去键盘焦点。 */
    void end(ImeClient field);
}
