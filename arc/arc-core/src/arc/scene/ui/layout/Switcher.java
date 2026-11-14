package arc.scene.ui.layout;

import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.util.*;

/**
 * Rustwright 二开：页面切换容器，与原型（CSS 动画挂在新页面上）一致：旧页面立刻移除，只有新页面做入场动画。
 * <ul>
 * <li>{@link #PUSH}：从右侧 {@link #slide} 处滑入并淡入（进入下一级）</li>
 * <li>{@link #POP}：从左侧滑入并淡入（返回上一级）</li>
 * <li>{@link #FADE}：从下方 {@link #rise} 处上浮、由 {@link #fromScale} 放大到 1 并淡入（同级切换）</li>
 * <li>{@link #NONE}：直接替换</li>
 * </ul>
 * 子元素填满容器。
 */
public class Switcher extends WidgetGroup{
    public static final int NONE = 0, PUSH = 1, POP = 2, FADE = 3;

    private Element current;
    private int mode;
    private float time;
    private float duration = 0.34f;
    private Interp interp = Interp.standard;
    /** 横向滑动距离（像素）。 */
    public float slide = 48f;
    /** FADE 时的上浮距离（像素）与起始缩放。 */
    public float rise = 10f, fromScale = 0.985f;

    public Switcher(){
        setTransform(false);
    }

    public Switcher duration(float seconds){
        duration = seconds;
        return this;
    }

    public Switcher interp(Interp interp){
        this.interp = interp;
        return this;
    }

    public Element current(){
        return current;
    }

    public boolean isAnimating(){
        return current != null && time < duration && mode != NONE;
    }

    /** 切到 [next]（同一个元素重复调用不做任何事）。 */
    public void show(Element next, int mode){
        if(next == current) return;
        if(current != null){
            reset(current);
            current.remove();
        }
        current = next;
        this.mode = next == null ? NONE : mode;
        time = 0f;
        fresh = true;
        if(next != null){
            if(next.parent != this) addChild(next);
            next.toFront();
            next.touchable = Touchable.enabled;
        }
        apply(0f);
        invalidate();
    }

    /** 对当前页面重播一次入场动画（如首次启动结束进入主界面）。 */
    public void replay(int mode, float seconds){
        if(current == null) return;
        this.mode = mode;
        this.time = 0f;
        fresh = true;
        float old = duration;
        duration = seconds;
        apply(0f);
        restoreDuration = old;
    }

    private float restoreDuration = -1f;
    /** 切换后的第一帧不推进动画：那一帧的耗时是在建新页面，算进去动画会直接跳到中途。 */
    private boolean fresh;

    @Override
    public void act(float delta){
        super.act(delta);
        if(mode != NONE && current != null && time < duration){
            if(fresh){
                fresh = false;
                return;
            }
            time += delta;
            float t = Math.min(1f, time / duration);
            apply(interp.apply(t));
            if(t >= 1f){
                reset(current);
                mode = NONE;
                if(restoreDuration > 0f){
                    duration = restoreDuration;
                    restoreDuration = -1f;
                }
            }
        }
    }

    private void reset(Element e){
        e.translation.set(0f, 0f);
        e.color.a = 1f;
        e.setScale(1f);
        if(e instanceof Group) ((Group)e).setTransform(false);
    }

    private void apply(float p){
        if(current == null) return;
        switch(mode){
            case PUSH: current.translation.set(slide * (1f - p), 0f); current.color.a = p; break;
            case POP: current.translation.set(-slide * (1f - p), 0f); current.color.a = p; break;
            case FADE:{
                current.translation.set(0f, -rise * (1f - p));
                current.color.a = p;
                float sc = fromScale + (1f - fromScale) * p;
                if(current instanceof Group && sc < 0.9999f){
                    Group g = (Group)current;
                    g.setTransform(true);
                    g.setOrigin(Align.center);
                }
                current.setScale(sc);
                break;
            }
            default: reset(current);
        }
    }

    @Override
    public void layout(){
        for(Element e : getChildren()){
            e.setBounds(0f, 0f, getWidth(), getHeight());
            e.validate();
        }
    }

    /** 页面容器总是填满父级：不把子页面的首选宽度往上传（否则长文字会把整列撑出屏幕）。 */
    @Override
    public float getPrefWidth(){
        return 0f;
    }

    @Override
    public float getMinWidth(){
        return 0f;
    }

    @Override
    public float getMinHeight(){
        return 0f;
    }

    @Override
    public float getPrefHeight(){
        return current == null ? 0f : current.getPrefHeight();
    }
}
