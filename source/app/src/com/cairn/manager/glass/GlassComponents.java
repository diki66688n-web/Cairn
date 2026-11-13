package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 玻璃组件工厂。
 *
 * 把「液态玻璃的各种形状」封装成一行调用，MainActivity 只关心内容不关心绘制。
 *
 * 为什么用工厂方法而不是 XML 布局：
 *   本项目走手写 build.sh（无 Gradle），虽然已经接了 aapt2 能编译 res/，
 *   但玻璃组件的参数（折射高度、色散强度、圆角）都是运行期按 dp 换算的，
 *   用 XML 反而要写一堆自定义属性解析。工厂方法更直接，也便于动态调整。
 */
public final class GlassComponents {

    private GlassComponents() {
    }

    /** dp 转 px */
    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 玻璃卡片：最常用的容器。
     *
     * @param ctx    上下文
     * @param radius 圆角 dp
     * @param tint   玻璃色调（含 alpha），0 表示用默认
     * @param spread 是否开启色散（棱镜镶边），小面积才开，大面积会掉帧
     */
    public static GlassCard card(Context ctx, float radius, int tint, boolean spread) {
        /*
         * 返回自绘的 GlassCard，不是官方 LiquidGlassView。
         *
         * 原因见 GlassCard 顶部的长注释：官方 LiquidGlassView 的背景是
         * Activity 级共享捕获，多个页面叠在同一个 FrameLayout 里时，
         * 每张卡片抓到的都是同一份窗口画面，切页后内容看起来完全一样。
         *
         * GlassCard 用 Canvas 自绘（底色 + 顶部高光 + 描边 + 圆角），
         * 不参与任何背景捕获，所以每页显示的一定是本页内容。
         */
        GlassCard p = new GlassCard(ctx);
        p.setCornerRadius(radius);
        p.setTint(tint == 0 ? 0x14FFFFFF : tint);
        return p;
    }

    public static GlassCard button(Context ctx, String label, float radius,
                                   int tint, int textColor) {
        GlassCard p = new GlassCard(ctx);
        p.setCornerRadius(radius);
        p.setTint(tint);
        p.setClickable(true);
        p.setFocusable(true);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        // 文字铺满整块玻璃，点击事件由父容器统一接管
        tv.setClickable(false);
        p.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        p.setMinimumHeight(dp(ctx, 52));
        return p;
    }

    public static GlassCard statusCard(Context ctx, String title, String value,
                                        int dotColor, float radius) {
        GlassCard p = card(ctx, radius, 0, false);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16));

        // 状态圆点
        View dot = new View(ctx);
        int d = dp(ctx, 10);
        dot.setBackground(GlowDot.create(dotColor, d));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(d, d);
        dotLp.rightMargin = dp(ctx, 14);
        row.addView(dot, dotLp);

        // 文字区
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(13f);
        t1.setTextColor(Color.parseColor("#B3C8D2E0"));
        col.addView(t1);

        TextView t2 = new TextView(ctx);
        t2.setText(value);
        t2.setTextSize(17f);
        t2.setTextColor(Color.parseColor("#FFF2F5FA"));
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t2);

        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        p.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    /**
     * 悬浮按钮（圆形玻璃）。
     *
     * 圆形玻璃的折射效果最明显 —— 整个边缘都在弯折背景，
     * 是液态玻璃最有辨识度的形态，所以这里默认开启色散。
     */
    public static GlassCard floatingButton(Context ctx, String glyph, int size) {
        GlassCard p = new GlassCard(ctx);
        int s = dp(ctx, size);
        // 圆角设成半径即得正圆
        p.setCornerRadius(size / 2f);
        p.setTint(Color.parseColor("#22FFFFFF"));
        p.setRefractionHeight(10f);
        p.setRefractionAmount(14f);
        p.setChromaticAberration(1f);
        p.setDepthEffect(true);
        p.setClickable(true);

        TextView tv = new TextView(ctx);
        tv.setText(glyph);
        tv.setTextSize(22f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        p.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(ctx, 20);
        lp.bottomMargin = dp(ctx, 96);
        p.setLayoutParams(lp);
        return p;
    }

    /**
     * 带发光的状态圆点。
     *
     * 不是普通实心圆：外面套一圈同色低透明度光晕，
     * 让小圆点看起来像"在发光"，这是深色 UI 里最省力的状态强调手法。
     */
    public static class GlowDot extends View {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

        private GlowDot(Context c, int color) {
            super(c);
            core.setColor(color);
            // 光晕：同色但 alpha 降到 ~40
            int a = Color.alpha(color);
            glow.setColor(Color.argb(a / 3, Color.red(color),
                    Color.green(color), Color.blue(color)));
        }

        public static android.graphics.drawable.Drawable create(int color, int sizePx) {
            // 用固定尺寸的 Drawable 包装，避免依赖 View 测量
            return new DotDrawable(color, sizePx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            canvas.drawCircle(cx, cy, r, glow);
            canvas.drawCircle(cx, cy, r * 0.5f, core);
        }
    }

    /** 状态圆点的 Drawable 版本（给 View.setBackground 用） */
    private static class DotDrawable extends android.graphics.drawable.Drawable {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        DotDrawable(int color, int sizePx) {
            this.size = sizePx;
            core.setColor(color);
            int a = Color.alpha(color);
            glow.setColor(Color.argb(a / 3, Color.red(color),
                    Color.green(color), Color.blue(color)));
        }

        @Override
        public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            float cx = b.centerX();
            float cy = b.centerY();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(cx, cy, r, glow);
            canvas.drawCircle(cx, cy, r * 0.5f, core);
        }

        @Override
        public void setAlpha(int alpha) {
            core.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            core.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

}
