package arc.scene.ui.layout;

import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;

/**
 * Rustwright 二开：带动画的显隐容器，等价于网页里 {@code max-width / max-height + opacity + transform}
 * 一起过渡的写法。收起时占位尺寸平滑缩到 0，周围布局跟着平滑移动；内容同时淡出、略微缩小。
 * <p>
 * 用法：{@code Reveal r = new Reveal(button).axis(Reveal.HORIZONTAL); r.setShown(false);}
 */
public class Reveal extends WidgetGroup{
    public static final int VERTICAL = 1, HORIZONTAL = 2, BOTH = 3;

    private final Element content;
    private int axis = VERTICAL;
    private boolean shown = true;
    /** 0 = 完全收起，1 = 完全展开。 */
    private float progress = 1f;
    private float from = 1f, time, duration = 0.4f;
    private boolean animating;
    private Interp interp = Interp.standard;
    /** 收起时内容缩到的比例（1 表示不缩放）。 */
    public float collapsedScale = 0.85f;
    /** 内容透明度跟随进度的快慢：大于 1 时淡出比收起更快。 */
    public float fadeSpeed = 1.6f;

    public Reveal(Element content){
        this.content = content;
        addChild(content);
        setTransform(false);
    }

    public Reveal axis(int axis){
        this.axis = axis;
        invalidateHierarchy();
        return this;
    }

    public Reveal duration(float seconds){
        this.duration = seconds;
        return this;
    }

    public Reveal interp(Interp interp){
        this.interp = interp;
        return this;
    }

    public Element content(){
        return content;
    }

    public boolean isShown(){
        return shown;
    }

    /** 带动画地显示或收起。 */
    public void setShown(boolean shown){
        setShown(shown, true);
    }

    public void setShown(boolean shown, boolean animate){
        if(this.shown == shown && (animating || progress == (shown ? 1f : 0f))) return;
        this.shown = shown;
        touchable = shown ? Touchable.childrenOnly : Touchable.disabled;
        if(animate && duration > 0f){
            from = progress;
            time = 0f;
            animating = true;
        }else{
            progress = shown ? 1f : 0f;
            animating = false;
        }
        invalidateHierarchy();
    }

    /** 当前展开进度（0..1，已经过缓动）。 */
    public float progress(){
        return progress;
    }

    @Override
    public void act(float delta){
        super.act(delta);
        if(animating){
            time += delta;
            float t = Math.min(1f, time / duration);
            float target = shown ? 1f : 0f;
            progress = from + (target - from) * interp.apply(t);
            if(t >= 1f){
                progress = target;
                animating = false;
            }
            invalidateHierarchy();
        }
    }

    private float contentW(){
        return content.getPrefWidth();
    }

    private float contentH(){
        return content.getPrefHeight();
    }

    @Override
    public float getPrefWidth(){
        float w = contentW();
        return (axis & HORIZONTAL) != 0 ? w * progress : w;
    }

    @Override
    public float getPrefHeight(){
        float h = contentH();
        return (axis & VERTICAL) != 0 ? h * progress : h;
    }

    @Override
    public float getMinWidth(){
        return (axis & HORIZONTAL) != 0 ? getPrefWidth() : super.getMinWidth();
    }

    @Override
    public float getMinHeight(){
        return (axis & VERTICAL) != 0 ? getPrefHeight() : super.getMinHeight();
    }

    @Override
    public void layout(){
        // 内容始终按完整尺寸排版：横向贴左、纵向贴上，容器只露出一部分
        // 容器被撑得比内容大时（growX / growY）内容跟着填满
        float w = (axis & HORIZONTAL) != 0 ? Math.max(contentW(), getWidth()) : getWidth();
        float h = (axis & VERTICAL) != 0 ? Math.max(contentH(), getHeight()) : getHeight();
        content.setBounds(0f, getHeight() - h, w, h);
        content.validate();
    }

    @Override
    public void draw(){
        if(progress <= 0.001f) return;
        validate();
        float alpha = Mathf.clamp(1f - (1f - progress) * fadeSpeed);
        float scale = collapsedScale + (1f - collapsedScale) * progress;
        float oldA = color.a;
        color.a = oldA * alpha;
        boolean clip = progress < 0.999f;
        if(clip){
            Draw.flush();
            if(!clipBegin(x, y, getWidth(), getHeight())){
                color.a = oldA;
                return;
            }
        }
        if(content instanceof Group){
            Group g = (Group)content;
            if(scale != 1f){
                g.setScale(scale);
                g.setOrigin(g.getWidth() / 2f, g.getHeight() / 2f);
                g.setTransform(true);
            }else if(g.scaleX != 1f || g.scaleY != 1f){
                g.setScale(1f);
                g.setTransform(false);
            }
        }
        super.draw();
        if(clip){
            Draw.flush();
            clipEnd();
        }
        color.a = oldA;
    }
}
