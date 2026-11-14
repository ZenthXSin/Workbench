package arc.graphics.g2d;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;

/**
 * Rustwright 二开：背景模糊（毛玻璃），等价于 CSS {@code backdrop-filter: blur()}。
 * <p>
 * 把当前已经画好的画面里、目标区域背后的那一块拷贝出来，缩小后做两遍高斯模糊，再按圆角画回原处，
 * 最后叠一层半透明底色。调用时机：背景内容画完之后、浮层内容画之前。
 */
public final class Backdrop{
    /** 缩小倍数：越大越省、模糊越重。 */
    private static final int DOWN = 4;

    private static Texture grab;
    private static int grabW, grabH;
    private static FrameBuffer a, b;
    private static Shader copyShader, blurShader;
    private static final TextureRegion region = new TextureRegion();

    private static float scaleU, scaleV, dirX, dirY;

    private Backdrop(){
    }

    /**
     * 在 (x, y, w, h) 的圆角区域内画背景模糊。blur 为模糊半径（与 CSS 一样是世界单位 / 逻辑像素），
     * tint 为叠在上面的半透明底色（可为 null）。
     */
    public static void draw(float x, float y, float w, float h, float radius, float blur, Color tint){
        if(w <= 0 || h <= 0) return;
        Draw.flush();
        init();

        float aa = Shapes.aa();
        float blurPx = blur / aa;
        Mat proj = Core.batch.getProjection();
        int bbw = Core.graphics.getBackBufferWidth(), bbh = Core.graphics.getBackBufferHeight();
        // 世界坐标 → 后台缓冲像素
        float sx0 = toScreen(proj, x, y, true, bbw), sy0 = toScreen(proj, x, y, false, bbh);
        float sx1 = toScreen(proj, x + w, y + h, true, bbw), sy1 = toScreen(proj, x + w, y + h, false, bbh);
        float pad = blurPx * 2f;
        int gx = Math.max(0, (int)Math.floor(Math.min(sx0, sx1) - pad));
        int gy = Math.max(0, (int)Math.floor(Math.min(sy0, sy1) - pad));
        int gx2 = Math.min(bbw, (int)Math.ceil(Math.max(sx0, sx1) + pad));
        int gy2 = Math.min(bbh, (int)Math.ceil(Math.max(sy0, sy1) + pad));
        int gw = gx2 - gx, gh = gy2 - gy;
        if(gw <= 0 || gh <= 0) return;

        // 1. 拷贝背后的画面
        if(grab == null || grabW < gw || grabH < gh){
            if(grab != null) grab.dispose();
            grabW = Math.max(gw, grabW);
            grabH = Math.max(gh, grabH);
            grab = new Texture(grabW, grabH);
            grab.setFilter(Texture.TextureFilter.linear);
        }
        grab.bind(0);
        Gl.copyTexSubImage2D(Gl.texture2d, 0, 0, 0, gx, gy, gw, gh);

        // 2. 缩小 + 两遍高斯模糊（各做两次，模糊更圆润）
        int tw = Math.max(1, gw / DOWN), th = Math.max(1, gh / DOWN);
        if(a.getWidth() != tw || a.getHeight() != th){
            a.resize(tw, th);
            b.resize(tw, th);
            a.getTexture().setFilter(Texture.TextureFilter.linear);
            b.getTexture().setFilter(Texture.TextureFilter.linear);
        }
        scaleU = gw / (float)grabW;
        scaleV = gh / (float)grabH;
        a.begin();
        Draw.blit(grab, copyShader);
        a.end();
        float spread = Math.max(0.5f, blurPx / DOWN / 3f);
        for(int i = 0; i < 2; i++){
            dirX = spread / tw;
            dirY = 0f;
            b.begin();
            Draw.blit(a.getTexture(), blurShader);
            b.end();
            dirX = 0f;
            dirY = spread / th;
            a.begin();
            Draw.blit(b.getTexture(), blurShader);
            a.end();
        }

        // 3. 按圆角画回目标区域（帧缓冲纹理 y 向上：上边对应较大的 v）
        float left = (Math.min(sx0, sx1) - gx) / gw, right = (Math.max(sx0, sx1) - gx) / gw;
        float bottom = (Math.min(sy0, sy1) - gy) / gh, top = (Math.max(sy0, sy1) - gy) / gh;
        region.set(a.getTexture());
        region.u = left;
        region.u2 = right;
        region.v = top;
        region.v2 = bottom;
        Shapes.texture(region, x, y, w, h, radius, Color.white);
        if(tint != null && tint.a > 0f) Shapes.rect(x, y, w, h, radius, tint);
    }

    private static float toScreen(Mat proj, float x, float y, boolean horizontal, int size){
        float[] m = proj.val;
        float ndc = horizontal ? m[Mat.M00] * x + m[Mat.M01] * y + m[Mat.M02] : m[Mat.M10] * x + m[Mat.M11] * y + m[Mat.M12];
        return (ndc + 1f) / 2f * size;
    }

    private static void init(){
        if(copyShader != null) return;
        a = new FrameBuffer(4, 4);
        b = new FrameBuffer(4, 4);
        String vert =
            "attribute vec4 a_position;\n" +
            "attribute vec2 a_texCoord0;\n" +
            "varying vec2 v_uv;\n" +
            "void main(){ v_uv = a_texCoord0; gl_Position = a_position; }\n";
        copyShader = new Shader(vert,
            "uniform sampler2D u_texture;\n" +
            "uniform vec2 u_scale;\n" +
            "varying vec2 v_uv;\n" +
            "void main(){ gl_FragColor = vec4(texture2D(u_texture, v_uv * u_scale).rgb, 1.0); }\n"){
            @Override
            public void apply(){
                setUniformi("u_texture", 0);
                setUniformf("u_scale", scaleU, scaleV);
            }
        };
        blurShader = new Shader(vert,
            "uniform sampler2D u_texture;\n" +
            "uniform vec2 u_dir;\n" +
            "varying vec2 v_uv;\n" +
            "void main(){\n" +
            // 9 抽头高斯（利用线性过滤合并成 5 次采样）
            "  vec3 c = texture2D(u_texture, v_uv).rgb * 0.2270270270;\n" +
            "  c += texture2D(u_texture, v_uv + u_dir * 1.3846153846).rgb * 0.3162162162;\n" +
            "  c += texture2D(u_texture, v_uv - u_dir * 1.3846153846).rgb * 0.3162162162;\n" +
            "  c += texture2D(u_texture, v_uv + u_dir * 3.2307692308).rgb * 0.0702702703;\n" +
            "  c += texture2D(u_texture, v_uv - u_dir * 3.2307692308).rgb * 0.0702702703;\n" +
            "  gl_FragColor = vec4(c, 1.0);\n" +
            "}\n"){
            @Override
            public void apply(){
                setUniformi("u_texture", 0);
                setUniformf("u_dir", dirX, dirY);
            }
        };
    }

    /** 释放缓存的纹理（一般不需要调用）。 */
    public static void dispose(){
        if(grab != null) grab.dispose();
        if(a != null) a.dispose();
        if(b != null) b.dispose();
        grab = null;
        a = b = null;
        copyShader = blurShader = null;
    }
}
