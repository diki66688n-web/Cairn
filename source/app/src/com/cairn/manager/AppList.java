package com.cairn.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已安装应用列表。
 *
 * 为什么不用 PackageManager.getInstalledPackages(GET_PERMISSIONS)：
 *   在 Android 11+ 上那会触发包可见性限制（需要 <queries>），
 *   拿到的列表是空的。这里用 getInstalledApplications 并显式声明
 *   QUERY_ALL_PACKAGES 权限（已在 Manifest 里加了）。
 *
 * 图标用 ApplicationInfo.loadIcon()，由系统渲染成 Drawable，
 * 不自己解码 APK，避免不同 density 下取错资源。
 */
public final class AppList {

    public static final class App {
        public final String label;
        public final String pkg;
        public final int uid;
        public final Drawable icon;

        public App(String label, String pkg, int uid, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.uid = uid;
            this.icon = icon;
        }
    }

    private final Context ctx;
    private final PackageManager pm;

    public AppList(Context ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
    }

    /** 是否系统应用（有 FLAG_SYSTEM 或 FLAG_UPDATED_SYSTEM_APP 都算） */
    public static int kindOf(ApplicationInfo ai) {
        boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        return sys ? 1 : 0;
    }

    /**
     * @param includeSystem 是否包含系统应用
     * @param needIcon      是否加载图标（加载图标较慢，滚动时可关掉）
     */
    public List<App> load(boolean includeSystem, boolean needIcon) {
        List<App> out = new ArrayList<>();
        List<ApplicationInfo> list;
        try {
            list = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return out;
        }
        if (list == null) return out;

        for (ApplicationInfo ai : list) {
            if (ai == null) continue;
            boolean sys = (kindOf(ai) == 1);
            // 系统应用里绝大多数是框架组件，没有启动图标，价值不大；
            // 但用户可能确实想给某个系统应用授权，所以只排除掉没有图标的那批。
            if (sys && !includeSystem) continue;
            String label;
            try {
                CharSequence cs = pm.getApplicationLabel(ai);
                label = cs == null ? ai.packageName : cs.toString();
            } catch (Throwable t) {
                label = ai.packageName;
            }
            Drawable ic = null;
            if (needIcon) {
                try { ic = pm.getApplicationIcon(ai); } catch (Throwable t) { /* ignore */ }
            }
            out.add(new App(label, ai.packageName, ai.uid, ic));
        }

        Collections.sort(out, (a, b) -> {
            // 有中文名的排前面更符合直觉，但简单点：按名字不区分大小写排
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    /** 本 APP 自己的包名，列表里要排除（不能给自己授权） */
    public String selfPackage() { return ctx.getPackageName(); }

    /** 本 APP 的 uid */
    public int selfUid() {
        try {
            return pm.getApplicationInfo(selfPackage(), 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按包名查 uid，找不到返回 -1 */
    public int uidOf(String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}