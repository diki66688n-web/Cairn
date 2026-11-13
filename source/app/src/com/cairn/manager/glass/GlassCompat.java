package com.cairn.manager.glass;

import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * AGSL / RuntimeShader 的反射访问层。
 *
 * 为什么必须用反射，不能用直接引用：
 *   RuntimeShader 是 API 33 (Android 13) 才加入的类。本项目 build.sh 用
 *   android-33.jar 编译（因为要用到它），但 APK 的 minSdk 仍然是 23，
 *   且真机上可能有低版本设备。如果代码里直接 import android.graphics.RuntimeShader，
 *   在 API < 33 的设备上，只要这个类被加载到验证阶段就会抛
 *   NoClassDefFoundError —— 哪怕那段代码根本没被执行。
 *   反射可以把「类的解析」推迟到运行时真正调用的那一刻，从而安全降级。
 *
 * 用到的 API 全部为 API 31+：
 *   - android.graphics.RenderEffect   (API 31)
 *   - android.graphics.RuntimeShader  (API 33)
 *   - RenderNode.setRenderEffect      (API 31)
 *
 * 降级策略：
 *   API >= 33  → 真折射（AGSL 液态玻璃）
 *   API 31-32  → 只有 RenderEffect 没有 RuntimeShader，用系统模糊
 *   API < 31   → 两者都没有，纯色半透明（GlassPanel 内部处理）
 */
public final class GlassCompat {

    /** API 33：RuntimeShader 可用，能做真折射 */
    static final int TIER_REFRACTION = 3;
    /** API 31-32：只有 RenderEffect，能做模糊但做不了折射 */
    static final int TIER_BLUR = 2;
    /** API < 31：什么效果 API 都没有，退回纯色 */
    static final int TIER_SOLID = 1;

    private static int sTier = -1;

    private GlassCompat() {
    }

    /**
     * 当前设备的玻璃渲染等级。
     *
     * 结果缓存，因为这段检查会在每帧绘制路径上被问到。
     */
    public static int tier() {
        int t = sTier;
        if (t > 0) {
            return t;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // 光看版本号不够：还要确认 RuntimeShader 真能被加载。
            // 部分定制 ROM 会改这个类的包名或直接裁掉它。
            try {
                Class.forName("android.graphics.RuntimeShader");
                t = TIER_REFRACTION;
            } catch (Throwable e) {
                t = TIER_BLUR;
            }
        } else if (Build.VERSION.SDK_INT >= 31) {
            t = TIER_BLUR;
        } else {
            t = TIER_SOLID;
        }
        sTier = t;
        return t;
    }

    /** 是否支持真折射（AGSL） */
    static boolean supportsRefraction() {
        return tier() >= TIER_REFRACTION;
    }

    /** 是否支持任何 GPU 效果（模糊或折射） */
    static boolean supportsEffect() {
        return tier() >= TIER_BLUR;
    }

    /**
     * 构造一个 RuntimeShader 并设置 AGSL 源码。
     *
     * @param agslSource AGSL 着色器源码，见 {@link GlassShaders}
     * @return RuntimeShader 实例；设备不支持或编译失败时返回 null
     */
    static Object newShader(String agslSource) {
        if (!supportsRefraction()) {
            return null;
        }
        try {
            Class<?> cls = Class.forName("android.graphics.RuntimeShader");
            Constructor<?> ctor = cls.getConstructor(String.class);
            return ctor.newInstance(agslSource);
        } catch (Throwable e) {
            // AGSL 编译失败（语法错误、驱动不支持等）不应让整个界面崩掉，
            // 退回降级路径即可。
            return null;
        }
    }

    /**
     * 给 shader 设一个 float uniform。
     *
     * AGSL 里 uniform 变量名必须与着色器源码中的声明完全一致。
     */
    static boolean setFloat(Object shader, String name, float value) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass().getMethod("setFloatUniform", String.class, float.class);
            m.invoke(shader, name, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 给 shader 设 float2 uniform */
    static boolean setFloat2(Object shader, String name, float x, float y) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setFloatUniform", String.class, float.class, float.class);
            m.invoke(shader, name, x, y);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 给 shader 设 float4 uniform */
    static boolean setFloat4(Object shader, String name, float x, float y, float z, float w) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setFloatUniform", String.class,
                            float.class, float.class, float.class, float.class);
            m.invoke(shader, name, x, y, z, w);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 给 shader 设 half4 颜色 uniform（对应 AGSL 的 layout(color) uniform） */
    static boolean setColor(Object shader, String name, float r, float g, float b, float a) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setColorUniform", String.class,
                            float.class, float.class, float.class, float.class);
            m.invoke(shader, name, r, g, b, a);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 把 RuntimeShader 包成 RenderEffect。
     *
     * @param shaderName 着色器里 {@code uniform shader xxx;} 的那个名字，
     *                   必须是 "content"（见 GlassShaders 里的声明）
     * @return RenderEffect 实例；失败返回 null
     */
    static Object newShaderEffect(Object shader, String shaderName) {
        if (shader == null) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createRuntimeShaderEffect", shader.getClass(), String.class);
            return m.invoke(null, shader, shaderName);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 创建色彩控制效果：亮度 / 对比度 / 饱和度。
     *
     * 这是「vibrancy」的实现基础。反编译参考实现里 vibrancy() 就是
     * colorControls(0.0f, 1.0f, 1.5f)，即亮度不变、对比度不变、
     * 饱和度乘 1.5。
     *
     * 【为什么这一步不能省 —— 这是之前画面"发脏发灰"的根因】
     *
     * 背景经过柔和的光斑/渐变叠加后，本身饱和度就低（灰扑扑一片）。
     * 玻璃层再叠一层半透明白tint，最终每个像素的 RGB 差距被进一步压缩，
     * 视觉上就是"一片均匀的灰"。先做 vibrancy（拉高饱和度 1.5 倍），
     * 玻璃下面的画面才会重新"活"起来，折射出来的颜色变化也才有对比度
     * 可看 —— 没有色彩对比，折射在视觉上就等于不存在。
     */
    static Object newColorControls(float brightness, float contrast, float saturation) {
        if (!supportsEffect()) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createColorFilterEffect",
                    Class.forName("android.graphics.ColorFilter"));
            Class<?> cf = Class.forName("android.graphics.ColorMatrixColorFilter");
            Constructor<?> ctor = cf.getConstructor(float[].class);

            /*
             * 逐行翻译自反编译 BackdropEffectScope.colorControlsColorFilter：
             *
             *   f4 = 1 - saturation
             *   f5 = 0.213f * f4     ← 红色通道的灰度权重
             *   f6 = 0.715f * f4     ← 绿色
             *   f7 = 0.072f * f4     ← 蓝色
             *   f8 = ((0.5 - contrast*0.5) + brightness) * 255   ← 平移量
             *   f9 = contrast * f5
             *   f10 = contrast * f6
             *   f11 = contrast * f7
             *   f12 = contrast * saturation
             *
             * 矩阵（行主序，每行 5 个）：
             *   行1: f9+f12, f10,    f11,    0, f8
             *   行2: f9,     f10+f12, f11,   0, f8
             *   行3: f9,     f10,    f11+f12, 0, f8
             *   行4: 0,      0,      0,      1, 0
             *
             * 【为什么不用 ColorMatrix.setSaturation + postConcat】
             * setSaturation 内部用的是 Android 自己的一套近似权重，和这个
             * 库手写的 0.213/0.715/0.072 组合顺序不同。在 saturation=1.5
             * 这种轻度增强下差别不明显，但原库连 brightness 和 contrast 都
             * 编进同一个矩阵，一旦要调参就必须是这套公式，否则颜色会漂。
             * 这里直接照抄，保证和参考实现像素级一致。
             */
            float f4 = 1f - saturation;
            float f5 = 0.213f * f4;
            float f6 = 0.715f * f4;
            float f7 = 0.072f * f4;
            float f8 = ((0.5f - (contrast * 0.5f)) + brightness) * 255f;
            float f9 = contrast * f5;
            float f10 = contrast * f6;
            float f11 = contrast * f7;
            float f12 = contrast * saturation;

            float[] mat = {
                    f9 + f12, f10, f11, 0f, f8,
                    f9, f10 + f12, f11, 0f, f8,
                    f9, f10, f11 + f12, 0f, f8,
                    0f, 0f, 0f, 1f, 0f
            };
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(mat);
            Object filter = ctor.newInstance((Object) cm.getArray());
            return m.invoke(null, filter);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 饱和度增强（原库 vibrancy 的等价实现） */
    static Object newVibrancy() {
        return newColorControls(0f, 1f, 1.5f);
    }

    /*
     * 把 RenderEffect 链成「先 a 后 b」。
     *
     * 用于叠加：先模糊 + 再折射，或者折射 + 高光。
     */
    static Object chain(Object inner, Object outer) {
        if (inner == null) {
            return outer;
        }
        if (outer == null) {
            return inner;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createChainEffect",
                    re, re);
            return m.invoke(null, outer, inner);
        } catch (Throwable e) {
            return outer;
        }
    }

    /**
     * 创建模糊效果。
     *
     * @param radiusX 横向模糊半径（像素）
     * @param radiusY 纵向模糊半径
     */
    static Object newBlur(float radiusX, float radiusY) {
        if (!supportsEffect()) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createBlurEffect",
                    float.class, float.class, Class.forName("android.graphics.Shader$TileMode"));
            Class<?> tileMode = Class.forName("android.graphics.Shader$TileMode");
            Object clamp = tileMode.getField("CLAMP").get(null);
            return m.invoke(null, radiusX, radiusY, clamp);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 把 RenderEffect 应用到 View。
     *
     * setRenderEffect 是 API 31 的 View 方法，低版本没有。
     */
    static void applyToView(android.view.View view, Object effect) {
        if (view == null || !supportsEffect()) {
            return;
        }
        try {
            Method m = android.view.View.class.getMethod("setRenderEffect",
                    Class.forName("android.graphics.RenderEffect"));
            m.invoke(view, effect);
        } catch (Throwable e) {
            // 忽略：低版本没有这个方法，调用方已经按 tier 做了降级
        }
    }

    /**
     * 创建 RenderNode（API 29+）。
     *
     * 为什么用 RenderNode 而不是 Bitmap：
     *   Bitmap 方案要经过「离屏绘制 → 显存回读 → 再上传」两趟拷贝，
     *   每帧都做会直接掉到个位数帧率。RenderNode 是 GPU 侧的绘制记录节点，
     *   RenderEffect 可以直接挂在它上面，全程不离开显存。
     *
     * @return RenderNode；API 不够返回 null（调用方走降级）
     */
    static Object newRenderNode(String name) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            return new android.graphics.RenderNode(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 RenderEffect 包装成可挂到 RenderNode 上的形态。
     *
     * createRuntimeShaderEffect 产出的已经是合法 RenderEffect，
     * 这里只做空值保护 —— 保留这层是为了将来接入「折射 + 模糊」链式效果时
     * 有统一的挂载点（RenderEffect.createChainEffect 可以串多个）。
     */
    static Object newRenderNodeEffect(Object effect) {
        if (effect == null) {
            return null;
        }
        return effect;
    }

    /**
     * 给 RenderNode 设置 RenderEffect（API 33+）。
     *
     * RenderNode.setRenderEffect 要求 API 33；低版本没有这个方法，
     * 直接跳过，onDraw 会走模糊/纯色降级。
     */
    static void setRenderEffectOnNode(Object node, Object effect) {
        if (node == null || effect == null || !supportsRefraction()) {
            return;
        }
        try {
            android.graphics.RenderNode rn = (android.graphics.RenderNode) node;
            rn.setRenderEffect((android.graphics.RenderEffect) effect);
        } catch (Throwable ignored) {
            // 节点或效果类型不符，跳过
        }
    }
}