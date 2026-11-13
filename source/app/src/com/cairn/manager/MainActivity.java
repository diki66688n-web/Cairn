package com.cairn.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cairn.manager.glass.GlassBackdrop;
import com.cairn.manager.glass.GlassCompat;
import com.cairn.manager.glass.GlassComponents;
import com.cairn.manager.glass.GlassCard;
import com.cairn.manager.glass.LiquidNavBar;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Pages are built programmatically; the APK also packages Android theme/resource data through aapt2. */
public class MainActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Language.wrap(base));}

    private String VERSION = "?";   // onCreate 里从 manifest 读取，杜绝写死

    private static final int C_BG     = 0xFF101319;
    private static final int C_CARD   = 0xFF1A1F29;
    private static final int C_CARD2  = 0xFF222835;
    private static final int C_ACCENT = 0xFF7FD1FF;
    private static final int C_TEXT   = 0xFFE6EDF3;
    private static final int C_DIM    = 0xFF8B95A5;
    private static final int C_OK     = 0xFF4ADE80;
    private static final int C_WARN   = 0xFFFBBF24;
    private static final int C_ERR    = 0xFFF87171;
    private static final int C_INFO   = 0xFF60A5FA;

    /** 工作状态 */
    private static final int ST_UNKNOWN = 0;
    private static final int ST_WORKING = 1;
    private static final int ST_NO_ROOT = 2;
    private static final int ST_NO_KERNEL = 3;

    private FileLog flog;
    private KeyStoreBox box;
    private CairnCore core;
    private AppList appList;

    private int state = ST_UNKNOWN;
    private long k0, k1;
    private boolean keyLoaded;

    // 主页
    private TextView tvBigState, tvSubState, tvDetail;
    private EditText keyInput;
    private View dot;

    // 授权页
    private FrameLayout content;
    private View pageHome, pageApps, pageLog, pageSettings;
    private ListView lvApps;
    private EditText etSearch;
    private AppAdapter adapter;
    private final List<AppList.App> allApps = new ArrayList<>();
    private final List<AppList.App> shownApps = new ArrayList<>();
    private final Map<Integer, Integer> granted = new HashMap<>();  // uid -> policy
    private boolean appsLoaded;

    /**
     * ensureDaemon 的互斥锁。
     * refreshStatusAsync 会被多处触发（onCreate / onResume / 保存密钥后），
     * 不串行化会导致两段 deploy 交错、rm 删掉刚 cp 的文件。见 ensureDaemon 注释。
     */
    private final Object daemonLock = new Object();

    private int curTab = 0;

    private static String vis(View v) {
        if (v == null) return "null";
        return v.getVisibility() == View.VISIBLE ? "V" : (v.getVisibility() == View.GONE ? "G" : "I");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        flog = new FileLog(this);
        // 版本号从 manifest 读，不要写死字符串。
        // 之前这里是 private static final String VERSION = "0.4"，
        // 从 0.5 到 0.7 一直没跟着改，日志里永远显示 0.4，
        // 排查时无法判断用户到底装的是哪一版（已经被这件事误导过一次）。
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION = pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable t) {
            VERSION = "?";
        }
        box = new KeyStoreBox(this);
        core = new CairnCore(this);
        appList = new AppList(this);
        flog.header(this, VERSION);
setContentView(buildUi());

        /*
         * 【全局崩溃捕获 —— 为什么必须加】
         *
         * 用户反馈"点击就闪退"，但日志里只看到最后一条业务日志就断了，
         * 没有任何异常信息。原因是 Android 的未捕获异常默认只打到
         * logcat，而 APP 自己的 FileLog 完全不知道发生过崩溃，
         * 于是磁盘上留下一份"看起来正常结束"的日志，无法定位。
         *
         * 这里接管默认的 UncaughtExceptionHandler：
         *   1) 把完整堆栈写进同一份日志文件（带 [CRASH] 前缀）
         *   2) 再把异常交回系统默认处理，保证系统行为不变
         *      （弹"应用已停止"、被 ActivityManager 记录）
         *
         * 必须在 setContentView 之前装吗？不必 —— 只要在可能抛异常的
         * 代码之前即可。这里放在 buildUi 之后、启动检查之前，
         * 覆盖 refreshStatusAsync 等后台线程的崩溃点。
         *
         * 注意：后台线程抛的异常同样会走到这里，因为
         * Thread.setDefaultUncaughtExceptionHandler 是全局的。
         */
        installCrashHandler();

        try {
            flog.log("NATIVE", "libcairn.so loaded, getuid()=" + Native.getuid());
        } catch (Throwable t) {
            flog.log("NATIVE", "load FAILED: " + t);
        }

        checkStoragePermission();
        loadKey();
        refreshStatusAsync(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (flog != null) refreshStatusAsync(false);
    }

    /**
     * 装全局崩溃捕获。
     *
     * 【为什么需要 —— 这次"闪退"排查的直接教训】
     *
     * 用户点开就退出，但 cairn_logs 里的日志最后一条是 [PROBE] probe()=0，
     * 之后就断了，一句异常都没有。原因是：
     *   - APP 自己的 FileLog 只记录主动调用 log() 的内容
     *   - 未捕获异常默认只走 Android 的 UncaughtExceptionHandler → logcat
     *   - 而 proot 环境读不到 logcat，用户也不会去抓
     *
     * 所以磁盘上留下的是一份"看起来正常结束"的日志，等于没有证据。
     * 这个 handler 把真实堆栈写进同一份日志，下次崩溃就能直接看到
     * 是哪个方法第几行、什么异常。
     *
     * 实现要点：
     *   1) 保存旧的 handler，处理完后调用它，保证系统仍能正常
     *      杀掉进程并显示"应用已停止"，行为与原来一致
     *   2) 写日志本身包在 try/catch 里 —— 崩溃处理代码自己再崩
     *      就彻底没救了，必须吞掉一切异常
     *   3) 打印完整 cause 链，Android 很多异常都是被包了一层
     *      （RuntimeException caused by NPE），只看最外层没意义
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("thread=").append(thread == null ? "?" : thread.getName())
                        .append("  exception=").append(ex.getClass().getName())
                        .append(": ").append(ex.getMessage());
                flog.log("CRASH", sb.toString());

                // 完整堆栈
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                for (String ln : sw.toString().split("\n")) {
                    if (!ln.trim().isEmpty()) {
                        flog.log("CRASH", ln.trim());
                    }
                }

                // cause 链（很多异常是被包过的，只看最外层会误判）
                Throwable c = ex.getCause();
                int depth = 0;
                while (c != null && depth < 5) {
                    flog.log("CRASH", "caused by: " + c.getClass().getName()
                            + ": " + c.getMessage());
                    c = c.getCause();
                    depth++;
                }
                flog.log("CRASH", "=== 进程即将退出 ===");
            } catch (Throwable ignored) {
                // 崩溃处理器自身绝不能抛异常
            }

            // 交回系统默认处理（保证"应用已停止"等行为不变）
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    // ================= UI 构建 =================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * 构建整个界面。
     *
     * 结构（从下往上）：
     *   GlassBackdrop   ← 彩色光斑背景，玻璃折射的"素材"
     *     FrameLayout   ← 页面容器（home / apps 叠放，靠 visibility 切换）
     *     GlassNavBar   ← 悬浮玻璃导航栏，浮在内容之上
     *
     * 为什么背景必须是 GlassBackdrop 而不是纯色：
     *   折射的原理是"采样背景坐标并偏移"。如果背景是纯色，
     *   偏移后采样到的颜色完全一样，视觉上等于没效果。
     *   必须有明暗和色彩变化，边缘弯折才看得出来。
     */
    private ModulePage modulePage;
    private View pageModules;
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==710 && result==RESULT_OK && data!=null && data.getData()!=null)modulePage.install(data.getData());
    }
    private View buildUi() {
        // 根容器：背景层 + 内容层
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        // 1) 光斑背景（铺满，作为折射源）
        GlassBackdrop backdrop = new GlassBackdrop(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(backdrop);

        // 2) 页面容器
        content = new FrameLayout(this);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content);

        modulePage=new ModulePage(this,core,()->new long[]{k0,k1});
        pageModules=modulePage.root;
        content.addView(pageModules);pageModules.setVisibility(View.GONE);
        pageHome = buildHomePage();
        pageApps = buildAppsPage();
        pageLog = buildLogPage();
        pageSettings = buildSettingsPage();
        content.addView(pageHome);
        content.addView(pageApps);
        content.addView(pageLog);
        content.addView(pageSettings);
        pageApps.setVisibility(View.GONE);
        pageLog.setVisibility(View.GONE);
        pageSettings.setVisibility(View.GONE);

        // 3) 液态玻璃底部导航栏（正胶囊、图标在上文字在下、选中蓝底圆）
        navBar = new LiquidNavBar(this);
        // 当前页面的标签文字（官方组件用文字标签，不用图标）
        navBar.setLabels(new String[]{Language.text(this,"Overview","概览"), Language.text(this,"Apps","应用"), Language.text(this,"Logs","日志"), Language.text(this,"Settings","设置"), Language.text(this,"Modules","模块")});
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        nlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // 参考图：导航栏宽度约 80% 屏宽、底部留 30dp，浮在内容之上
        nlp.leftMargin = dp(28);
        nlp.rightMargin = dp(28);
        nlp.bottomMargin = dp(28);
        navBar.setLayoutParams(nlp);
        root.addView(navBar);
        /*
         * 【不调用 bindBackdrop(navBar)】
         *
         * bindBackdrop 的参数是 GlassPanel —— 它的机制是"把背景画进 RenderNode
         * 再套 AGSL 折射"。LiquidNavBar 是自绘 View，走的是自己那套
         * clipPath + 线性渐变高光 + 半透明填充，不消费 RenderNode 背景。
         * 对它调 bindBackdrop 在编译期就过不了（类型不匹配）。
         *
         * 导航栏的"玻璃感"来源不同：它半透明（12% 白），下面透出的是
         * 根背景本身，加上顶部一道高光边 —— 在近黑背景上这已经足够，
         * 而且省掉一次全屏 RenderNode 采样的开销。
         */
        navBar.setOnTabSelectedListener(new LiquidNavBar.OnTabSelectedListener() {
            @Override
            public void onTabSelected(int idx) {
                flog.log("UI", "onTabSelected idx=" + idx
                        + " pages=" + (pageHome!=null) + "/" + (pageApps!=null)
                        + "/" + (pageLog!=null) + "/" + (pageSettings!=null));
                switchTab(idx);
            }
        });
        // 首帧不播放弹簧动画，避免一打开就抖一下
        navBar.setSelectedIndex(0);

        // 4) 玻璃背景层：所有 GlassPanel 都从这里取折射素材
        glassRoot = backdrop;

        return root;
    }

    private GlassBackdrop glassRoot;
    private LiquidNavBar navBar;

    // ---------- 概览页 ----------

    /**
     * 概览页。
     *
     * 设计原则：一屏之内必须能回答三个问题——
     *   1) 现在能不能用？（状态灯 + 大字）
     *   2) 为什么不能？（副标题 + 详情）
     *   3) 我该做什么？（密钥输入 + 按钮）
     * 所以顺序是 状态卡 → 密钥卡 → 部署信息，日志挪到独立的日志页。
     *
     * 注意：所有玻璃面板都要用 setBackgroundSource 指到根背景，
     * 否则折射采到的是一片黑，玻璃就退化成半透明色块了。
     */
    private View buildHomePage() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        // 让内容能滚到悬浮导航栏下面，最后一块卡片不至于被挡住
        sc.setClipToPadding(false);
        sc.setPadding(0, 0, 0, dp(96));

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(28), dp(20), dp(20));
        sc.addView(p);

        // ---- 标题区 ----
        TextView title = new TextView(this);
        title.setText("Cairn");
        title.setTextColor(C_TEXT);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        p.addView(title);

        TextView sub = new TextView(this);
        sub.setText("内核级权限管理  ·  v" + VERSION);
        sub.setTextColor(C_DIM);
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(22));
        p.addView(sub);

        // ---- 状态玻璃卡 ----
        GlassCard statusPanel = GlassComponents.card(this, 24f, 0, true);
        LinearLayout sc2 = new LinearLayout(this);
        sc2.setOrientation(LinearLayout.VERTICAL);
        sc2.setPadding(dp(20), dp(20), dp(20), dp(20));
        statusPanel.addView(sc2);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(11), dp(11));
        dlp.rightMargin = dp(12);
        dot.setLayoutParams(dlp);
        dot.setBackground(GlassComponents.GlowDot.create(C_DIM, dp(11)));
        head.addView(dot);

        tvBigState = new TextView(this);
        tvBigState.setText(Language.text(this,"Checking…","检测中…"));
        tvBigState.setTextColor(C_DIM);
        tvBigState.setTextSize(22);
        tvBigState.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(tvBigState);

        sc2.addView(head);

        tvSubState = new TextView(this);
        tvSubState.setText("");
        tvSubState.setTextColor(C_DIM);
        tvSubState.setTextSize(13);
        tvSubState.setLineSpacing(dp(3), 1f);
        tvSubState.setPadding(0, dp(12), 0, 0);
        sc2.addView(tvSubState);

        tvDetail = new TextView(this);
        tvDetail.setText("");
        tvDetail.setTextColor(0xFF7D8A9C);
        tvDetail.setTextSize(10.5f);
        tvDetail.setTypeface(Typeface.MONOSPACE);
        tvDetail.setPadding(0, dp(14), 0, 0);
        tvDetail.setLineSpacing(dp(3), 1f);
        sc2.addView(tvDetail);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(22);
        p.addView(statusPanel, slp);
        bindBackdrop(statusPanel);

        // ---- 密钥区标题 ----
        TextView kl = new TextView(this);
        kl.setText("SUPERKEY");
        kl.setTextColor(0xFF7D8A9C);
        kl.setTextSize(10.5f);
        kl.setLetterSpacing(0.14f);
        kl.setTypeface(Typeface.DEFAULT_BOLD);
        kl.setPadding(dp(6), 0, 0, dp(8));
        p.addView(kl);

        // ---- 密钥输入（玻璃底） ----
        GlassCard keyPanel = GlassComponents.card(this, 18f, 0, false);
        keyInput = new EditText(this);
        keyInput.setHint("32 位十六进制密钥");
        keyInput.setHintTextColor(0xFF556070);
        keyInput.setTextColor(C_TEXT);
        keyInput.setTextSize(13);
        keyInput.setTypeface(Typeface.MONOSPACE);
        /*
         * 不能用 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD：
         * 带 password 变体的字段会被输入法识别成密码框 ——
         * 弹出安全键盘（无法切换/无剪贴板），且长按不出现"粘贴"。
         * 这里只是密钥，不是用户口令，不需要防截屏那套。
         */
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        keyInput.setSingleLine(true);
        keyInput.setLongClickable(true);
        keyInput.setTextIsSelectable(true);
        keyInput.setBackground(null);
        keyInput.setPadding(dp(18), dp(16), dp(18), dp(16));
        keyPanel.addView(keyInput, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        p.addView(keyPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindBackdrop(keyPanel);

        // ---- 按钮行 ----
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(14);
        p.addView(btns, btnsLp);

        GlassCard btnSave = GlassComponents.button(this, "保存并提权", 18f,
                0xCC3B82F6, 0xFFFFFFFF);
        /*
         * 【为什么不用 weight】
         * GlassPanel 的父类是 FrameLayout，LinearLayout.LayoutParams 里的
         * weight 会被 FrameLayout 的 checkLayoutParams 判为「外来参数」
         * 并降级重造，weight 丢失 → 按钮宽度错乱、互相重叠。
         * 改成 0dp + weight 之外的确定方案：外层横排容器里用 WRAP_CONTENT，
         * 由 LinearLayout 按 weight 均分，但按钮自己不让 GlassPanel 参与分宽度。
         */
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(52), 1.35f);
        blp.rightMargin = dp(10);
        btnSave.setOnClickListener(v -> {
            flog.log("BTN", "btnSave clicked");
            doSaveAndEscalate();
        });
        btns.addView(btnSave, blp);
        bindBackdrop(btnSave);

        GlassCard btnClear = GlassComponents.button(this, Language.text(this,"Clear","清除"), 18f,
                0x1AFFFFFF, 0xFFB6C0CE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        btnClear.setOnClickListener(v -> {
            box.clear();
            keyInput.setText("");
            k0 = k1 = 0;
            keyLoaded = false;
            flog.log("KEY", "cleared by user");
            toast("密钥已清除");
            refreshStatusAsync(false);
        });
        btns.addView(btnClear, clp);
        bindBackdrop(btnClear);

        // ---- 运行信息（小卡片组） ----
        TextView il = new TextView(this);
        il.setText("运行环境");
        il.setTextColor(0xFF7D8A9C);
        il.setTextSize(10.5f);
        il.setLetterSpacing(0.14f);
        il.setTypeface(Typeface.DEFAULT_BOLD);
        il.setPadding(dp(6), dp(28), 0, dp(8));
        p.addView(il);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        p.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GlassCard c1 = GlassComponents.statusCard(this, "守护进程", "cnd", C_INFO, 18f);
        GlassCard c2 = GlassComponents.statusCard(this, "玻璃渲染",
                GlassCompat.tier() >= 3 ? "AGSL" : (GlassCompat.tier() == 2 ? "模糊" : "纯色"),
                GlassCompat.tier() >= 3 ? C_OK : C_WARN, 18f);

        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        h1.rightMargin = dp(10);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        info.addView(c1, h1);
        info.addView(c2, h2);
        bindBackdrop(c1);
        bindBackdrop(c2);

        return sc;
    }

    // ---------- 授权页 ----------

    private View buildAppsPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        /*
         * 根容器必须显式 MATCH_PARENT 高度。
         * 下面是 ListView，用 height=0 + weight=1 占满剩余空间；
         * 如果 p 自己高度是 WRAP_CONTENT，weight 就没有「剩余空间」可分，
         * ListView 会被量成 0 高 —— 页面只剩一个标题，下面全空。
         */
        p.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 顶部内边距留出状态栏位置；底部给悬浮导航栏让位
        p.setPadding(0, dp(28), 0, dp(96));

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(20), 0, dp(20), dp(10));
        p.addView(bar);

        TextView t = new TextView(this);
        t.setText("应用授权");
        t.setTextColor(C_TEXT);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(t);

        TextView tip = new TextView(this);
        tip.setText("勾选后，该应用执行 su 时仍需在管理器里确认");
        tip.setTextColor(C_DIM);
        tip.setTextSize(11);
        tip.setPadding(0, dp(4), 0, dp(14));
        bar.addView(tip);

        // 搜索框（玻璃底）
        GlassCard searchPanel = GlassComponents.card(this, 16f, 0, false);
        etSearch = new EditText(this);
        etSearch.setHint("搜索应用名或包名");
        etSearch.setHintTextColor(0xFF556070);
        etSearch.setTextColor(C_TEXT);
        etSearch.setTextSize(13);
        etSearch.setSingleLine(true);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setBackground(null);
        etSearch.setPadding(dp(18), dp(13), dp(18), dp(13));
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { applyFilter(); }
        });
        searchPanel.addView(etSearch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(searchPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindBackdrop(searchPanel);

        // 列表：必须是透明背景，否则会把光斑背景挡住、玻璃就没东西可折射
        lvApps = new ListView(this);
        lvApps.setDivider(null);
        lvApps.setDividerHeight(0);
        lvApps.setCacheColorHint(Color.TRANSPARENT);
        lvApps.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        lvApps.setBackgroundColor(Color.TRANSPARENT);
        lvApps.setPadding(dp(16), dp(6), dp(16), dp(10));
        lvApps.setClipToPadding(false);
        adapter = new AppAdapter();
        lvApps.setAdapter(adapter);
        p.addView(lvApps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return p;
    }

    // ---------- 日志页 ----------

    /**
     * 日志页。
     *
     * 日志原来挤在主页最底下，主页一长就看不到，排查时还得滚动。
     * 独立成页之后可以直接给整屏高度，同时保留「清空」入口。
     */
    private View buildLogPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        // 同上：根容器要 MATCH_PARENT，日志区才能用 weight 占满剩余高度
        p.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        p.setPadding(dp(20), dp(28), dp(20), dp(96));

        TextView t = new TextView(this);
        t.setText("运行日志");
        t.setTextColor(C_TEXT);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(t);

        TextView tip = new TextView(this);
        tip.setText("实时刷新  ·  自动滚动到底部");
        tip.setTextColor(C_DIM);
        tip.setTextSize(11);
        tip.setPadding(0, dp(4), 0, dp(10));
        p.addView(tip);

        // cnd 自己的运行日志：cnd 记了启动来源、连接方 uid/pid/cmdline、
        // 收到的命令、执行结果，都写进 /data/adb/cairn/daemon.log。
        // 只有 root 能读，所以点击时现场提权拉一次，不常驻。
        GlassCard btnCnd = GlassComponents.button(this, "查看 cnd 日志", 14f,
                0x1AFFFFFF, 0xFFB6C0CE);
        LinearLayout.LayoutParams cndLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        cndLp.bottomMargin = dp(10);
        btnCnd.setOnClickListener(v -> showCndLog());
        p.addView(btnCnd, cndLp);
        bindBackdrop(btnCnd);

        // 日志玻璃卡
        GlassCard logPanel = GlassComponents.card(this, 18f, 0, false);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(true);

        TextView tvLog = new TextView(this);
        tvLog.setTextColor(0xFFB6C0CE);
        tvLog.setTextSize(10.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(dp(16), dp(16), dp(16), dp(16));
        tvLog.setLineSpacing(dp(3), 1f);
        tvLog.setTextIsSelectable(true);
        tvLog.setText(flog.dump());
        logScroll.addView(tvLog);
        logPanel.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(logPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindBackdrop(logPanel);

        // 这两行是「日志刷新」用的，postLog() 会把内容写进 tvLog
        root_postLog = tvLog;
        logScroller = logScroll;

        // 底部操作行
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams opsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        opsLp.topMargin = dp(14);
        p.addView(ops, opsLp);

        GlassCard btnCopy = GlassComponents.button(this, "复制全部", 16f,
                0x1AFFFFFF, 0xFFB6C0CE);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        btnCopy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "cairn-log", flog.dump()));
                toast("日志已复制");
            } catch (Throwable e) {
                toast("复制失败: " + e);
            }
        });
        ops.addView(btnCopy, copyLp);
        bindBackdrop(btnCopy);

        return p;
    }

    /**
     * 弹出 cnd 日志。
     *
     * cnd 的日志只有 root 可读，所以走提权通道现场 tail 一次。
     * 不放进实时刷新，因为每次都要提权，代价高；
     * 用户排查时手动点，拿到的是当前快照。
     */
    private void showCndLog() {
        if (k0 == 0 && k1 == 0) {
            toast("还没有密钥，先完成初始化");
            return;
        }
        toast("正在读取 cnd 日志...");
        new Thread(() -> {
            CairnCore.Res r = null;
            String text;
            try {
                r = core.cndLog(k0, k1, 500);
                text = r.text();
            } catch (Throwable e) {
                text = "读取失败: " + e;
            }
            final String body = text;
            final CairnCore.Res rr = r;
            runOnUiThread(() -> {
                flog.log("CND", "读取 cnd 日志 rc=" + (rr == null ? -1 : rr.rc));
                AlertDialog.Builder b = new AlertDialog.Builder(this);
                b.setTitle("cnd 运行日志");
                ScrollView sv = new ScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(body);
                tv.setTextSize(9.5f);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(0xFFB6C0CE);
                tv.setTextIsSelectable(true);
                int pad = dp(12);
                tv.setPadding(pad, pad, pad, pad);
                sv.addView(tv);
                b.setView(sv);
                b.setPositiveButton(Language.text(this,"Close","关闭"), null);
                b.setNeutralButton(Language.text(this,"Copy","复制"), (d, w) -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "cnd-log", body));
                        toast("cnd 日志已复制");
                    } catch (Throwable e) {
                        toast("复制失败: " + e);
                    }
                });
                b.show();
            });
        }).start();
    }

    private ScrollView logScroller;

    /** 日志 TextView（日志页里的那个），postLog() 往它写 */
    private TextView root_postLog;

    /**
     * 刷新日志显示。
     *
     * 两件事：
     *   1) 内容变了才写（TextView.setText 会触发重新排版，
     *      每秒无条件写一次在长日志下是明显卡顿源）
     *   2) 写完后滚到底部 —— 否则新日志加在下面但视口停在顶上，
     *      看起来像"日志不刷新"
     */
    private void postLog() {
        if (root_postLog == null) {
            return;
        }
        String t = flog.dump();
        if (!t.contentEquals(root_postLog.getText())) {
            root_postLog.setText(t);
        }
        if (logScroller != null) {
            final int bottom = root_postLog.getHeight();
            logScroller.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        logScroller.scrollTo(0, bottom);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    // ---------- 设置页 ----------

    /**
     * 设置页。
     *
     * 这里放的是「不需要每次看、但需要时能找到」的东西：
     * 版本、内核状态、部署路径、以及重新检测/打开系统的入口。
     */
    private View buildSettingsPage() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        sc.setPadding(0, 0, 0, dp(96));

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(28), dp(20), dp(20));
        sc.addView(p);

        TextView t = new TextView(this);
        t.setText(Language.text(this,"Settings","设置"));
        t.setTextColor(C_TEXT);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(t);

        TextView tip = new TextView(this);
        tip.setText(Language.text(this,"Cairn Manager","Cairn 管理器"));
        tip.setTextColor(C_DIM);
        tip.setTextSize(11);
        tip.setPadding(0, dp(4), 0, dp(18));
        p.addView(tip);

        GlassCard languageButton=GlassComponents.button(this,"Language / 语言",18f,0x14ffffff,C_TEXT);
        languageButton.setOnClickListener(v->Language.choose(this));
        LinearLayout.LayoutParams languageLp=new LinearLayout.LayoutParams(-1,dp(52));languageLp.bottomMargin=dp(18);p.addView(languageButton,languageLp);
        // ---- 信息卡 ----
        TextView il = new TextView(this);
        il.setText(Language.text(this,"System information","系统信息"));
        il.setTextColor(0xFF7D8A9C);
        il.setTextSize(10.5f);
        il.setLetterSpacing(0.14f);
        il.setTypeface(Typeface.DEFAULT_BOLD);
        il.setPadding(dp(6), 0, 0, dp(8));
        p.addView(il);

        GlassCard infoCard = GlassComponents.card(this, 20f, 0, false);
        tvSettingsInfo = new TextView(this);
        tvSettingsInfo.setTextColor(0xFFB6C0CE);
        tvSettingsInfo.setTextSize(11.5f);
        tvSettingsInfo.setTypeface(Typeface.MONOSPACE);
        tvSettingsInfo.setPadding(dp(20), dp(18), dp(20), dp(18));
        tvSettingsInfo.setLineSpacing(dp(4), 1f);
        tvSettingsInfo.setText(buildSettingsInfo());
        infoCard.addView(tvSettingsInfo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iclp.bottomMargin = dp(20);
        p.addView(infoCard, iclp);
        bindBackdrop(infoCard);

        // ---- 操作 ----
        TextView ol = new TextView(this);
        ol.setText("操作");
        ol.setTextColor(0xFF7D8A9C);
        ol.setTextSize(10.5f);
        ol.setLetterSpacing(0.14f);
        ol.setTypeface(Typeface.DEFAULT_BOLD);
        ol.setPadding(dp(6), 0, 0, dp(8));
        p.addView(ol);

        GlassCard btnRecheck = GlassComponents.button(this, "重新检测状态", 18f,
                0x1AFFFFFF, 0xFFF2F5FA);
        btnRecheck.setOnClickListener(v -> {
            toast("正在重新检测…");
            refreshStatusAsync(true);
            refreshSettingsInfo();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        rlp.bottomMargin = dp(10);
        p.addView(btnRecheck, rlp);
        bindBackdrop(btnRecheck);

        GlassCard btnLogClear = GlassComponents.button(this, Language.text(this,"Clear logs","清空日志"), 18f,
                0x1AFFFFFF, 0xFFF87171);
        btnLogClear.setOnClickListener(v -> {
            /*
             * FileLog 没有 clear() 方法（它是追加写的文件日志）。
             * 这里只清界面上的显示，磁盘上的日志保留 ——
             * 排查问题时最怕的就是用户手一抖把证据清了，
             * 所以「清空日志」只做视觉清空，不动文件。
             */
            if (root_postLog != null) {
                root_postLog.setText("");
            }
            toast("显示已清空（日志文件保留）");
        });
        p.addView(btnLogClear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        bindBackdrop(btnLogClear);
        TextView foot = new TextView(this);
        foot.setText("\n内核入口 probe() / 守护进程 /data/adb/cnd\n"
                + "本应用不修改 SELinux 策略，不新增 context");
        foot.setTextColor(0xFF5C6779);
        foot.setTextSize(10);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(dp(6), dp(28), dp(6), 0);
        p.addView(foot);

        return sc;
    }

    private TextView tvSettingsInfo;

    private String buildSettingsInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("版本      ").append(VERSION).append('\n');
        sb.append("包名      ").append(getPackageName()).append('\n');
        sb.append("UID       ").append(appList.selfUid()).append('\n');
        sb.append("SDK       ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("ABI       ").append(Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append("玻璃渲染  ").append(GlassCompat.tier() >= 3 ? "AGSL 真折射"
                : (GlassCompat.tier() == 2 ? "模糊（API<33）" : "纯色（API<31）")).append('\n');
        sb.append("守护进程  /data/adb/cnd").append('\n');
        sb.append("密钥      ").append(keyLoaded ? "已载入（" + box.mode() + "）" : "未设置");
        return sb.toString();
    }

    private void refreshSettingsInfo() {
        if (tvSettingsInfo != null) {
            tvSettingsInfo.setText(buildSettingsInfo());
        }
    }

    // ---------- 页面切换 ----------

    /**
     * 切换 tab。
     *
     * 授权页有一个前置条件：管理器必须在工作状态（内核补丁在位 + 密钥正确），
     * 否则授权表读不了。以前是把用户弹回主页，体验很糟 ——
     * 现在改成留在授权页，列表区显示一条说明，用户能看到"为什么是空的"。
     */
    private void switchTab(int i) {
        curTab = i;

        View[] pages = {pageHome, pageApps, pageLog, pageSettings,pageModules};
        if(i==4)modulePage.refresh();
        for (int k = 0; k < pages.length; k++) {
            if (pages[k] != null) {
                pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            }
        }
        flog.log("UI", "switchTab(" + i + ") -> home=" + vis(pageHome)
                + " apps=" + vis(pageApps) + " log=" + vis(pageLog)
                + " set=" + vis(pageSettings));
        if (navBar != null && navBar.getSelectedIndex() != i) {
            // animate=true：由内容逻辑切换时也播放弹簧，指示圆滑动过去
            navBar.selectTab(i, true);
        }

        if (i == 1) {
            if (state == ST_WORKING) {
                if (!appsLoaded) loadAppsAsync();
                else refreshGrantsAsync();
            } else {
                // 不弹 toast、不跳转：让用户看到空列表旁边的状态提示即可
                if (state == ST_UNKNOWN) refreshStatusAsync(false);
            }
        } else if (i == 2) {
            postLog();
        } else if (i == 3) {
            refreshSettingsInfo();
        }
    }

    /**
     * 把玻璃面板接入折射管线。
     *
     * 【本轮重写】
     *
     * 旧实现在这里给每个面板设置一个 BackgroundSource，内容是
     * "用 GlassBackdrop.drawBackdrop 画一遍光斑"。问题是：
     *
     *   光斑是低频渐变，相邻像素几乎相同。折射 shader 即使把采样坐标
     *   偏移几十像素，取到的颜色也几乎不变 —— 数学上折射生效了，
     *   视觉上完全看不出来。这就是界面"不像液态玻璃"的根本原因。
     *
     * 现在改成：不设置 BackgroundSource，让 GlassPanel 走
     * GlassCapture 的窗口实时捕获路径，采样源变成真实的界面内容
     * （文字、卡片、色块、图标），折射才有东西可弯折。
     *
     * 为什么仍然保留这个方法：
     *   1) 统一所有面板的接入点，调用处不用改（十几处 bindBackdrop）
     *   2) 在捕获不可用（Android < 29 或无硬件加速）时，
     *      GlassPanel 会自动降级到 GlassBackdrop 光斑 + 模糊，
     *      这条降级链在 GlassPanel 内部完成，这里不需要重复处理
     *   3) 顺手把面板标记成"需要在内容变化时刷新折射"
     */
    private void bindBackdrop(GlassCard panel) {
        if (panel == null) {
            return;
        }
        /*
         * 显式清掉背景源。
         *
         * 如果调用方之前设过 BackgroundSource（比如别处代码遗留），
         * 这里清空可以保证走捕获路径，避免两套采样源打架。
         */
        panel.setBackgroundSource(null);

        /*
         * 打开实时捕获。
         *
         * 玻璃下面是会变的内容（页面切换、列表滚动、状态刷新、日志追加），
         * 折射画面必须跟着变。关掉实时捕获的话，玻璃里透出的
         * 永远是最初那一帧，看起来像贴了一张静态贴纸。
         */
    }


    // ---------- 小工具 ----------

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(dp(10), dp(10));
        return d;
    }

    private Button flatBtn(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(fg);
        b.setBackground(roundRect(bg, dp(10)));
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        return b;
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    // ================= 状态检测 =================

    private void loadKey() {
        String hex = box.load();
        if (hex == null) {
            keyLoaded = false;
            k0 = k1 = 0;
            return;
        }
        try {
            long[] kk = CairnCore.keyToLongs(hex);
            k0 = kk[0]; k1 = kk[1];
            keyLoaded = true;
            keyInput.setText(hex);
            flog.log("KEY", "loaded from " + box.mode());
        } catch (Throwable t) {
            keyLoaded = false;
            flog.log("KEY", "stored key unparsable: " + t);
        }
    }

    private void doSaveAndEscalate() {
        flog.log("KEY", "doSaveAndEscalate entered");
        String hex = keyInput.getText().toString().trim();
        flog.log("KEY", "input len=" + hex.length() + " value=[" + hex + "]");
        if (hex.isEmpty()) {
            flog.log("KEY", "EMPTY -> return");
            toast("请输入密钥");
            return;
        }
        try {
            long[] kk = CairnCore.keyToLongs(hex);
            k0 = kk[0]; k1 = kk[1];
        } catch (Throwable t) {
            toast("密钥格式错误，需要 32 位十六进制");
            return;
        }
        boolean ok = box.save(hex);
        flog.log("KEY", "Key loaded (value redacted)");
        keyLoaded = true;
        flog.log("KEY", "save=" + ok + " mode=" + box.mode()
                 + " boxLog=[" + box.logText().replace("\n", "|") + "]");
        flog.log("KEY", "calling refreshStatusAsync(true)");
        refreshStatusAsync(true);
    }

    /**
     * 每次进入都跑一遍：
     *   1) probe() 判断内核有没有入口
     *   2) 有入口就拿密钥去真的提一次权（子进程里，父进程 uid 不变）
     */
    private void refreshStatusAsync(final boolean verbose) {
        new Thread(() -> {
            int st;
            String detail = "";

            long pr = -99;
            try { pr = Native.probe(); } catch (Throwable t) { /* ignore */ }
            flog.log("PROBE", "probe()=" + pr);

            if (pr == CairnCore.KERNEL_STOCK) {
                st = ST_NO_KERNEL;
                detail = "probe=0  当前内核没有 Cairn 入口（未安装补丁镜像）";
            } else if (pr != CairnCore.KERNEL_PATCHED) {
                st = ST_NO_KERNEL;
                detail = "probe=" + pr + "  无法识别内核状态";
            } else {
                // 有补丁，试提权
                if (!keyLoaded) {
                    flog.log("AUTH", "skip selfTest: keyLoaded=false");
                    st = ST_NO_ROOT;
                    detail = "probe=1  补丁在位，但没有密钥";
                } else {
                    flog.log("AUTH", "selfTest start (key redacted)");
                    CairnCore.Res r = core.selfTest(k0, k1);
                    flog.log("AUTH", "selfTest rc=" + r.rc
                             + " out=[" + r.text().replace("\n", "|") + "]");
                    for (String ln : r.text().split("\n")) {
                        if (!ln.trim().isEmpty()) flog.log("OUT", ln);
                    }
                    // 关键诊断：纯内置指令（不 execve）测 /data/adb 可达性。
                    // 用来分离 DAC/SELinux 拒绝 与 execve 后 cred 被重置。
                    CairnCore.Res pa = core.probeAdb(k0, k1);
                    flog.log("ADBPROBE", "rc=" + pa.rc + " out=["
                            + pa.text().replace("\n", "|") + "]");
                    for (String ln : pa.text().split("\n")) {
                        if (!ln.trim().isEmpty()) flog.log("ADBPROBE", ln);
                    }
                    boolean working = (r.rc == 0) && r.text().contains("CAIRN_UID=0");
                    if (working) {
                        st = ST_WORKING;
                        // 顺手把 manager.uid 写成当前 uid（APP 更新后会变）
                        int myUid = appList.selfUid();
                        if (myUid > 0) {
                            CairnCore.Res s = core.initSelf(k0, k1, myUid);
                            flog.log("INIT", "manager.uid <- " + myUid + " rc=" + s.rc);
                        }
                        // 部署并拉起守护进程（幂等：已经在跑就跳过部署）
                        ensureDaemon();
                        CairnCore.Res ds = core.daemonStatus(k0, k1);
                        detail = "probe=1  提权成功" + (ds.text().isEmpty() ? ""
                                : "\n" + ds.text().trim().replace("\n", "  "));
                    } else {
                        st = ST_NO_ROOT;
                        detail = "probe=1  补丁在位，但密钥不对（子进程提权失败 rc="
                                + r.rc + "）";
                    }
                }
            }

            final int fst = st;
            final String fdet = detail;
            ui(() -> {
                state = fst;
                renderState(fst, fdet);
                postLog();
                if (fst == ST_WORKING && appsLoaded) refreshGrantsAsync();
            });
        }).start();
    }

    /* Prepare native executable paths, deploy cnd/shell, then synchronize saved grants. Current cnd is one-shot, not resident. */
    private void ensureDaemon() {
        /*
         * 串行化。
         *
         * 真机日志（V19, 0.8）实证：refreshStatusAsync 在启动流程里被触发了两次，
         * 于是 ensureDaemon 并发跑了两遍，两段 [DEPLOY] 交错在日志里。
         * 后一遍的 "rm -f .../cairnd" 把前一遍刚 cp 出来的文件删掉了，结果
         *   CP_MAIN=0      <- cp 自己报成功
         *   chmod: /data/adb/cairn/bin/cairnd: No such file or directory
         *   SIZE_MAIN=     <- stat 也读不到
         * 虽然最终一致（后一遍完整成功），但日志会被这种噪声污染，
         * 排查真问题时极易误判。这里用一把锁把整段钉住。
         */
        synchronized (daemonLock) {
            ensureDaemonLocked();
        }
    }

    /** ensureDaemon 的实际内容，只允许在持有 daemonLock 时调用 */
    private void ensureDaemonLocked() {
        // ---- 第一层：不需要 root 的自检 ----
        String st = core.selfTestNoRoot(this);
        for (String ln : st.split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("NATIVE", ln);
        }

        // ---- 第二层：有 root 才做的正式部署 ----
        CairnCore.Res dp = core.deployDaemon(k0, k1, this);
        for (String ln : dp.text().split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("DEPLOY", ln);
        }
        CairnCore.Res sr = core.startDaemon(k0, k1);
        for (String ln : sr.text().split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("START", ln);
        }
    }

    private void renderState(int st, String detail) {
        /*
         * 【为什么整个方法包 try/catch —— 这次闪退的防御性修复】
         *
         * 用户反馈点开就闪退，日志停在第二次 probe() 之后，
         * 没有异常信息。而 probe()==0 时唯一会走的路径就是这里：
         *   ui(() -> { state=fst; renderState(fst,fdet); postLog(); ... })
         *
         * 这个方法里访问了 dot / tvBigState / tvSubState / tvDetail /
         * navBar 五个 UI 组件。任何一个为 null（比如后台线程回调
         * 早于 buildUi 完成，或 Activity 已被系统回收重建），
         * 就会抛 NPE，而 UI 线程的未捕获异常 = 直接闪退。
         *
         * 状态渲染是"锦上添花"的功能：即使它失败，界面也应该正常显示，
         * 而不是把整个 APP 干掉。所以这里兜底 —— 出错就记日志，
         * 让界面停留在默认状态，用户可以继续操作、可以点Language.text(this,"Check again","重新检测")。
         *
         * 这不是掩盖问题：异常会带完整堆栈进日志（[UIERR]），
         * 配合 installCrashHandler 的 [CRASH] 一起，下次能直接定位。
         */
        try {
            switch (st) {
                case ST_WORKING:
                    dot.setBackground(circle(C_OK));
                    tvBigState.setText(Language.text(this,"Working…","正在工作中…"));
                    tvBigState.setTextColor(C_OK);
                    tvSubState.setText("内核补丁已生效");
                    tvSubState.setTextColor(C_OK);
                    break;
                case ST_NO_ROOT:
                    dot.setBackground(circle(C_WARN));
                    tvBigState.setText("未获取 root");
                    tvBigState.setTextColor(C_WARN);
                    tvSubState.setText("内核补丁已生效，但没有 root —— 请填写正确的密钥");
                    tvSubState.setTextColor(C_WARN);
                    break;
                case ST_NO_KERNEL:
                    dot.setBackground(circle(C_ERR));
                    tvBigState.setText(Language.text(this,"Not installed","未安装"));
                    tvBigState.setTextColor(C_ERR);
                    tvSubState.setText("当前内核没有 Cairn 补丁，刷入 Cairn 内核镜像后再试");
                    tvSubState.setTextColor(C_ERR);
                    break;
                default:
                    dot.setBackground(circle(C_DIM));
                    tvBigState.setText(Language.text(this,"Checking…","检测中…"));
                    tvBigState.setTextColor(C_DIM);
                    tvSubState.setText("");
                    break;
            }
            tvDetail.setText(detail);
            // 授权页在非工作状态下不可用：导航栏对应格子变暗，提示"这条路现在走不通"
            if (navBar != null) {
                navBar.setItemAlpha(1, st == ST_WORKING ? 1f : 0.35f);
            }
            refreshSettingsInfo();
        } catch (Throwable t) {
            /*
             * 兜底：把真实原因写进日志，但不让 APP 退出。
             *
             * 打印完整堆栈而不是只打 getMessage() —— NPE 的 message
             * 在新版 Android 上是空的（默认开启 helpful NPE 才有），
             * 只有堆栈能看出到底哪个字段是 null。
             */
            try {
                flog.log("UIERR", "renderState failed: " + t.getClass().getName());
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                for (String ln : sw.toString().split("\n")) {
                    if (!ln.trim().isEmpty()) {
                        flog.log("UIERR", ln.trim());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 应用列表 =================

    private void loadAppsAsync() {
        toast("正在加载应用列表…");
        new Thread(() -> {
            List<AppList.App> list = appList.load(true, true);
            String self = appList.selfPackage();
            List<AppList.App> keep = new ArrayList<>();
            for (AppList.App a : list) {
                if (a.pkg.equals(self)) continue;
                keep.add(a);
            }
            ui(() -> {
                allApps.clear();
                allApps.addAll(keep);
                appsLoaded = true;
                applyFilter();
                refreshGrantsAsync();
                toast("共 " + keep.size() + " 个应用");
            });
        }).start();
    }

    private void applyFilter() {
        String q = etSearch == null ? "" : etSearch.getText().toString().trim().toLowerCase();
        shownApps.clear();
        for (AppList.App a : allApps) {
            if (q.isEmpty()
                    || a.label.toLowerCase().contains(q)
                    || a.pkg.toLowerCase().contains(q)) {
                shownApps.add(a);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshGrantsAsync() {
        if (!keyLoaded) return;
        new Thread(() -> {
            List<int[]> l = core.listAuth(k0, k1);
            final Map<Integer, Integer> m = new HashMap<>();
            for (int[] p : l) m.put(p[0], p[1]);
            flog.log("GRANT", "授权表 " + m.size() + " 项");
            ui(() -> {
                granted.clear();
                granted.putAll(m);
                if (adapter != null) adapter.notifyDataSetChanged();
                postLog();
            });
        }).start();
    }

    private void toggleApp(final AppList.App a, final boolean on) {
        new Thread(() -> {
            CairnCore.Res r = on
                    ? core.grant(k0, k1, a.uid, true)
                    : core.revoke(k0, k1, a.uid);
            boolean ok = (r.rc == 0);
            flog.log("GRANT", (on ? "grant " : "revoke ") + a.pkg
                    + " uid=" + a.uid + " rc=" + r.rc + " ok=" + ok);
            ui(() -> {
                if (ok) {
                    if (on) granted.put(a.uid, CairnCore.POL_AUTO);
                    else granted.remove(a.uid);
                    toast((on ? "已授权 " : "已撤销 ") + a.label);
                } else {
                    toast("操作失败 rc=" + r.rc + "（详见日志）");
                }
                if (adapter != null) adapter.notifyDataSetChanged();
                postLog();
            });
        }).start();
    }

    /** 列表适配器 —— 每行：图标 + 名称 + 包名 + 开关 */
    private final class AppAdapter extends BaseAdapter {

        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int i) { return shownApps.get(i); }
        @Override public long getItemId(int i) { return shownApps.get(i).uid; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(roundRect(C_CARD, dp(12)));
                int pad = dp(12);
                row.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                row.setLayoutParams(rlp);

                ImageView iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
                ilp.rightMargin = dp(12);
                iv.setLayoutParams(ilp);
                row.addView(iv);

                LinearLayout mid = new LinearLayout(MainActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(mid, mlp);

                TextView name = new TextView(MainActivity.this);
                name.setTextColor(C_TEXT);
                name.setTextSize(14);
                name.setSingleLine(true);
                mid.addView(name);

                TextView pkg = new TextView(MainActivity.this);
                pkg.setTextColor(C_DIM);
                pkg.setTextSize(10);
                pkg.setSingleLine(true);
                pkg.setPadding(0, dp(2), 0, 0);
                mid.addView(pkg);

                Switch sw = new Switch(MainActivity.this);
                sw.setShowText(false);
                row.addView(sw);

                h = new Holder(iv, name, pkg, sw);
                row.setTag(h);
                cv = row;
            } else {
                h = (Holder) cv.getTag();
            }

            AppList.App a = shownApps.get(pos);
            h.name.setText(a.label);
            h.pkg.setText(a.pkg + "  ·  " + a.uid);
            if (a.icon != null) h.icon.setImageDrawable(a.icon);
            else h.icon.setImageDrawable(null);

            Integer pol = granted.get(a.uid);
            boolean on = pol != null && pol != CairnCore.POL_NONE;

            // 先解绑监听，避免复用时触发误回调
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(on);
            h.sw.setOnCheckedChangeListener((btn, checked) -> {
                if (checked == on) return;
                toggleApp(a, checked);
            });
            return cv;
        }
    }

    private static final class Holder {
        final ImageView icon;
        final TextView name, pkg;
        final Switch sw;
        Holder(ImageView i, TextView n, TextView p, Switch s) {
            icon = i; name = n; pkg = p; sw = s;
        }
    }

    // ================= 存储权限 =================

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) {
            flog.log("PERM", "sdk<30, legacy storage model");
            return;
        }
        boolean granted = false;
        try {
            granted = (Boolean) Environment.class
                    .getMethod("isExternalStorageManager").invoke(null);
        } catch (Throwable t) {
            flog.log("PERM", "isExternalStorageManager err: " + t);
        }
        if (granted) {
            flog.log("PERM", "all-files access granted");
            return;
        }
        flog.log("PERM", "MANAGE_EXTERNAL_STORAGE not granted, requesting");
        try {
            String act = (String) Settings.class
                    .getField("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                    .get(null);
            Intent i = new Intent(act);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            flog.log("PERM", "cannot open settings: " + t);
        }
    }
}