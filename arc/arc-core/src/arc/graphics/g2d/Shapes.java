package arc.graphics.g2d;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;

/**
 * Rustwright 二开：基于有向距离场（SDF）的界面图形。
 * <p>
 * 与 {@link Fill}/{@link Lines} 拼出来的多边形不同，这里每个形状都在片元着色器里按距离场计算覆盖率，
 * 任意圆角、描边、柔和阴影、圆头弧线都能平滑抗锯齿，效果与浏览器 CSS 的
 * {@code border-radius} / {@code box-shadow} 一致。
 * <p>
 * 每次调用会刷新批次并设置一组 uniform，适合界面这种几十到几百个形状的场景。
 * 坐标沿用 Arc 的 y 向上；圆角顺序为左上、右上、右下、左下（与 CSS 相同）。
 */
public final class Shapes{
    private static final int MODE_RECT = 0, MODE_SHADOW = 1, MODE_ARC = 2, MODE_MASK = 3, MODE_TEXTURE = 4;

    private static Shader shader;
    private static Texture white;
    private static TextureRegion whiteRegion;
    private static final Color tmp1 = new Color(), tmp2 = new Color();

    // 当前这次绘制的参数，由 shader.apply() 写入 uniform
    private static int mode;
    private static float quadW, quadH, halfW, halfH, cx, cy;
    private static float rTL, rTR, rBR, rBL;
    private static final float[] fill = new float[4], border = new float[4];
    private static float borderWidth, blur, arcStart, arcSweep, arcRadius, arcThickness;
    private static float u1, v1, u2, v2;
    private static Texture texture;

    private static int clipDepth;

    private Shapes(){
    }

    /** 实心圆角矩形，四角同一半径。 */
    public static void rect(float x, float y, float w, float h, float radius, Color fillColor){
        rect(x, y, w, h, radius, radius, radius, radius, fillColor, 0f, null);
    }

    /** 圆角矩形，带描边（描边画在矩形内侧，与 CSS {@code box-sizing: border-box} 一致）。 */
    public static void rect(float x, float y, float w, float h, float radius, Color fillColor, float borderWidth, Color borderColor){
        rect(x, y, w, h, radius, radius, radius, radius, fillColor, borderWidth, borderColor);
    }

    /** 圆角矩形，四角半径分别指定（左上、右上、右下、左下），可选描边。fillColor 可为 null（只画描边）。 */
    public static void rect(float x, float y, float w, float h, float tl, float tr, float br, float bl,
                            Color fillColor, float borderW, Color borderColor){
        if(w <= 0 || h <= 0) return;
        float aa = aa();
        setup(MODE_RECT, x + w / 2f, y + h / 2f, w / 2f, h / 2f, w + aa * 4f, h + aa * 4f);
        radii(tl, tr, br, bl, w, h);
        put(fill, fillColor);
        put(border, borderColor);
        borderWidth = borderColor == null ? 0f : borderW;
        drawQuad();
    }

    /** 柔和阴影（CSS box-shadow：offsetX、offsetY 向右下为正，blur 为模糊半径，spread 为扩展）。 */
    public static void shadow(float x, float y, float w, float h, float radius, float offsetX, float offsetY, float blurRadius, float spread, Color color){
        if(color == null || color.a <= 0f) return;
        float sx = x - spread + offsetX, sy = y - spread - offsetY, sw = w + spread * 2f, sh = h + spread * 2f;
        float r = Math.max(0f, radius + spread);
        float pad = blurRadius * 1.5f + aa() * 4f;
        setup(MODE_SHADOW, sx + sw / 2f, sy + sh / 2f, sw / 2f, sh / 2f, sw + pad * 2f, sh + pad * 2f);
        radii(r, r, r, r, sw, sh);
        put(fill, color);
        blur = Math.max(blurRadius, 0.001f);
        drawQuad();
    }

    /** 实心圆。 */
    public static void circle(float centerX, float centerY, float radius, Color color){
        rect(centerX - radius, centerY - radius, radius * 2f, radius * 2f, radius, color);
    }

    /**
     * 圆头弧线。startDeg 从正上方开始、顺时针为正；sweepDeg 为扫过的角度（0..360）。
     * radius 为弧线中心线半径，thickness 为线宽。
     */
    public static void arc(float centerX, float centerY, float radius, float thickness, float startDeg, float sweepDeg, Color color){
        if(sweepDeg <= 0f || color == null) return;
        float ext = radius + thickness / 2f + aa() * 2f;
        setup(MODE_ARC, centerX, centerY, ext, ext, ext * 2f, ext * 2f);
        put(fill, color);
        arcStart = startDeg;
        arcSweep = Math.min(sweepDeg, 360f);
        arcRadius = radius;
        arcThickness = thickness;
        drawQuad();
    }

    /** 在圆角矩形内画一张纹理（图片圆角裁剪、毛玻璃等）。region 为 null 时使用整张纹理。 */
    public static void texture(TextureRegion region, float x, float y, float w, float h, float radius, Color tint){
        if(region == null || w <= 0 || h <= 0) return;
        float aa = aa();
        setup(MODE_TEXTURE, x + w / 2f, y + h / 2f, w / 2f, h / 2f, w + aa * 4f, h + aa * 4f);
        radii(radius, radius, radius, radius, w, h);
        put(fill, tint == null ? Color.white : tint);
        texture = region.texture;
        u1 = region.u;
        v1 = region.v;
        u2 = region.u2;
        v2 = region.v2;
        drawQuad();
        texture = null;
    }

    /**
     * 开始圆角裁剪：之后绘制的内容只在这个圆角矩形内可见，可嵌套，必须与 {@link #clipEnd()} 成对调用。
     * 需要模板缓冲（Android / SDL 配置里 stencil = 8）。
     */
    public static void clipBegin(float x, float y, float w, float h, float radius){
        Draw.flush();
        if(clipDepth == 0){
            Gl.enable(Gl.stencilTest);
            Gl.clearStencil(0);
            Gl.clear(Gl.stencilBufferBit);
        }
        Gl.stencilFunc(Gl.equal, clipDepth, 0xff);
        Gl.stencilOp(Gl.keep, Gl.keep, Gl.incr);
        Gl.colorMask(false, false, false, false);
        mask(x, y, w, h, radius);
        clipDepth++;
        Gl.colorMask(true, true, true, true);
        Gl.stencilFunc(Gl.equal, clipDepth, 0xff);
        Gl.stencilOp(Gl.keep, Gl.keep, Gl.keep);
        clipStack[clipDepth - 1][0] = x;
        clipStack[clipDepth - 1][1] = y;
        clipStack[clipDepth - 1][2] = w;
        clipStack[clipDepth - 1][3] = h;
        clipStack[clipDepth - 1][4] = radius;
    }

    private static final float[][] clipStack = new float[32][5];

    /** 结束最近一次 {@link #clipBegin}。 */
    public static void clipEnd(){
        if(clipDepth <= 0) return;
        Draw.flush();
        float[] c = clipStack[clipDepth - 1];
        Gl.stencilFunc(Gl.equal, clipDepth, 0xff);
        Gl.stencilOp(Gl.keep, Gl.keep, Gl.decr);
        Gl.colorMask(false, false, false, false);
        mask(c[0], c[1], c[2], c[3], c[4]);
        clipDepth--;
        Gl.colorMask(true, true, true, true);
        if(clipDepth == 0){
            Gl.disable(Gl.stencilTest);
        }else{
            Gl.stencilFunc(Gl.equal, clipDepth, 0xff);
            Gl.stencilOp(Gl.keep, Gl.keep, Gl.keep);
        }
    }

    /** 当前嵌套的圆角裁剪层数。 */
    public static int clipDepth(){
        return clipDepth;
    }

    private static void mask(float x, float y, float w, float h, float radius){
        setup(MODE_MASK, x + w / 2f, y + h / 2f, w / 2f, h / 2f, w, h);
        radii(radius, radius, radius, radius, w, h);
        drawQuad();
        Draw.flush();
    }

    /** 世界坐标里一个屏幕像素的大小，用于抗锯齿宽度。 */
    public static float aa(){
        Mat proj = Core.batch.getProjection();
        float sx = Math.abs(proj.val[Mat.M00]) * Core.graphics.getWidth() / 2f;
        return sx <= 0f ? 1f : 1f / sx;
    }

    private static void radii(float tl, float tr, float br, float bl, float w, float h){
        float max = Math.min(w, h) / 2f;
        rTL = Mathf.clamp(tl, 0f, max);
        rTR = Mathf.clamp(tr, 0f, max);
        rBR = Mathf.clamp(br, 0f, max);
        rBL = Mathf.clamp(bl, 0f, max);
    }

    private static void put(float[] out, Color c){
        if(c == null){
            out[0] = out[1] = out[2] = out[3] = 0f;
        }else{
            // 乘上当前 Draw 的整体透明度，便于淡入淡出
            float a = c.a * Draw.getColor().a;
            out[0] = c.r;
            out[1] = c.g;
            out[2] = c.b;
            out[3] = a;
        }
    }

    private static void setup(int m, float centerX, float centerY, float hw, float hh, float qw, float qh){
        init();
        mode = m;
        cx = centerX;
        cy = centerY;
        halfW = hw;
        halfH = hh;
        quadW = qw;
        quadH = qh;
    }

    private static void drawQuad(){
        Draw.flush();
        Shader prev = Core.batch.getShader();
        Draw.shader(shader, true);
        float col = Draw.getColor().toFloatBits();
        Draw.color(Color.white);
        if(mode == MODE_TEXTURE && texture != null){
            // 纹理模式：仍然用 0..1 的局部坐标，真实采样坐标由 uniform 换算
            texture.bind(1);
            Gl.activeTexture(Gl.texture0);
        }
        Draw.rect(whiteRegion, cx, cy, quadW, quadH);
        Draw.flush();
        Draw.color(col);
        Draw.shader(prev == shader ? null : prev, true);
    }

    private static void init(){
        if(shader != null) return;
        Pixmap p = new Pixmap(1, 1);
        p.fill(Color.white);
        white = new Texture(p);
        p.dispose();
        whiteRegion = new TextureRegion(white);
        shader = new Shader(VERT, FRAG){
            @Override
            public void apply(){
                setUniformi("u_mode", mode);
                setUniformf("u_quad", quadW, quadH);
                setUniformf("u_half", halfW, halfH);
                setUniformf("u_radius", rTL, rTR, rBR, rBL);
                setUniformf("u_fill", fill[0], fill[1], fill[2], fill[3]);
                setUniformf("u_border", border[0], border[1], border[2], border[3]);
                setUniformf("u_borderWidth", borderWidth);
                setUniformf("u_blur", blur);
                setUniformf("u_aa", aa());
                float mid = (arcStart + arcSweep / 2f) * Mathf.degRad;
                float half = arcSweep / 2f * Mathf.degRad;
                setUniformf("u_arc", mid, half, arcRadius, arcThickness);
                setUniformf("u_uv", u1, v1, u2, v2);
                setUniformi("u_image", 1);
                // 1×1 纹理的区域坐标可能带内缩，着色器里按实际范围归一化
                setUniformf("u_quv", whiteRegion.u, whiteRegion.v, whiteRegion.u2, whiteRegion.v2);
            }
        };
    }

    private static final String VERT =
        "#define HIGHP\n" +
        "attribute vec4 a_position;\n" +
        "attribute vec4 a_color;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "uniform mat4 u_projTrans;\n" +
        "varying vec2 v_uv;\n" +
        "void main(){\n" +
        "  v_uv = a_texCoord0;\n" +
        "  gl_Position = u_projTrans * a_position;\n" +
        "}\n";

    private static final String FRAG =
        "#define HIGHP\n" +
        "uniform int u_mode;\n" +
        "uniform vec2 u_quad;\n" +
        "uniform vec2 u_half;\n" +
        "uniform vec4 u_radius;\n" +
        "uniform vec4 u_fill;\n" +
        "uniform vec4 u_border;\n" +
        "uniform float u_borderWidth;\n" +
        "uniform float u_blur;\n" +
        "uniform float u_aa;\n" +
        "uniform vec4 u_arc;\n" +
        "uniform vec4 u_uv;\n" +
        "uniform vec4 u_quv;\n" +
        "uniform sampler2D u_texture;\n" +
        "uniform sampler2D u_image;\n" +
        "varying vec2 v_uv;\n" +
        // 圆角矩形距离场（Inigo Quilez），r = 左上、右上、右下、左下，y 向上
        "float sdRoundBox(vec2 p, vec2 b, vec4 r){\n" +
        "  float rad = p.x > 0.0 ? (p.y > 0.0 ? r.y : r.z) : (p.y > 0.0 ? r.x : r.w);\n" +
        "  vec2 q = abs(p) - b + rad;\n" +
        "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n" +
        "}\n" +
        "float erfApprox(float x){\n" +
        "  float s = sign(x); float a = abs(x);\n" +
        "  float t = 1.0 + (0.278393 + (0.230389 + (0.000972 + 0.078108 * a) * a) * a) * a;\n" +
        "  t *= t;\n" +
        "  return s - s / (t * t);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec2 n = (v_uv - u_quv.xy) / (u_quv.zw - u_quv.xy);\n" +
        "  vec2 p = (n - 0.5) * u_quad;\n" +
        "  p.y = -p.y;\n" +
        "  float aa = u_aa;\n" +
        "  if(u_mode == 2){\n" +
        // 圆头弧线：把弧的中点转到 +y 方向
        "    float c = cos(u_arc.x), s = sin(u_arc.x);\n" +
        "    vec2 q = vec2(c * p.x - s * p.y, s * p.x + c * p.y);\n" +
        "    q = vec2(q.x, q.y);\n" +
        "    vec2 sc = vec2(sin(u_arc.y), cos(u_arc.y));\n" +
        "    q.x = abs(q.x);\n" +
        "    float d = (sc.y * q.x > sc.x * q.y) ? length(q - sc * u_arc.z) : abs(length(q) - u_arc.z);\n" +
        "    d -= u_arc.w * 0.5;\n" +
        "    float cov = clamp(0.5 - d / aa, 0.0, 1.0);\n" +
        "    gl_FragColor = vec4(u_fill.rgb, u_fill.a * cov);\n" +
        "    return;\n" +
        "  }\n" +
        "  float d = sdRoundBox(p, u_half, u_radius);\n" +
        "  if(u_mode == 3){\n" +
        "    if(d > 0.0) discard;\n" +
        "    gl_FragColor = vec4(1.0);\n" +
        "    return;\n" +
        "  }\n" +
        "  if(u_mode == 1){\n" +
        "    float sigma = u_blur * 0.5;\n" +
        "    float a = 0.5 - 0.5 * erfApprox(d / (sigma * 1.41421356));\n" +
        "    gl_FragColor = vec4(u_fill.rgb, u_fill.a * a);\n" +
        "    return;\n" +
        "  }\n" +
        "  float outer = clamp(0.5 - d / aa, 0.0, 1.0);\n" +
        "  if(u_mode == 4){\n" +
        "    vec2 t = (p / (u_half * 2.0)) + 0.5;\n" +
        "    vec2 uv = vec2(mix(u_uv.x, u_uv.z, t.x), mix(u_uv.w, u_uv.y, t.y));\n" +
        "    vec4 img = texture2D(u_image, uv) * u_fill;\n" +
        "    gl_FragColor = vec4(img.rgb, img.a * outer);\n" +
        "    return;\n" +
        "  }\n" +
        "  vec4 col = u_fill;\n" +
        "  if(u_borderWidth > 0.0){\n" +
        "    float inner = clamp(0.5 - (d + u_borderWidth) / aa, 0.0, 1.0);\n" +
        // 内部填充与描边按覆盖率混合（源在上）
        "    vec4 f = vec4(u_fill.rgb, u_fill.a * inner);\n" +
        "    vec4 b = vec4(u_border.rgb, u_border.a * (1.0 - inner));\n" +
        "    float a = f.a + b.a * (1.0 - f.a);\n" +
        "    vec3 rgb = a > 0.0 ? (f.rgb * f.a + b.rgb * b.a * (1.0 - f.a)) / a : vec3(0.0);\n" +
        "    col = vec4(rgb, a);\n" +
        "  }\n" +
        "  gl_FragColor = vec4(col.rgb, col.a * outer);\n" +
        "}\n";
}
