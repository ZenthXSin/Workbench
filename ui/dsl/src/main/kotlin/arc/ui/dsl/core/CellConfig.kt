package arc.ui.dsl

import arc.scene.ui.layout.Cell

/**
 * Cell 布局配置 DSL —— 统一的 cell 配置方法集合。
 *
 * [TableScope]、[ElementCellScope]、[PaneScope]、[DialogScope]、[TreeScope] 等作用域都实现本接口，使
 * `pad/fillX/grow/size/...` 等 cell 方法在这些作用域内写法完全一致。
 *
 * 语义与底层 [Cell] 完全对应，见各自实现。所有方法作用于「本 scope 元素在父容器中的 cell」；
 * 顶层 root / 无父容器时 cell 为 null，调用这些方法空操作（不崩溃，也不生效）。
 *
 * @author Eve
 */
interface CellConfig {

    /** 本 scope 元素在父容器中的 cell；顶层 root / 无父容器时为 null。 */
    val cell: Cell<*>?

    // ---- 内边距 ----

    /** 设置 cell 各方向内边距均为 [v] 像素。 */
    fun pad(v: Float) { cell?.pad(v) }
    /** 设置 cell 四个方向内边距（上、左、下、右，像素）。 */
    fun pad(top: Float, left: Float, bottom: Float, right: Float) { cell?.pad(top, left, bottom, right) }
    /** 设置 cell 左右内边距为 [v] 像素。 */
    fun padX(v: Float) { cell?.padLeft(v); cell?.padRight(v) }
    /** 设置 cell 上下内边距为 [v] 像素。 */
    fun padY(v: Float) { cell?.padTop(v); cell?.padBottom(v) }
    /** 设置 cell 左内边距 [v] 像素。 */
    fun padLeft(v: Float) { cell?.padLeft(v) }
    /** 设置 cell 右内边距 [v] 像素。 */
    fun padRight(v: Float) { cell?.padRight(v) }
    /** 设置 cell 上内边距 [v] 像素。 */
    fun padTop(v: Float) { cell?.padTop(v) }
    /** 设置 cell 下内边距 [v] 像素。 */
    fun padBottom(v: Float) { cell?.padBottom(v) }

    // ---- 填充 / 扩展 ----

    /** 使元素填充 cell 的剩余空间（水平+垂直），即 cell 水平与垂直都填满。 */
    fun fillX() { cell?.fillX() }
    /** 使元素在垂直方向填满 cell 的剩余空间。 */
    fun fillY() { cell?.fillY() }
    /** 使元素在水平与垂直方向都填满 cell 的剩余空间。 */
    fun fill() { cell?.fill() }
    /** 自定义填充比例：水平 [x]、垂直 [y]（0~1，1 表示填满）。 */
    fun fill(x: Float, y: Float) { cell?.fill(x, y) }
    /** 使 cell 在水平与垂直方向占用额外行列并填满该空间（等价 [expand] + [fill]）。 */
    fun grow() { cell?.grow() }
    /** 使 cell 在水平方向占用额外列并填满，即 [expand]X + [fill]X。 */
    fun growX() { cell?.growX() }
    /** 使 cell 在垂直方向占用额外行并填满，即 [expand]Y + [fill]Y。 */
    fun growY() { cell?.growY() }
    /** 使 cell 在水平与垂直方向占用额外的行列（仅分配空间；要填满需再 [fill]，或用 [grow] 一步到位）。 */
    fun expand() { cell?.expand() }
    /** 使 cell 在水平 [x] / 垂直 [y] 方向（为 true 时）占用额外的行列。 */
    fun expand(x: Boolean, y: Boolean) { cell?.expand(x, y) }

    // ---- 尺寸 ----

    /** 设置本元素在 cell 中的期望高度 [v] 像素（覆盖 pref 高度）。 */
    fun height(v: Float) { cell?.height(v) }
    /** 设置本元素在 cell 中的期望宽度 [v] 像素（覆盖 pref 宽度）。 */
    fun width(v: Float) { cell?.width(v) }
    /** 设置本元素期望宽高均为 [s] 像素。 */
    fun size(s: Float) { cell?.size(s) }
    /** 设置本元素期望宽 [w]、高 [h] 像素。 */
    fun size(w: Float, h: Float) { cell?.size(w, h) }
    /** 设置 cell 最小宽度 [v] 像素（用于约束收缩下限）。 */
    fun minWidth(v: Float) { cell?.minWidth(v) }
    /** 设置 cell 最小高度 [v] 像素（用于约束收缩下限）。 */
    fun minHeight(v: Float) { cell?.minHeight(v) }
    /** 设置 cell 最大宽度 [v] 像素（用于约束扩展上限）。 */
    fun maxWidth(v: Float) { cell?.maxWidth(v) }
    /** 设置 cell 最大高度 [v] 像素（用于约束扩展上限）。 */
    fun maxHeight(v: Float) { cell?.maxHeight(v) }

    // ---- 对齐 ----

    /** 本 scope 元素在 cell 中的对齐（[arc.util.Align] 常量，如 [arc.util.Align.topLeft]）。 */
    fun align(align: Int = arc.util.Align.center) { cell?.align(align) }
    /** 本元素贴 cell 顶部（垂直对齐到上沿）。 */
    fun top() { cell?.top() }
    /** 本元素贴 cell 底部。 */
    fun bottom() { cell?.bottom() }
    /** 本元素贴 cell 左端。 */
    fun left() { cell?.left() }
    /** 本元素贴 cell 右端。 */
    fun right() { cell?.right() }
    /** 本元素在 cell 中水平+垂直居中。 */
    fun center() { cell?.center() }

    // ---- 其它 ----

    /** 使该 cell 横跨 [v] 列（跨列合并）。 */
    fun colspan(v: Int) { cell?.colspan(v) }
    /** 使该列与其他列等宽（统一预宽），实现等宽布局。 */
    fun uniform() { cell?.uniform() }
    /** 使该列在水平 [x] / 垂直 [y] 方向（为 true 时）与其他列/行等宽。 */
    fun uniform(x: Boolean, y: Boolean) { cell?.uniform(x, y) }
}
