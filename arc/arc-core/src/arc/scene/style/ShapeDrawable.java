package arc.scene.style;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.struct.*;

/**
 * Rustwright 二开：用 {@link Shapes} 画的背景——任意圆角、描边、多层柔和阴影，等价于 CSS 的
 * {@code background} + {@code border} + {@code border-radius} + {@code box-shadow}。
 * <p>
 * 链式构造：{@code new ShapeDrawable().fill(c).radius(12).border(1, line).shadow(0, 8, 24, 0, shadowColor).pad(16)}
 */
public class ShapeDrawable extends BaseDrawable{
    public final Color fill = new Color(0, 0, 0, 0);
    public final Color borderColor = new Color(0, 0, 0, 0);
    public float borderWidth;
    public float radiusTL, radiusTR, radiusBR, radiusBL;
    public final Seq<Shadow> shadows = new Seq<>();

    public ShapeDrawable(){
    }

    public ShapeDrawable(Color fill, float radius){
        fill(fill).radius(radius);
    }

    public ShapeDrawable fill(Color color){
        fill.set(color);
        return this;
    }

    public ShapeDrawable radius(float r){
        radiusTL = radiusTR = radiusBR = radiusBL = r;
        return this;
    }

    /** 四角分别指定：左上、右上、右下、左下。 */
    public ShapeDrawable radius(float tl, float tr, float br, float bl){
        radiusTL = tl;
        radiusTR = tr;
        radiusBR = br;
        radiusBL = bl;
        return this;
    }

    public ShapeDrawable border(float width, Color color){
        borderWidth = width;
        borderColor.set(color);
        return this;
    }

    /** 追加一层阴影（与 CSS box-shadow 参数同序：x、y、模糊、扩展、颜色；y 向下为正）。 */
    public ShapeDrawable shadow(float offsetX, float offsetY, float blur, float spread, Color color){
        shadows.add(new Shadow(offsetX, offsetY, blur, spread, color));
        return this;
    }

    /** 内边距（Table 背景会据此留出内容区）。 */
    public ShapeDrawable pad(float all){
        return pad(all, all, all, all);
    }

    public ShapeDrawable pad(float top, float left, float bottom, float right){
        setTopHeight(top);
        setLeftWidth(left);
        setBottomHeight(bottom);
        setRightWidth(right);
        return this;
    }

    public ShapeDrawable copy(){
        ShapeDrawable d = new ShapeDrawable();
        d.fill.set(fill);
        d.borderColor.set(borderColor);
        d.borderWidth = borderWidth;
        d.radius(radiusTL, radiusTR, radiusBR, radiusBL);
        for(Shadow s : shadows) d.shadows.add(new Shadow(s.offsetX, s.offsetY, s.blur, s.spread, s.color));
        d.setTopHeight(getTopHeight());
        d.setLeftWidth(getLeftWidth());
        d.setBottomHeight(getBottomHeight());
        d.setRightWidth(getRightWidth());
        d.setMinWidth(getMinWidth());
        d.setMinHeight(getMinHeight());
        return d;
    }

    @Override
    public void draw(float x, float y, float width, float height){
        float r = Math.max(Math.max(radiusTL, radiusTR), Math.max(radiusBR, radiusBL));
        for(Shadow s : shadows){
            Shapes.shadow(x, y, width, height, r, s.offsetX, s.offsetY, s.blur, s.spread, s.color);
        }
        boolean hasFill = fill.a > 0f, hasBorder = borderWidth > 0f && borderColor.a > 0f;
        if(!hasFill && !hasBorder) return;
        Shapes.rect(x, y, width, height, radiusTL, radiusTR, radiusBR, radiusBL,
            hasFill ? fill : null, hasBorder ? borderWidth : 0f, hasBorder ? borderColor : null);
    }

    public static class Shadow{
        public float offsetX, offsetY, blur, spread;
        public final Color color = new Color();

        public Shadow(float offsetX, float offsetY, float blur, float spread, Color color){
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.blur = blur;
            this.spread = spread;
            this.color.set(color);
        }
    }
}
