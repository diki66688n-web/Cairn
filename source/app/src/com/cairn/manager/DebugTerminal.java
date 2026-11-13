package com.cairn.manager;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 【临时调试用】自定义终端。
 *
 * 目的：绕开 mksh / bash 的 PATH 查找（stat -> access -> execve）三层逻辑，
 * 直接把我们想测的 syscall 序列送进内核，用来单独判断"内核桩本身对不对"。
 *
 * 所有输出同时写 /storage/emulated/0/Download/cairn_logs/，调试完直接删本文件 +
 * AndroidManifest 里那一行 activity 声明即可，不影响主界面。
 */
public class DebugTerminal extends Activity {

    /** 输出目录：Download/cairn_logs/ */
    private static final String LOG_DIR =
            "/storage/emulated/0/Download/cairn_logs";

    private TextView out;
    private EditText input;
    private ScrollView scroller;
    private CairnCore core;
    private long k0, k1;
    private final StringBuilder hist = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        core = new CairnCore(this);

        // 密钥：与主界面共用同一份存储，避免再输一遍
        try {
            KeyStoreBox box = new KeyStoreBox(this);
            String hex = box.load();
            if (hex != null && hex.length() == 32) {
                long[] kk = CairnCore.keyToLongs(hex);
                k0 = kk[0];
                k1 = kk[1];
            }
        } catch (Throwable ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(d(12), d(12), d(12), d(12));

        TextView title = new TextView(this);
        title.setText("CAIRN 调试终端（临时）"
                + "\n密钥状态: " + (k0 == 0 && k1 == 0 ? "未加载" : "已加载")
                + "\n日志: " + LOG_DIR);
        title.setTextColor(0xFF7FA8D0);
        title.setTextSize(11f);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroller = new ScrollView(this);
        out = new TextView(this);
        out.setTextColor(0xFFB6C0CE);
        out.setTextSize(9.5f);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        scroller.addView(out, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroller, slp);

        input = new EditText(this);
        input.setHint("输入命令后回车执行（单行 shell，走 su 子进程）");
        input.setTextColor(0xFFE8EEF5);
        input.setHintTextColor(0xFF5A6572);
        input.setTextSize(11f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(quickRow("probe", "selftest", "id", "systemsu"),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        bar.addView(btn("执行", () -> submit()), weight());
        bar.addView(btn("清屏", () -> { hist.setLength(0); out.setText(""); }), weight());
        bar.addView(btn("导出", () -> export()), weight());
        bar.addView(btn("复制", () -> copyAll()), weight());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        say("== Cairn 调试终端已启动 ==");
        say("预设：");
        say("  probe            -> 内核是否有补丁");
        say("  selftest         -> 子进程 uid（密钥是否可用）");
        say("  id               -> 当前身份");
        say("  ls -l /system/bin/su");
        say("  stat /system/bin/su");
        say("  su               -> 真正测 exec 桩（关键）");
        say("");

        // 快捷按钮行已经插在输入框上方了（见 onCreate 中的 insertBefore）
    }

    // ------------------------------------------------------------------ UI

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int d(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    /** 一键预置命令按钮，返回这一行，由调用处决定插到哪里 */
    private View quickRow(String... cmds) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String c : cmds) {
            row.addView(btn(c, () -> {
                if ("systemsu".equals(c)) {
                    input.setText("/system/bin/su");
                } else if ("probe".equals(c)) {
                    doProbe();
                } else if ("selftest".equals(c)) {
                    doSelfTest();
                } else {
                    input.setText(c);
                    submit();
                }
            }), weight());
        }
        return row;
    }

    // ------------------------------------------------------------------ 执行

    private void submit() {
        String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) {
            return;
        }
        input.setText("");
        run(cmd);
    }

    private void doProbe() {
        say("$ probe");
        new Thread(() -> {
            long p;
            try {
                p = Native.probe();
            } catch (Throwable t) {
                ui(() -> say("probe 异常: " + t));
                return;
            }
            ui(() -> say("PROBE=" + p + "  ("
                    + (p == 1 ? "有补丁" : p == 0 ? "原厂无补丁" : "未知")
                    + ")"));
        }).start();
    }

    private void doSelfTest() {
        if (k0 == 0 && k1 == 0) {
            say("!! 没有密钥，无法执行 root 命令");
            return;
        }
        say("$ selftest");
        new Thread(() -> {
            CairnCore.Res r = core.selfTest(k0, k1);
            ui(() -> say("rc=" + r.rc + "\n" + r.text()));
        }).start();
    }

    private void run(final String cmd) {
        if (k0 == 0 && k1 == 0) {
            say("$ " + cmd);
            say("!! 没有密钥，无法执行 root 命令");
            return;
        }
        say("$ " + cmd);
        new Thread(() -> {
            CairnCore.Res r;
            try {
                r = core.runRoot(k0, k1, cmd);
            } catch (Throwable t) {
                ui(() -> say("执行异常: " + t));
                return;
            }
            final String body = r.text();
            final long rc = r.rc;
            ui(() -> {
                if (body != null && body.length() > 0) {
                    say(body.trim());
                }
                say("[rc=" + rc + "]");
                say("");
            });
        }).start();
    }

    // ------------------------------------------------------------------ 输出

    private void say(String s) {
        hist.append(s).append('\n');
        out.setText(hist.toString());
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    /** 写一份到 Download/cairn_logs/ */
    private void export() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File f = new File(dir, "debug-" + ts + ".log");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(hist.toString().getBytes("UTF-8"));
            fo.close();
            say("[已导出] " + f.getAbsolutePath());
        } catch (Throwable t) {
            say("!! 导出失败: " + t);
        }
    }

    private void copyAll() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "cairn-debug", hist.toString()));
            say("[已复制到剪贴板]");
        } catch (Throwable t) {
            say("!! 复制失败: " + t);
        }
    }
}
