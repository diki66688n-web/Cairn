package com.cairn.manager;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 提权通道封装 —— 所有需要 root 的动作都走这里。
 *
 * 设计约束（真机实证得来，不要改）：
 *   1) 本进程永不提权。app 一旦把自己的 cred 改成 uid 0，
 *      system_server 的身份校验会全部失败（Toast/通知/Activity 都被拒），
 *      app 会变成"有 root 却什么都干不了"。所以一律 clone 子进程。
 *   2) 子进程只能执行 $FILES/cairn.cmd 这一个脚本，路径由 native 固定推导。
 *      Java 不传字符串给 native（手写 JNI 函数表偏移极易算错跳飞）。
 *   3) 脚本首行必须是 SHELL，native 才会用 /system/bin/sh 执行，
 *      否则走内置的极简解释器（能力很弱，只有 cat/ls/stat 之类）。
 */
public final class CairnCore {

    /** 授权策略，必须与 cairnd.c 的 CAIRN_POL_* 一致 */
    public static final int POL_NONE    = 0;
    public static final int POL_CONFIRM = 1;
    public static final int POL_AUTO    = 2;

    /** 探针结果 */
    public static final int KERNEL_PATCHED = 1;
    public static final int KERNEL_STOCK   = 0;
    public static final int KERNEL_UNKNOWN = -3;

    public static final String BASE = "/data/adb/cairn";
    public static final String AUTH_DIR = BASE + "/auth";
    /** 守护进程自己的运行日志（cairn_sys.h 里 CAIRN_LOG 对应路径） */
    public static final String LOG_FILE = BASE + "/daemon.log";
    /* cnd dispatches by argv[0] basename: su executes the su path, cnd handles explicit administration/stage commands. See daemon/cairnd.c, not historical Rust daemon sources. */
    public static final String DAEMON_BIN = "/data/adb/cnd";
    /** 历史路径，仅用于升级时清理旧文件，新部署不再往这里写 */
    /**
     * 自带 sh 的落地路径。
     *
     * 【为什么必须有这个文件,不能用 /system/bin/sh】
     *
     * stage.c:50 写死了 CAIRN_SH_PATH = "/data/adb/bin/sh",
     * 模块脚本(post-fs-data.sh / service.sh)全部由它执行:
     *
     *     sc3(SYS_execve, CAIRN_SH_PATH, argv, envp)   // stage.c:149
     *
     * 用自带 sh 而不是 /system/bin/sh:
     *   1) 系统 shell 是动态链接的,在早期阶段不一定能加载;
     *   2) 它的行为受厂商改动影响,各家不一;
     *   3) 自带 toybox 是静态 ET_EXEC,零依赖,行为固定。
     *
     * 它和 cnd 一样必须走 nativeLibraryDir:
     *   untrusted_app 域只被允许在 apk_data_file(lib/<abi>/)下执行文件,
     *   放在 assets/ 或 files/ 下 execve 会被 execute_no_trans 拒绝。
     *   所以打进 APK 的 lib/arm64-v8a/libcairnsh.so,
     *   由系统安装时解压到 nativeLibraryDir 并置 0755。
     *
     * 缺了这个文件,模块脚本会 execve 失败 -> exit 127,
     * 表现为“模块全部静默不生效”。
     */
    public static final String SH_BIN = "/data/adb/bin/sh";
    public static final String BIN_DIR = BASE + "/bin";
    public static final String MGR_UID_FILE = BASE + "/manager.uid";
    /**
     * 【V46 已删除 daemon.sock】
     *
     * 守卫进程不再监听任何 socket：
     *   1) su 授权走内核 cred 改写，根本不经过 socket；
     *   2) /data/adb 是 0700 root:root，非 root APP 穿不过去；
     *   3) 更要命的是 bind 前必须 unlink，任何第二个实例都会顶替
     *      真守护进程的监听点 —— MT 卡死那次的
     *      "[init] listening /data/adb/cairn/daemon.sock" 就是这个。
     *   4) 管理操作改成 cnd <子命令> 单次执行，不需要常驻 IPC。
     *
     * 这个旧路径只在"升级时清理残留"里出现，不再有任何判断逻辑。
     */

    private final File cmdFile;
    private final File outFile;
    /** runRoot 每次调用的唯一序号，用来给脚本/输出文件起不重名的名字 */
    private static final java.util.concurrent.atomic.AtomicLong SEQ
            = new java.util.concurrent.atomic.AtomicLong();

    public CairnCore(Context ctx) {
        File dir = ctx.getFilesDir();
        cmdFile = new File(dir, "cairn.cmd");
        outFile = new File(dir, "cairn.out");
    }

    /** 一次 root 执行的结果 */
    public static final class Res {
        public final long rc;        // spawnRoot 返回值
        public final String out;     // 子进程输出
        public Res(long rc, String out) { this.rc = rc; this.out = out; }
        public boolean ok() { return rc == 0; }
        public String text() { return out == null ? "" : out; }
    }

    /**
     * 以 root 子进程执行一段 sh 脚本。
     *
     * @param body 脚本正文（不要带 SHELL 头，本方法会加）
     */
    public Res runRoot(long k0, long k1, String body) {
        return runRoot(k0, k1, body, true);
    }

    /** shell=true 走 /system/bin/sh（会 execve）；false 走内置解释器（不 execve）。 */
    public Res runRoot(long k0, long k1, String body, boolean shell) {
        StringBuilder sb = new StringBuilder();
        if (shell) sb.append("SHELL\n");
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        /*
         * 每次调用用一对唯一文件。
         *
         * 真机 11:05:41 日志实证：refreshStatusAsync 会被 onCreate /
         * onResume / 点按钮并发触发，同一秒内两次 selfTest 交错。
         * 旧实现两条调用共用 cairn.cmd/cairn.out，后一次 O_TRUNC
         * 把前一次的输出抹掉，于是出现 selfTest 读到的却是
         * ensureDaemon 部署脚本的日志，CAIRN_UID=0 找不到，
         * 界面永远停在"检测中"。
         * 改成每次唯一文件名（进程内自增序号），并发之间互不干扰。
         */
        long seq = SEQ.incrementAndGet();
        File dir = cmdFile.getParentFile();
        File myCmd = new File(dir, "cairn-" + seq + ".cmd");
        File myOut = new File(dir, "cairn-" + seq + ".out");
        try {
            writeText(myCmd, sb.toString());
        } catch (Throwable t) {
            return new Res(-3, "写脚本失败: " + t);
        }
        long rc;
        try {
            rc = Native.spawnRoot(k0, k1, myCmd.getAbsolutePath(), myOut.getAbsolutePath());
        } catch (Throwable t) {
            myCmd.delete();
            return new Res(-3, "调用 spawnRoot 失败: " + t);
        }
        String out = "";
        try { out = readText(myOut); } catch (Throwable t) { /* ignore */ }
        myCmd.delete();
        myOut.delete();
        return new Res(rc, out);
    }

    // ---------------- 提权自检 ----------------

    /**
     * 只让子进程报告 uid，用来判断密钥是否可用。
     * 不写任何文件、不改任何状态，可反复调用。
     */
    public Res selfTest(long k0, long k1) {
        return runRoot(k0, k1, "echo CAIRN_UID=$(id -u)\n");
    }

    /**
     * 判断"是否已在工作中"：子进程能提到 uid 0。
     *
     * 这是唯一可信的判据。probe() 只能证明内核有补丁，
     * 不能证明密钥对、也不能证明提权链路通。
     */
    public boolean isWorking(long k0, long k1) {
        Res r = selfTest(k0, k1);
        return r.rc == 0 && r.text().contains("CAIRN_UID=0");
    }

    // ---------------- 授权 ----------------

    /**
     * 读授权表。
     *
     * 【V46：改成读 cnd list 的输出，不再自己扫目录】
     *
     * 旧实现自己 for f in auth/* 再 cat 文件内容判断策略，
     * 逻辑与 cnd 内部 auth_policy() 重复，两边一改就漂。
     * 现在统一由 cnd 输出 "uid:policy" 每行一条，
     * policy 数值就是 CAIRN_POL_NONE/CONFIRM/AUTO，与本类常量同源。
     *
     * 为什么仍然必须提权：/data/adb 是 0700 root:root，
     * 管理器是普通 uid，直接读会被 EACCES 拒掉。
     */
    public List<int[]> listAuth(long k0, long k1) {
        List<int[]> ret = new ArrayList<>();
        Res r = runRoot(k0, k1, DAEMON_BIN + " list 2>&1\n");
        for (String ln : r.text().split("\n")) {
            ln = ln.trim();
            if (ln.isEmpty()) continue;
            int c = ln.indexOf(':');
            if (c <= 0) continue;
            int uid, pol;
            try {
                uid = Integer.parseInt(ln.substring(0, c).trim());
                pol = Integer.parseInt(ln.substring(c + 1).trim());
            } catch (Exception e) { continue; }
            if (uid < 10000) continue;
            if (pol != POL_AUTO) pol = POL_CONFIRM;
            ret.add(new int[]{uid, pol});
        }
        return ret;
    }

    /**
     * 授权。
     *
     * 【V46：改成调用 cnd grant，不再自己算 shell 重定向】
     *
     * 关键收益：cnd grant 内部用 open(O_CREAT) 新建文件，
     * 天然避开"覆盖已有文件走 setattr 被真实 SELinux 拒"这个坑
     * （与应用部署同一个约束）；而且成功后它自己会调 wl_sync()
     * 把授权表同步进内核白名单，不必再等守护进程 30 秒兜底。
     *
     * autoAllow=true 时传第三个参数 A，cnd 写入 'A' 标记免确认。
     */
    public Res grant(long k0, long k1, int uid, boolean autoAllow) {
        if (uid < 10000) return new Res(-4, "拒绝：uid=" + uid + " 是系统身份");
        String sh = DAEMON_BIN + " grant " + uid + (autoAllow ? " A" : "") + " 2>&1\n"
                + "echo GRANT_RC=$?\n";
        return foldSubRc(runRoot(k0, k1, sh), "GRANT_RC=");
    }

    /**
     * 把子命令的退出码折进 Res.rc。
     *
     * 为什么需要：runRoot 的 rc 是 root 子进程（/system/bin/sh）的退出码，
     * 只要脚本本身跑完就是 0 —— 即使它执行的 cnd grant 返回 1。
     * 调用方一直用 r.rc==0 判成功，那样会把"cnd 报错"误判成成功。
     * 所以这里把 cnd 自己的退出码解析出来，非 0 就整体判失败。
     */
    private static Res foldSubRc(Res r, String tag) {
        int code = 0;
        boolean seen = false;
        for (String ln : r.text().split("\n")) {
            ln = ln.trim();
            if (!ln.startsWith(tag)) continue;
            seen = true;
            try { code = Integer.parseInt(ln.substring(tag.length()).trim()); }
            catch (Exception e) { code = -1; }
        }
        if (!seen) return new Res(-6, r.text() + "\n(缺少 " + tag + "，脚本未跑到)");
        return new Res(code, r.text());
    }

    /* Diagnostic probe using JNI built-ins, without spawning a shell. DAC and SELinux failures must be distinguished using actual results, not inferred from uid alone. */
    public Res diag(long k0, long k1, int uid) {
        String fn = AUTH_DIR + "/" + uid;
        return runRoot(k0, k1,
                "echo DIAG_uid=$(id -u)\n" +
                "echo DIAG_ctx=$(cat /proc/self/attr/current 2>&1)\n" +
                "echo DIAG_enforce=$(getenforce 2>&1)\n" +
                "echo DIAG_base=$(ls -ld " + BASE + " 2>&1)\n" +
                "echo DIAG_authdir=$(ls -ld " + AUTH_DIR + " 2>&1)\n" +
                "echo DIAG_authlist=$(ls -l " + AUTH_DIR + " 2>&1)\n" +
                "echo DIAG_daemon=$(pidof cnd 2>&1)\n" +
                "mkdir -p " + AUTH_DIR + " 2>&1; echo DIAG_mkdir=$?\n" +
                "printf A > " + fn + " 2>&1; echo DIAG_write=$?\n" +
                "chown 0:0 " + fn + " 2>&1; echo DIAG_chown=$?\n" +
                "chmod 0600 " + fn + " 2>&1; echo DIAG_chmod=$?\n" +
                "printf A > /data/local/tmp/cairn_probe 2>&1; echo DIAG_tmpwrite=$?\n" +
                "rm -f /data/local/tmp/cairn_probe 2>&1\n");
    }


    /**
     * 拉取守护进程 cnd 自己的运行日志。
     *
     * cnd 把每次启动来源、连接方 uid/pid/cmdline、收到的命令、
     * 执行结果与耗时全部写进 CAIRN_LOG。只有 root 能读，
     * 所以必须先提权再读。
     *
     * 同时返回：日志最后 400 行 + 文件状态。不截断行首，
     * 因为排查时需要完整时间/uid 信息。
     */
    public Res cndLog(long k0, long k1, int lines) {
        if (lines <= 0) lines = 400;
        String sh = "echo CNDLOG_stat=$(ls -l " + LOG_FILE + " 2>&1)\n"
                + "echo CNDLOG_wc=$(wc -l < " + LOG_FILE + " 2>&1)\n"
                + "echo ===CNDLOG_BEGIN===\n"
                + "tail -n " + lines + " " + LOG_FILE + " 2>&1\n"
                + "echo ===CNDLOG_END===\n";
        return runRoot(k0, k1, sh);
    }

    /** 清空 cnd 日志（保留文件本身） */
    public Res clearCndLog(long k0, long k1) {
        return runRoot(k0, k1, ": > " + LOG_FILE + "\necho CLEARED=$?\n");
    }

    /**
     * 撤销授权。
     *
     * 【V46：改成调用 cnd revoke】
     *
     * 与 grant 对称：cnd 内部删文件后立即 wl_sync()，
     * 撤销立刻生效，不必等守护进程 30 秒周期。
     */
    public Res revoke(long k0, long k1, int uid) {
        return foldSubRc(runRoot(k0, k1,
                DAEMON_BIN + " revoke " + uid + " 2>&1\n" +
                "echo REVOKE_RC=$?\n"), "REVOKE_RC=");
    }

    /** 把本 APP 的 uid 写进 manager.uid（APP 更新后 uid 会变，必须重写） */
    public Res initSelf(long k0, long k1, int myUid) {
        return runRoot(k0, k1,
                "mkdir -p " + BASE + " " + AUTH_DIR + " " + BIN_DIR + " 2>/dev/null\n" +
                "chmod 0700 " + BASE + " 2>/dev/null\n" +
                "echo " + myUid + " > " + MGR_UID_FILE + "\n" +
                "chown 0:0 " + MGR_UID_FILE + " 2>/dev/null\n" +
                "chmod 0600 " + MGR_UID_FILE + " 2>/dev/null\n" +
                "echo MGR_UID=$(cat " + MGR_UID_FILE + ")\n");
    }

    // ---------------- 守护进程定位 / 部署 ----------------

    /**
     * 守护进程可执行文件的路径。
     *
     * 为什么用 nativeLibraryDir 而不是 filesDir：
     *
     *   Android 的 SELinux 策略里，untrusted_app 域只被允许在
     *   apk_data_file（即 /data/app/.../lib/<abi>/）执行文件。
     *   放在 /data/data/<pkg>/files/ 就算 chmod 0755，
     *   W^X 那套规则也会判成 execute_no_trans 直接拒绝。
     *
     *   所以二进制必须以 lib<name>.so 的形式打进 APK 的 lib/<abi>/，
     *   由系统在安装时解压到 nativeLibraryDir —— 那个目录里的文件
     *   天然带 0755 且有 execute 权限，app 自己就能直接 exec。
     *   这条路不需要 root，是后面做"无 root 修补工具"的基础。
     *
     * extractNativeLibs="true"（见 AndroidManifest）保证它被解压成
     * 真实文件而不是留在 apk 里映射，否则 exec 不了。
     */
    public static java.io.File daemonBin(Context ctx) {
        return new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libcairnd.so");
    }

    /**
     * 自带 sh 的可执行路径(libcairnsh.so -> nativeLibraryDir)。
     *
     * 与 daemonBin() 同一套理由:必须是 lib/<abi>/ 里的文件,
     * 系统安装时解压到 nativeLibraryDir 并给 0755,APP 才能直接 exec。
     */
    public static java.io.File shBin(Context ctx) {
        return new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libcairnsh.so");
    }

    /** 兼容旧调用：现在不再是"解压到私有目录"，改成返回真实可执行路径 */
    public String stageDaemon(Context ctx) {
        java.io.File f = daemonBin(ctx);
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    /**
     * 不需要 root 的自检：直接把 libcairnd.so 当成可执行文件跑一次。
     *
     * 这是本方案的地基 —— 如果这一条在真机上不通，
     * 后面所有"无 root 修补"都不用谈了。所以先量出来，不猜。
     */
    public String selfTestNoRoot(Context ctx) {
        StringBuilder sb = new StringBuilder();
        java.io.File bin = daemonBin(ctx);
        java.io.File res = new java.io.File(ctx.getFilesDir(), "selftest.txt");
        res.delete();
        sb.append("EXISTS=").append(bin.isFile() ? "yes" : "no").append('\n');
        sb.append("PATH=").append(bin.getAbsolutePath()).append('\n');
        sb.append("SIZE=").append(bin.length()).append('\n');
        sb.append("CANEXEC=").append(bin.canExecute() ? "yes" : "no").append('\n');
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--selftest");
            pb.redirectErrorStream(true);
            // 第二路证据：让它把同样的内容写进我们的私有目录。
            // 万一 ProcessBuilder 拿不到 stdout，还能从文件读回来。
            pb.environment().put("CAIRN_SELFTEST_OUT", res.getAbsolutePath());
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = r.readLine()) != null) sb.append("OUT:").append(ln).append('\n');
            r.close();
            sb.append("RC=").append(p.waitFor()).append('\n');
        } catch (Throwable t) {
            sb.append("ERR=").append(t).append('\n');
        }
        // 回读落盘那份，作为交叉验证
        if (res.isFile()) {
            try {
                String body = readText(res);
                for (String ln : body.split("\n")) {
                    if (!ln.trim().isEmpty()) sb.append("FILE:").append(ln).append('\n');
                }
            } catch (Throwable t) {
                sb.append("FILE_ERR=").append(t).append('\n');
            }
        } else {
            sb.append("FILE=missing\n");
        }
        return sb.toString();
    }

    /* Deployment uses /data/adb/cnd and the bundled /data/adb/bin/sh.
 * Current cnd is a one-shot command/stage executable, not a resident socket service.
 * Keep remove-then-copy deployment until device-specific overwrite constraints are retested. */
    /**
     * 纯内置指令探测 /data/adb 可达性。不走 shell、不 execve，
     * 用来分离“DAC/SELinux 拒绝”与“execve 后 cred 被重置”这两个原因。
     */
    public Res probeAdb(long k0, long k1) {
        return runRoot(k0, k1,
                "UID\n" +
                "IDS\n" +
                "STAT /data/adb\n" +
                "STAT /data/adb/cairn\n" +
                "MKDIR /data/adb/cairn\n" +
                "MKDIR /data/adb/cairn/auth\n" +
                "STAT /data/adb/cairn/auth\n" +
                "WRITE /data/adb/cairn/auth/probe 41\n" +
                "STAT /data/adb/cairn/auth/probe\n" +
                "UID\n", false);
    }

    public Res deployDaemon(long k0, long k1, Context ctx) {
        java.io.File src = daemonBin(ctx);
        if (!src.isFile()) return new Res(-5, "libcairnd.so 不存在于 nativeLibraryDir");
        String staged = src.getAbsolutePath();
        /*
         * 部署极简化（V33）：
         *   1) 先 rm 再 cp —— 不能覆盖。覆盖走 setattr，被真实 SELinux 拒；
         *      新建走 create，内核 hook 已放行。提权后 uid=0，
         *      新建文件天然 root:root，不需要 chown/chmod。
         *   2) 最后跑一次 --selftest 拿 uid 作为唯一验收依据。
         *      不再逐项 echo / probe /data/adb —— 那些是排查用的，
         *      链路通了之后留着只会拖慢流程并产生误判。
         */
        /*
         * sh 与 cnd 必须同批部署:
         *
         *   stage.c 用写死的 /data/adb/bin/sh 跑模块脚本,
         *   cnd 新版能跑到 stage 但 sh 不存在时,每个脚本都会
         *   execve 失败 -> exit 127,日志里只看到 “script FAILED rc=127”,
         *   极难反查到“是 sh 缺失”。
         *
         * 所以这里一次 root 往返把两个文件都安置好:
         *   /data/adb/cnd        守护进程 + su 客户端(argv[0] 分流)
         *   /data/adb/bin/sh     模块脚本解释器(静态 toybox)
         *
         * 两者都是“先 rm 再 cp”(新建而非覆盖):覆盖会走 setattr
         * 被真实 SELinux 拒;新建走 create,内核 hook 已放行。
         * 提权后 uid=0,新建文件天然 root:root,无需 chown/chmod。
         */
        java.io.File shSrc = shBin(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p " + AUTH_DIR + " 2>/dev/null\n");
        sb.append("mkdir -p /data/adb/bin 2>/dev/null\n");
        sb.append("rm -f " + DAEMON_BIN + "\n");
        sb.append("cp " + staged + " " + DAEMON_BIN + " 2>&1\n");
        sb.append("echo CND_SIZE=$(stat -c%s " + DAEMON_BIN + " 2>/dev/null || echo 0)\n");
        sb.append("echo CND_SHA=$(sha256sum " + DAEMON_BIN + " 2>/dev/null | cut -c1-16)\n");
        if (shSrc.isFile()) {
            sb.append("rm -f " + SH_BIN + "\n");
            sb.append("cp " + shSrc.getAbsolutePath() + " " + SH_BIN + " 2>&1\n");
            sb.append("echo SH_SIZE=$(stat -c%s " + SH_BIN + " 2>/dev/null || echo 0)\n");
            sb.append("echo SH_SHA=$(sha256sum " + SH_BIN + " 2>/dev/null | cut -c1-16)\n");
        } else {
            sb.append("echo SH_SRC_MISSING=1\n");
        }
        sb.append(DAEMON_BIN + " --selftest 2>&1 | head -3 | tr '\n' '|'");
        return runRoot(k0, k1, sb.toString());
    }

    /* Restore persisted grants through authenticated cnd --sync-wl. The legacy method name is retained for existing callers; no resident daemon is started. */
    public Res startDaemon(long k0, long k1) {
        // cnd is one-shot: restore persisted grants on every manager startup.
        // JNI already supplies CAIRN_KEY to this authenticated child shell.
        return foldSubRc(runRoot(k0,k1,
                DAEMON_BIN + " --sync-wl 2>&1\n" +
                "echo WL_SYNC_RC=$?\n"), "WL_SYNC_RC=");
    }

    /* Collect diagnostic process/path information and run cnd status. Process presence is not required for one-shot cnd and does not prove kernel whitelist state. */
    public Res daemonStatus(long k0, long k1) {
        return runRoot(k0, k1,
                "echo DAEMON_PID=$(pidof cnd 2>/dev/null || echo none)\n" +
                // V21：守护进程落在 /data/adb/cnd，单个文件双身份
                "echo CND_BIN=$([ -x " + DAEMON_BIN + " ] && echo yes || echo no)\n" +
                "echo CND_SIZE=$(stat -c%s " + DAEMON_BIN + " 2>/dev/null || echo 0)\n" +
                // 确认系统里没有真的 su 文件（应该是 ABSENT，拦截全靠内核）
                "echo SU_REAL=$([ -e /system/bin/su ] && echo EXISTS || echo ABSENT)\n" +
                // 旧版残留检查，用于确认升级后已清理
                "echo OLD_BIN=$(ls " + BIN_DIR + " 2>/dev/null | tr '\\n' ',' || echo none)\n" +
                "echo MGR_UID=$(cat " + MGR_UID_FILE + " 2>/dev/null || echo none)\n" +
                // cnd 自己的状态（单次执行，跑完退出）
                "echo ---CND_STATUS---\n" +
                DAEMON_BIN + " status 2>&1\n");
    }

    // ---------------- 密钥解析 ----------------

    /** 32 位 hex -> {k0, k1}。格式与内核 cairn_hash 的 16 字节原始数据一致。 */
    public static long[] keyToLongs(String hex) {
        byte[] raw = KeyStoreBox.hexToBytes(hex);
        if (raw.length != 16) throw new IllegalArgumentException("需要 16 字节");
        return KeyStoreBox.keyToLongs(raw);
    }

    // ---------------- 文件 ----------------

    private static void writeText(File f, String s) throws Exception {
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
        try {
            fo.write(s.getBytes("UTF-8"));
            fo.flush();
        } finally {
            fo.close();
        }
    }

    private static String readText(File f) throws Exception {
        if (!f.exists()) return "";
        java.io.FileInputStream fi = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fi.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        } finally {
            fi.close();
        }
    }
}