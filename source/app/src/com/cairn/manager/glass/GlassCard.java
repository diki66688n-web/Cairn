package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 玻璃卡片 —— 不依赖任何第三方库的自绘容器。
 *
 * ===================================================================
 * 【为什么不再用 LiquidGlassView 做卡片】
 * ===================================================================
 *
 * 之前 GlassPanel 直接继承官方的 LiquidGlassView。它在底部导航栏上工作得
 * 很好，但拿来做页面里的卡片会出问题：
 *
 *   LiquidGlassView 的背景来源是 ActivityBackdropManager.getBackdrop(View)，
 *   这是 **Activity 级共享** 的一份捕获。它按「窗口」抓画面，而不是按
 *   「这个 View 自己的父容器」抓。
 *
 *   管理器有多个页面叠在同一个 FrameLayout 里靠 visibility 切换。
 *   结果就是：无论切到哪一页，每个玻璃卡片抓到的都是同一份窗口画面，
 *   于是三张截图里内容长得一模一样 —— 页面确实切换了（日志证实
 *   switchTab(3) -> set=V 完全正确），但玻璃卡片显示的还是同一块背景。
 *
 * ===================================================================
 * 【这个类怎么画】
 * ===================================================================
 *
 * 纯 Canvas 自绘，一帧成本很低（两次 drawRoundRect + 一层线性渐变）：
 *
 *   1. 底色填充：半透明白（约 8%），让下面背景透出来
 *   2. 顶部高光：从上到下的线性渐变，模拟光打在玻璃上边缘
 *   3. 描边：1px 高透明白边，玻璃的「厚度感」全靠这一圈
 *   4. 圆角：用 Path + RectF 精确控制，和官方组件的曲率一致
 *
 * 视觉上达不到 AGSL 那种真实折射，但绝对干净、稳定、不串页 ——
 * 而底部导航栏仍然是官方 LiquidBottomTabs，真正的液态玻璃球还在那里。
 */
public class GlassCard extends FrameLayout {

    /** 圆角半径（px） */
    private float radiusPx;
    /** 填充色 */
    private int fillColor = 0x1AFFFFFF;
    /** 描边色 */
    private int strokeColor = 0x26FFFFFF;
    /** 顶部高光强度 */
    private float highlightAlpha = 0.10f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public GlassCard(Context c) {
        super(c);
        init();
    }

    public GlassCard(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        radiusPx = dp(20);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, dp(1) * 0.75f));
        hiPaint.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---- 链式配置（和旧 GlassPanel 的调用风格保持一致） ----

    public GlassCard setCornerRadius(float dpValue) {
        radiusPx = dp(dpValue);
        invalidate();
        return this;
    }

    public GlassCard setTint(int color) {
        fillColor = color;
        invalidate();
        return this;
    }

    public GlassCard setStrokeColor(int color) {
        strokeColor = color;
        invalidate();
        return this;
    }

    public GlassCard setHighlightAlpha(float a) {
        highlightAlpha = Math.max(0f, Math.min(1f, a));
        invalidate();
        return this;
    }

    /** 保留旧接口，让上层代码零改动（这些在自绘实现里没有对应物） */
    public GlassCard setRefractionHeight(float v) { return this; }
    public GlassCard setRefractionAmount(float v) { return this; }
    public GlassCard setChromaticAberration(float v) { return this; }
    public GlassCard setDepthEffect(boolean v) { return this; }
    public GlassCard setBlurRadius(float v) { return this; }
    public GlassCard setHighlightColor(int c) { return this; }
    public GlassCard setHighlightAngle(float r) { return this; }
    public GlassCard setBackgroundSource(Object s) { return this; }
    public GlassCard setContentSource(android.view.View v) { return this; }
    public int getGlassTier() { return 1; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rect.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        // 顶部高光：从顶部 22% 高度的白到全透明
        hiPaint.setShader(new LinearGradient(
                0, 0, 0, h * 0.22f,
                new int[]{argb(highlightAlpha), 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
    }

    private static int argb(float a) {
        int al = (int) (Math.max(0f, Math.min(1f, a)) * 255f);
        return (al << 24) | 0x00FFFFFF;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1) 底色
        fillPaint.setColor(fillColor);
        canvas.drawPath(path, fillPaint);

        // 2) 顶部高光（裁在圆角内）
        if (highlightAlpha > 0.001f) {
            int save = canvas.save();
            canvas.clipPath(path);
            canvas.drawRect(0, 0, getWidth(), getHeight() * 0.22f, hiPaint);
            canvas.restoreToCount(save);
        }

        // 3) 描边（内缩半个描边宽，避免被裁掉）
        float half = strokePaint.getStrokeWidth() / 2f;
        rect.set(half, half, getWidth() - half, getHeight() - half);
        Path sp = new Path();
        sp.addRoundRect(rect, radiusPx - half, radiusPx - half, Path.Direction.CW);
        strokePaint.setColor(strokeColor);
        canvas.drawPath(sp, strokePaint);

        super.onDraw(canvas);
    }
}
