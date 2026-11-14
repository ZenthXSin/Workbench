package arc.input;

/**
 * [Rustwright] 能接收平台输入法的控件（{@link arc.scene.ui.TextField}、代码编辑器等）。
 * {@link TextInputBridge} 只通过这个接口读写：平台控件里放的是 {@link #imeText()}（可以只是光标附近的一段），
 * 输入后整段新文字连同选区、组合区经 {@link #imeUpdate} 回写。
 */
public interface ImeClient{
    /** 交给平台控件的文字。 */
    String imeText();

    /** 选区起点（imeText 内的下标，起点 ≤ 终点）。 */
    int imeSelectionStart();

    /** 选区终点 / 光标。 */
    int imeSelectionEnd();

    boolean imeMultiline();

    boolean imePassword();

    /** 平台控件的文字、选区或组合区变了；组合区不存在时传 -1。 */
    void imeUpdate(String text, int selStart, int selEnd, int compStart, int compEnd);

    /** 输入法的动作键（完成 / 发送）。 */
    void imeAction();
}
