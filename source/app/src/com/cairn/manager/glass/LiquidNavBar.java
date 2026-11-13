package com.cairn.manager.glass;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Color;

import com.liquidglass.java.miba.LiquidBottomTab;
import com.liquidglass.java.miba.LiquidBottomTabs;

/**
 * 底部导航栏 —— 现在直接继承官方 LiquidBottomTabs。
 *
 * ============ 为什么换 ============
 *
 * 之前这版是我自绘的 View：clipPath 裁胶囊 + 线性渐变高光 + 半透明填充，
 * 选中项是个自己画的实心圆。做出来的效果你也看到了，就是个蓝圆点，
 * 既没有液态的形变，也没有"球在标签之间流动"的过渡。
 *
 * 官方 LiquidBottomTabs 自带：
 *   - 选中指示器本身就是一个玻璃球（内部是 LiquidGlassView）
 *   - 弹簧阻尼动画（DampedDragAnimation，就是你要的"拖动球"）
 *   - 自适应前景色（玻璃后面的画面亮/暗变化时，文字自动反色）
 *   - 按下涟漪（InteractiveHighlight，跟随手指位置）
 *
 * ============ 为什么要包一层 ============
 *
 * MainActivity 里用的是：
 *   new LiquidNavBar(ctx)
 *   setOnTabSelectedListener(...)   // 回调是 onTabSelected(int)
 *   setSelectedIndex(0)
 *   setItemAlpha(i, a)
 *
 * 而官方是：
 *   addTabItem / setTabs
 *   setOnTabSelectedListener(LiquidBottomTabs.OnTabSelectedListener)  // 回调带 ViewGroup 参数
 *
 * 两者签名不一样，所以这里做适配，让 MainActivity 不用改。
 */
public class LiquidNavBar extends LiquidBottomTabs {

    /** 老接口的回调，只有下标 */
    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private OnTabSelectedListener listener;
    /** 标签文字，setItemAlpha 之类的兼容操作用得到 */
    private String[] labels = new String[0];
    /** 防止程序性设置选中时回调打转 */
    private boolean suppressCallback = false;

    public LiquidNavBar(Context context) {
        super(context);
        setContentColor(0xFFFFFFFF);
        setAccentColor(0xFF4C8DFF);
        // 官方内部会把选中回调转给我们
        super.setOnTabSelectedListener(new LiquidBottomTabs.OnTabSelectedListener() {
            @Override
            public void onTabSelected(LiquidBottomTabs tabs, int index) {
                if (suppressCallback) return;
                if (listener != null) listener.onTabSelected(index);
            }
        });
    }

    public LiquidNavBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setContentColor(0xFFFFFFFF);
        setAccentColor(0xFF4C8DFF);
    }

    // =====================================================================
    // 老接口适配
    // =====================================================================

    public LiquidNavBar setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
        return this;
    }

    /**
     * 添加一个标签。
     *
     * 图标传 null 时官方会只显示文字 —— 这正是我们想要的：
     * Cairn 的多个页面（概览 / 应用 / 日志 / 设置 / 模块）用文字标得比图标清楚。
     */
    /**
     * 创建一个「只要文字、不要图标」的标签并加上去。
     *
     * 为什么不用官方的 addTabBitmap(null, text)：
     * 那样会先 ensureIconView() 造一个 ImageView 再把 null 塞进去，
     * 虽然不抛异常，但会留下一个空 ImageView 参与布局，把文字挤偏。
     * 直接 new LiquidBottomTab 再 setText 更干净。
     */
    private void addTextTab(String text) {
        if (text == null) return;
        LiquidBottomTab tab = new LiquidBottomTab(getContext());
        tab.setText(text);
        addTab(tab);
    }

    public LiquidNavBar addLabel(String text) {
        addTextTab(text);
        String[] next = new String[labels.length + 1];
        System.arraycopy(labels, 0, next, 0, labels.length);
        next[labels.length] = text;
        labels = next;
        return this;
    }
    /** 一次性设置全部标签文字 */
    public LiquidNavBar setLabels(String[] texts) {
        clearTabs();
        labels = texts == null ? new String[0] : texts.clone();
        for (String t : labels) {
            addTextTab(t);
        }
        return this;
    }

    @Override
    public int getSelectedIndex() {
        return super.getSelectedIndex();
    }

    /** 程序性切换，不带回调，避免 switchTab 反复触发 */
    public void setSelectedIndex(int index) {
        selectTab(index, false);
    }

    /**
     * 切换选中项。
     *
     * animate=true 时走官方的弹簧动画，球会从当前标签滑过去 ——
     * 这就是你说的"从主页直接拖到模块"那个效果的来源。
     */
    public LiquidNavBar selectTab(int index, boolean animate) {
        if (index < 0 || index >= getTabCount()) return this;
        suppressCallback = !animate;
        try {
            setSelectedIndex(index, animate);
        } finally {
            suppressCallback = false;
        }
        return this;
    }

    /**
     * 设置某个标签的透明度。
     *
     * 官方没有逐标签透明度 API（它有自适应前景色，会自动反转），
     * 所以这里直接调标签自己的 label 视图。拿不到就静默跳过 ——
     * 这个方法原本只在动画里用，不是关键路径。
     */
    public void setItemAlpha(int index, float alpha) {
        try {
            LiquidBottomTab tab = getTabAt(index);
            if (tab != null && tab.getLabelView() != null) {
                tab.getLabelView().setAlpha(alpha);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 导航栏推荐高度，和其它地方保持一致 */
    public static float recommendedHeightDp() {
        return 64f;
    }
}