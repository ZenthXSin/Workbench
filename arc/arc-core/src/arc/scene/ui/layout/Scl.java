package arc.scene.ui.layout;

import arc.*;

/** Utilities for UI scale.*/
public class Scl{
    private static float scl = -1;
    private static float addition = 0f;
    private static float product = 1f;
    private static float fontScale = 1f;
    private static final float debugScale = 1f;

    public static void setProduct(float product){
        Scl.product = product;
        scl = -1;
    }

    public static void setAddition(float addition){
        Scl.addition = addition;
        scl = -1;
    }

    /** Rustwright：系统字体缩放（Android Configuration.fontScale），文字尺寸额外乘上它，与 sp 一致。 */
    public static void setFontScale(float scale){
        fontScale = scale <= 0f ? 1f : scale;
    }

    public static float fontScale(){
        return fontScale;
    }

    /** 文字尺寸：dp × 系统字体缩放。 */
    public static float sp(float amount){
        return scl(amount) * fontScale;
    }

    public static float scl(){
        return scl(1f);
    }

    public static float scl(float amount){
        if(scl < 0f){
            //calculate scaling value if it hasn't been set yet
            if(Core.app.isDesktop() || Core.app.isWeb()){
                scl = product;
            }else{
                // Rustwright：直接用系统 dp 密度（原 Mindustry 公式会把 2.75 压成 2.0，界面整体偏小）
                scl = Math.max(Core.graphics.getDensity() + addition, 1f) * product;
            }
        }
        return amount * scl * debugScale;
    }
}
