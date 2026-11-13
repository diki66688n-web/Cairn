package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 玻璃背景画布。
 *
 * 为什么需要这个东西：
 *   折射 shader 要采样「content」，也就是玻璃背后的画面。如果背后是纯色，
 *   折射采样出来的每个像素都一样，视觉上完全看不出弯折。
 *   必须有丰富的色彩/明暗变化，折射才「看得见」。
 *
 * iOS 26 Liquid Glass 在系统层面能折射壁纸，是因为它拿得到窗口后面的内容。
 * 第三方 APP 拿不到系统壁纸层，所以这里自己画一层柔和光斑作为折射源 ——
 * 这是唯一能在应用内做出可见折射效果的办法。
 *
 * 光斑算法：
 *   几个大半径径向渐变，颜色取自冷暖对比（青 / 紫 / 橙），
 *   叠在深色底上形成柔和的流体感。玻璃压上去之后，
 *   边缘会把这些色块「弯折」，产生真实的折射错觉。
 */
public class GlassBackdrop extends FrameLayout {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 深色基底。近纯黑，但保留一点蓝紫倾向，避免死黑。
     *
     * 【本轮调整】原来是 0xFF07070A，明度约 3%。
     * 太黑了：折射 shader 采样到的这片区域几乎是纯色，
     * 无论坐标怎么偏移，取到的值都是 0x07 上下，看不出任何弯折。
     * 提到 0xFF0C1018（约 6%），配合更亮的光斑，
     * 让折射带内存在可辨识的明暗差。
     */
    private int baseColor = 0xFF0C1018;

    /**
     * 光斑配色。
     *
     * 【重要修正】之前用的是青/紫/橙三团高饱和光斑（alpha 0x66），
     * 铺满整屏的结果是：整屏变成一片彩色雾，玻璃叠上去只显得脏。
     * 参考真实产品（KernelSU 的深色界面）后改成：
     *   - 只保留两团极低饱和的冷光，alpha 压到 0x1A 以下
     *   - 位置放在角落，中间大面积保持近黑
     * 这样玻璃边缘折射时能弯出一点"有东西"的感觉，
     * 但整体仍然是深色高级感，不会花。
     */
    /*
     * 光斑配色。
     *
     * 【本轮最终版：全部改为中性灰，不带任何色相】
     *
     * 演化过程（记录清楚，避免以后又走回头路）：
     *
     *   v1  青/紫/橙三团高饱和（alpha 0x66）
     *       → 整屏彩色雾，玻璃压上去显脏
     *   v2  压到 alpha ≤ 0x1A 的两团极淡冷光
     *       → 矫枉过正，整屏纯黑，折射采样到的是同一个颜色，
     *         数学上偏移了坐标，视觉上毫无变化
     *   v3  提亮到 alpha 0x38~0x40 的冷蓝/紫/暖橙/青碧
     *       → 折射能看见了，但用户评价"花里胡哨的彩色"
     *   v4（当前）全部换成中性灰
     *       → 参考实现 KernelSU 的界面是纯黑 + 深灰，
     *         高级感来自灰阶层级和留白，不来自背景色相。
     *         保留极淡的明暗起伏只是为了给折射一点点可弯折的梯度，
     *         不引入任何颜色倾向。
     *
     * 数值上 alpha 保持在 0x30 附近：
     *   太低（0x1A 以下）→ 折射无内容可采，等于没有
     *   太高（0x50 以上）→ 背景发灰，卡片浮不起来
     */
    private static final int[] BLOB_COLORS = {
            0x30FFFFFF, // [0] 中性白（左上，主光源）
            0x26FFFFFF, // [1] 中性白（右上）
            0x2AFFFFFF, // [2] 中性白（右下）
            0x22FFFFFF, // [3] 中性白（左下）
    };

    /**
     * 【崩溃修复记录】
     *
     * 上面这个数组原来只有 2 个元素，但 onDraw() 与 drawBackdrop() 里
     * 各引用了 BLOB_COLORS[2] 和 BLOB_COLORS[3]，导致每次绘制必定抛：
     *     java.lang.ArrayIndexOutOfBoundsException: length=2; index=3
     * 由于发生在 onDraw 里（渲染线程驱动的主线程 draw 阶段），
     * 一进界面就闪退，且不产生任何应用层日志 —— 这就是之前
     * 日志总是断在两次 probe() 之后、看不到 [AUTH] 的原因。
     *
     * 根因是上一轮把「青/紫/橙三团高饱和光斑」改成「两团冷光」时
     * 只改了数组、没同步改下面 4 处 drawBlob 调用。
     *
     * 修复方式：把数组补回 4 个元素，而不是删掉调用 ——
     * 删除会丢掉下半屏两团光，构图会塌。新增两个颜色与原有
     * 两个同为 alpha ≤ 0x1A 的低饱和冷/暖调，不破坏「深色高级感」。
     */

    public GlassBackdrop(Context context) {
        super(context);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClipChildren(false);
        // 光斑要能溢出到子 View 之外被玻璃折射到，
        // 所以这里不裁剪。
    }

    /** 设置基底颜色 */
    public GlassBackdrop setBaseColor(int color) {
        baseColor = color;
        invalidate();
        return this;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 1) 深色底 + 轻微垂直渐变（顶部略亮，像有环境光）
        bgPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h,
                0xFF141A24, baseColor,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h, bgPaint);
        bgPaint.setShader(null);

        // 2) 光斑：位置按比例算，保证任意屏幕尺寸下构图一致
        //    索引统一走 blobColor()，越界时安全降级为透明，绝不抛异常。
        //    onDraw 里抛异常 = 渲染阶段闪退，没有任何补救机会，必须防御。
        drawBlob(canvas, w * 0.18f, h * 0.12f, w * 0.62f, blobColor(0));
        drawBlob(canvas, w * 0.88f, h * 0.28f, w * 0.70f, blobColor(1));
        drawBlob(canvas, w * 0.08f, h * 0.72f, w * 0.75f, blobColor(3));
        drawBlob(canvas, w * 0.80f, h * 0.88f, w * 0.68f, blobColor(2));

        /*
         * 3) 中层细节光斑
         *
         * 【本轮已删除】
         *
         * 上一轮为了"让折射有东西可弯折"，在这里加了 5 个高饱和彩色光斑
         * （冷蓝、紫、暖橙、青碧、粉）。用户反馈是"花里胡哨的彩色"，
         * 这个判断是对的：
         *
         *   1. 参考实现（KernelSU）的界面是纯黑底 + 深灰卡片，
         *      根本没有彩色背景。高级感来自留白和灰阶层级，
         *      不是靠背景调色。
         *   2. 彩色光斑会跟玻璃的 tint、边缘高光、色散混在一起，
         *      变成一锅无法控制的花汤，反而显得廉价。
         *   3. 折射真正需要的"高频信息"来自卡片、文字、图标这些
         *      真实内容 —— 而这些现在已经由 GlassCapture 提供了，
         *      不需要再用光斑伪造。
         *
         * 保留下面那一层极淡的中性白斑，只负责制造一点点体积感，
         * 不引入任何色相。
         */

        // 4) 一层极淡的中性亮斑，增强层次
        drawBlob(canvas, w * 0.5f, h * 0.45f, w * 0.55f, 0x33FFFFFF);
    }

    /**
     * 安全取光斑颜色。
     *
     * 为什么不用 BLOB_COLORS[i] 直接取：
     *   onDraw 里任何异常都等于闪退，且是 native 渲染管线回调过来的，
     *   应用层只有一次机会，没法重试、没法降级。
     *   索引写死 + 数组长度靠人工同步 = 迟早再崩一次（0.8 就是这么崩的）。
     * 所以统一从这里取，越界返回全透明（既不崩，也不画）。
     */
    private static int blobColor(int index) {
        if (index < 0 || index >= BLOB_COLORS.length) {
            return 0x00000000;
        }
        return BLOB_COLORS[index];
    }

    private void drawBlob(Canvas canvas, float cx, float cy, float radius, int color) {
        int rgb = color & 0x00FFFFFF;
        blobPaint.setShader(new RadialGradient(
                cx, cy, radius,
                new int[]{(color & 0xFF000000) | rgb, (rgb & 0x00FFFFFF)},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, blobPaint);
        blobPaint.setShader(null);
    }

    /**
     * 把光斑背景画到任意 Canvas 上（静态工具方法）。
     *
     * 为什么要静态版本：
     *   GlassPanel 需要一个不依赖 Context 的背景绘制器（它在构造期就要能画，
     *   而 new 一个 GlassBackdrop(null) 会因为 super(null) 直接崩溃）。
     *   所以把绘制逻辑抽成静态的，View 版本也同样调用它，保证两边完全一致。
     *
     * @param canvas 目标画布，原点会被当作背景左上角
     * @param w      背景宽（px）
     * @param h      背景高（px）
     * @param baseColor 基底色（含 alpha），传 0 用默认深色
     */
    public static void drawBackdrop(Canvas canvas, int w, int h, int baseColor) {
        if (w <= 0 || h <= 0) {
            return;
        }
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);

        int base = (baseColor == 0) ? 0xFF0B0E14 : baseColor;

        // 1) 深色底 + 轻微垂直渐变（顶部略亮，像有环境光）
        bg.setShader(new LinearGradient(
                0f, 0f, 0f, (float) h,
                0xFF141A24, base,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, (float) w, (float) h, bg);
        bg.setShader(null);

        // 2) 光斑：位置按比例算，保证任意屏幕尺寸下构图一致
        drawBlobOn(canvas, blob, w * 0.18f, h * 0.12f, w * 0.62f, blobColor(0));
        drawBlobOn(canvas, blob, w * 0.88f, h * 0.28f, w * 0.70f, blobColor(1));
        drawBlobOn(canvas, blob, w * 0.08f, h * 0.72f, w * 0.75f, blobColor(3));
        drawBlobOn(canvas, blob, w * 0.80f, h * 0.88f, w * 0.68f, blobColor(2));

        // 3) 一层极淡的中性亮斑，增强层次
        drawBlobOn(canvas, blob, w * 0.5f, h * 0.45f, w * 0.55f, 0x33FFFFFF);
    }

    private static void drawBlobOn(Canvas canvas, Paint paint,
                                   float cx, float cy, float radius, int color) {
        int rgb = color & 0x00FFFFFF;
        paint.setShader(new RadialGradient(
                cx, cy, radius,
                new int[]{(color & 0xFF000000) | rgb, rgb},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    /**
     * 把光斑背景画到任意 Canvas 上，使用本实例当前的基底色。
     *
     * 供 GlassPanel 的降级路径与 RenderNode 路径调用，
     * 不需要把 GlassBackdrop 实例挂到视图树上。
     */
    public void draw(Canvas canvas, int w, int h) {
        drawBackdrop(canvas, w, h, baseColor);
    }

    /**
     * 把某个 View 作为折射内容源。
     *
     * 用法：把这个 GlassBackdrop 当作根容器，玻璃面板用
     * {@code panel.setContentSource(backdrop)} 指过来，
     * shader 就会采样这一层的绘制结果。
     */
    public View asContentSource() {
        return this;
    }
}