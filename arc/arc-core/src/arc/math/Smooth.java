package arc.math;

/**
 * Rustwright 二开：随状态变化自动平滑过渡的数值，等价于 CSS {@code transition}。
 * 每帧调用 {@link #update(float)}；{@link #to(float)} 改目标时从当前值重新开始过渡。
 */
public class Smooth{
    private float value, from, target, time, duration, delay;
    private Interp interp;

    public Smooth(float initial, float duration, Interp interp){
        this.value = this.from = this.target = initial;
        this.duration = duration;
        this.interp = interp;
        this.time = duration;
    }

    public Smooth(float initial, float duration){
        this(initial, duration, Interp.standard);
    }

    /** CSS transition-delay：之后每次 {@link #to(float)} 先等这么久再开始过渡。 */
    public Smooth delay(float seconds){
        this.delay = seconds;
        return this;
    }

    /** 设定新目标；与当前目标相同时不打断。 */
    public Smooth to(float target){
        if(target == this.target) return this;
        this.from = value;
        this.target = target;
        this.time = -delay;
        return this;
    }

    /** 立即跳到某个值，不做过渡。 */
    public Smooth snap(float v){
        value = from = target = v;
        time = duration;
        return this;
    }

    public float update(float delta){
        if(time < duration){
            time = Math.min(duration, time + delta);
            if(time < 0f) return value;
            value = from + (target - from) * interp.apply(duration <= 0f ? 1f : time / duration);
        }else{
            value = target;
        }
        return value;
    }

    public float get(){
        return value;
    }

    public float target(){
        return target;
    }

    public boolean isAnimating(){
        return time < duration;
    }
}
