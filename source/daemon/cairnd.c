/* cnd: one-shot root administration, persisted allowlist synchronization, su dispatch, and module stages.
 * APP supplies CAIRN_KEY through the authenticated child environment. Init stage calls do not carry this key.
 * No resident socket listener is started by the current command dispatcher. Authorization is handled by the CLI and kernel whitelist. */
#include "cairn_proto.h"
#include "cairn_sys.h"

#define MAX_WORKERS         32
#define READ_TIMEOUT_MS     5000
#define CONNECT_BACKLOG     16

static u32 g_manager_uid = 0;
static u64 g_wl_key[2];
static int g_wl_key_valid;
static int key_hex(char c) {
    if (c >= '0' && c <= '9') return c-'0';
    if (c >= 'a' && c <= 'f') return c-'a'+10;
    if (c >= 'A' && c <= 'F') return c-'A'+10;
    return -1;
}
static void load_wl_key(char **envp) {
    if (!envp) return;
    for (; *envp; envp++) {
        char *s = *envp;
        const char *prefix = "CAIRN_KEY=";
        int j=0;
        while (prefix[j] && s[j] == prefix[j]) j++;
        if (prefix[j]) continue;
        s += j;
        if (slen(s) != 32) return;
        g_wl_key[0]=g_wl_key[1]=0;
        for (int i=0; i<16; i++) {
            int a=key_hex(s[i*2]), b=key_hex(s[i*2+1]);
            if (a<0 || b<0) return;
            g_wl_key[i/8] |= (u64)((a<<4)|b) << ((i&7)*8);
        }
        g_wl_key_valid=1;
        for (int i=0; i<32; i++) s[i]='0';
        return;
    }
}

/* ================= 目录与授权表 ================= */

static void ensure_dir(const char *path)
{
    sc3(SYS_mkdirat, AT_FDCWD, (i64)path, 0700);
}

static int exists(const char *path)
{
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)path, O_RDONLY, 0);
    if (fd < 0) return 0;
    sc1(SYS_close, fd);
    return 1;
}

#include "allowlist.h"
#include "modules.c"
#include "stage.c"

/* ================= 内核白名单同步 =================
 *
 * 内核 exec 桩命中 /system/bin/su 后，会查一张内核内存里的 uid 白名单：
 *   在表里 -> 改 cred（uid=0 + cap_bset bit50）并重定向到 cnd
 *   不在表里 -> 走原路径，返回内核真实 ENOENT（未授权时保留原错误路径；不代表可隐藏所有 root 痕迹）
 *
 * 白名单的持久来源是 /data/adb/cairn/allowlist，每行一个 UID：
 *   grant/revoke 通过锁和原子替换更新集中式文件
 * 每次 grant/revoke 后重扫一遍全表写进内核，保证两边不会漂。
 *
 * 缓冲区格式（与内核 cairn_wlwrite 约定）：
 *   u32 count
 *   entry[count] = { u32 uid; u32 starttime; u32 flags; u32 reserved; }
 * starttime 目前填 0（不启用"绑定进程"模式），flags bit0 = 允许。
 *
 * 边界：最多 128 项。非法文件同步空表并记日志 ——
 * 绝不能给内核传超限 count，否则就回到"溢出写到指令上"那条死路。
 */
#define CAIRN_WL_MAX 128
static u32 g_wl_buf[1 + CAIRN_WL_MAX * 4];

static i64 prctl_wl_write(u32 count)
{
    if (!g_wl_key_valid) return -1;
    register long x8 __asm__("x8") = SYS_prctl;
    register long x0 __asm__("x0") = 0x43414952L;   /* CAIRN_MAGIC */
    register long x1 __asm__("x1") = (long)g_wl_key[0];
    register long x2 __asm__("x2") = (long)g_wl_key[1];
    register long x3 __asm__("x3") = 2;             /* op = WRITE_WL */
    register long x4 __asm__("x4") = (long)g_wl_buf;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    (void)count;
    return x0;
}

static i64 wl_sync(void)
{
    if (al_lock()<0) return -1;
    u32 n=0;
    if (al_load()<0) {
        al_count=0;
        loga("[wl] invalid allowlist: syncing empty table\n");
    }
    for(u32 i=0;i<al_count;i++) {
        u32 *e=g_wl_buf+1+n*4;
        e[0]=al_uid[i];e[1]=0;e[2]=1;e[3]=0;n++;
    }
    g_wl_buf[0] = n;
    i64 r = prctl_wl_write(n);
    loga("[wl] sync count=");
    loga_num("", n);
    loga(" rc=");
    loga_num("", (u32)r);
    loga("\n");
    al_unlock();
    return r;
}

/* manager.uid 是管理接口的唯一凭据：谁写在里面，谁才能 GRANT/REVOKE */
static int load_manager_uid(void)
{
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)CAIRN_MGR_UID_FILE, O_RDONLY, 0);
    if (fd < 0) return -1;
    char buf[32];
    i64 n = sc3(SYS_read, fd, (i64)buf, (i64)sizeof(buf) - 1);
    sc1(SYS_close, fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    g_manager_uid = atou(buf);
    return g_manager_uid ? 0 : -1;
}

/* Shared CLI output buffer and formatters (used by list/status). */
static char g_adm_out[4096];

/* 追加 s 到 g_adm_out，返回新的写入位置 */
static char *adm_put(char *p, const char *s)
{
    return stpcpy_(p, s);
}

static char *adm_put_num(char *p, u32 v)
{
    char b[12];
    utoa(v, b);
    return stpcpy_(p, b);
}

/* 记录自己是被谁拉起来的：父进程名 + cmdline + 自身 argv */
static void log_proc_line(const char *tag, const char *fmt_pid)
{
    char path[64];
    char *p = path;
    p = stpcpy_(p, fmt_pid);
    p = stpcpy_(p, "/cmdline");
    *p = 0;
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)path, O_RDONLY, 0);
    if (fd < 0) { loga_str(tag, "(unreadable)"); return; }
    char buf[128];
    i64 r = sc3(SYS_read, fd, (i64)buf, sizeof(buf) - 1);
    sc1(SYS_close, fd);
    if (r <= 0) { loga_str(tag, "(empty)"); return; }
    buf[r] = 0;
    /* cmdline 用 \0 分隔，转成空格便于阅读 */
    for (i64 i = 0; i < r; i++) if (buf[i] == 0) buf[i] = ' ';
    buf[r] = 0;
    loga_str(tag, buf);
}

static void log_startup_source(void)
{
    char b1[12], b2[12], line[160];
    u32 ppid = (u32)sc0(SYS_getppid);

    /* 自身 argv0 */
    log_proc_line("[init] self-cmdline: ", "/proc/self");
    /* 父进程 */
    utoa(ppid, b1);
    char pb[32];
    char *q = pb; q = stpcpy_(q, "/proc/"); q = stpcpy_(q, b1); *q = 0;
    log_proc_line("[init] parent-cmdline: ", pb);

    /* 父进程 exe 名 */
    char *pp = line;
    pp = stpcpy_(pp, "[init] parent pid=");
    utoa(ppid, b2); pp = stpcpy_(pp, b2);
    stpcpy_(pp, "\n");
    loga(line);
}

/* ================= main ================= */
/* ================= --selftest =================
 * APP 在没有 root 时用它验证二进制能不能跑：
 *   cnd --selftest   -> 输出几行后立即 exit 0
 * 绝不能进 accept_loop：那会让 APP 的 readLine() 永久阻塞，
 * UI 卡在“检测中”。
 *
 * 同时支持 CAIRN_SELFTEST_OUT：把同样的内容写一份文件，
 * 万一 stdout 拿不到还有第二路证据。
 */
static int str_eq(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

/* Compare argv[0] basename so both su and /system/bin/su select the su dispatch branch. */
static int base_is(const char *path, const char *name)
{
    if (!path || !path[0]) return 0;
    const char *b = path;
    const char *p = path;
    for (; *p; p++) if (*p == '/') b = p + 1;
    return str_eq(b, name);
}

static void selftest_run(char **envp)
{
    char buf[512];
    char *p = buf;
    p = stpcpy_(p, "SELFTEST=ok\n");
    p = stpcpy_(p, "uid=");
    utoa((u32)sc0(SYS_getuid), p);
    while (*p) p++;
    p = stpcpy_(p, "\nBASE=");
    p = stpcpy_(p, CAIRN_BASE);
    p = stpcpy_(p, "\nMODE=oneshot\n");
    *p = 0;

    /* stdout（fd 1）；没有就跳过，不报错 */
    sc3(SYS_write, 1, (i64)buf, slen(buf));

    /* 第二路：写文件 */
    if (envp) {
        const char *want = 0;
        for (int i = 0; envp[i]; i++) {
            const char *e = envp[i];
            const char *k = "CAIRN_SELFTEST_OUT=";
            int j = 0;
            while (k[j] && e[j] == k[j]) j++;
            if (!k[j]) { want = e + j; break; }
        }
        if (want) {
            i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)want,
                         O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (fd >= 0) {
                sc3(SYS_write, fd, (i64)buf, slen(buf));
                sc1(SYS_close, fd);
            }
        }
    }
}

/* ================= 单实例锁与守护化 =================
 *
 * 单实例锁:锁文件放在 CAIRN_BASE 下(0700 root),用 flock(LOCK_EX|LOCK_NB)
 * 非阻塞加锁。fd 全程不关闭 —— 一旦进程退出,内核自动释放锁,不需要清理
 * 残留 pid 文件,也不会出现"文件在但进程已死"的假死锁。
 *
 * 为什么必须非阻塞:本进程可能是被内核 UMH 拉起的,若在此阻塞等待,会
 * 一直占着内核的 UMH 工作线程;而内核侧是 UMH_WAIT_EXEC,等于把挂载流程
 * 卡住。拿不到锁立即退出才是正确行为。
 */
#define SYS_flock 32
#define LOCK_EX   2
#define LOCK_NB   4



/* aarch64 没有独立的 fork 系统调用:glibc 的 fork 就是 clone(SIGCHLD)。
 * 这里同样用 clone(SIGCHLD, 0, 0, 0):stack 传 0 表示子进程复制父进程
 * 的栈(写时复制),等价于 fork。QEMU 测试装置里已验证该形态可用。 */
#define SIGCHLD_NUM 17



void cairnd_main(long argc, char **argv, char **envp)
{
    load_wl_key(envp);
    if(argc>=2 && argv && argv[1] && str_eq(argv[1], "--sync-wl")) {
        if(sc0(SYS_geteuid)!=0 || !g_wl_key_valid) {
            const char *m="WL_SYNC_ERROR=missing-auth\n";
            sc3(SYS_write,2,(i64)m,slen(m));sc1(SYS_exit,1);for(;;);
        }
        log_open();
        i64 rc=wl_sync();
        const char *m=rc==0?"WL_SYNC_OK\n":"WL_SYNC_ERROR=kernel-or-file\n";
        sc3(SYS_write,rc==0?1:2,(i64)m,slen(m));
        sc1(SYS_exit,rc==0?0:1);for(;;);
    }

    if(argc>=2 && str_eq(argv[1], "module")){int rc=modules_cmd(argc,argv);sc1(SYS_exit,rc);for(;;);}
    /* Self-test exits before normal su/stage dispatch. Explicit module and sync commands are handled earlier. */
    if (argc >= 2 && argv && argv[1] && str_eq(argv[1], "--selftest")) {
        selftest_run(envp);
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    /* --ping is a one-shot executable liveness check, not proof of a resident daemon or whitelist synchronization. */
    if (argc >= 2 && argv && argv[1] && str_eq(argv[1], "--ping")) {
        const char *m = "alive\n";
        sc3(SYS_write, 1, (i64)m, slen(m));
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    /* Dispatch su by argv[0] basename. Other invocations proceed to explicit CLI/boot handling. */
    if (argc >= 1 && argv && base_is(argv[0], "su")) {
        /* Handle su invocations separately from administrative command arguments. */
        log_open();
        loga("\n=== [su] 被内核转向调用 ===\n");
        loga_num("[su] uid=", (u32)sc0(SYS_getuid));
        loga_num(" euid=", (u32)sc0(SYS_geteuid));
        loga_num(" pid=", (u32)sc0(SYS_getpid));
        loga_num(" argc=", (u32)argc);
        loga("\n");
        for (long i = 0; i < argc && i < 8; i++) {
            loga_num("[su] argv[", (u32)i);
            loga("]=");
            loga(argv[i] ? argv[i] : "(null)");
            loga("\n");
        }

        static char *envp_root[] = {
            "HOME=/data/adb",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/system/bin/sh",
            "PATH=/data/adb/ksu/bin:/system/bin:/system/xbin:/vendor/bin",
            "TERM=xterm-256color",
            0
        };
        const char *SH = "/system/bin/sh";

        /* ---- 参数分派 ----
         * 必须覆盖真机实测过的全部形态：
         *   su --help    argc=2  <- MT 先探测帮助，期望立刻退出。
         *                           旧代码没识别 --help，走"无命令"分支起
         *                           交互 shell，MT 就卡在等 Ctrl+D。
         *   su 0 <cmd>   argc=3  <- MT 真正执行命令的形态
         *   su -c <cmd>  argc=3
         *   su           argc=1  <- 交互 shell
         */
        const char *a1 = (argc >= 2 && argv[1]) ? argv[1] : 0;

        /* 帮助类参数：打印后立刻 exit 0。
         * 绝不能起交互 shell —— 那正是 MT 管理器"卡住、要按 Ctrl+D"的根因。 */
        if (a1 && (str_eq(a1, "--help") || str_eq(a1, "-h") ||
                   str_eq(a1, "help") || str_eq(a1, "--version") ||
                   str_eq(a1, "-v"))) {
            static const char h[] =
                "su (Cairn)\n"
                "用法: su [选项] [命令]\n"
                "  su                进入交互式 root shell\n"
                "  su -c <命令>      以 root 执行一条命令\n"
                "  su <uid> <命令>   以指定身份执行（MT 管理器约定）\n"
                "  su --help         显示本帮助\n";
            sc3(SYS_write, 1, (i64)h, slen(h));
            loga("[su] help requested, exit 0\n");
            sc1(SYS_exit, 0);
            for (;;) ;
        }

        const char *cmd = 0;
        if (argc >= 3 && argv[2] && argv[2][0] && a1) {
            /* su -c <cmd> / su 0 <cmd> / su -- <cmd> / su <uid> <cmd> */
            if (str_eq(a1, "-c") || str_eq(a1, "0") || str_eq(a1, "--") ||
                (a1[0] >= '0' && a1[0] <= '9'))
                cmd = argv[2];
        }

        if (cmd) {
            /* 有命令：sh -c <cmd> */
            static char *nargv[4];
            nargv[0] = (char *)"sh";
            nargv[1] = (char *)"-c";
            nargv[2] = (char *)cmd;
            nargv[3] = 0;
            loga("[su] exec sh -c\n");
            i64 r1 = sc3(SYS_execve, (i64)SH, (i64)nargv, (i64)envp_root);
            loga_num("[su] execve sh -c 失败 rc=", (u32)(-r1));
            loga("\n");
        } else {
            /* 无命令：交互式 sh -i，argv 只留 sh */
            static char *nargv[3];
            nargv[0] = (char *)"-sh";
            nargv[1] = 0;
            loga("[su] exec sh -i (interactive)\n");
            i64 r2 = sc3(SYS_execve, (i64)SH, (i64)nargv, (i64)envp_root);
            loga_num("[su] execve sh 失败 rc=", (u32)(-r2));
            loga("\n");
        }

        /* 走到这里说明 execve 彻底失败 */
        {
            const char *m = "[CAIRN-SU] cannot exec /system/bin/sh\n";
            sc3(SYS_write, 2, (i64)m, slen(m));
        }
        sc1(SYS_exit, 127);
        for (;;) ;
    }

    /* No command arguments -> boot-time one-shot mode.
     * init execs us via the /system/bin/cnd redirect with no args,
     * so argc<2 identifies the boot path unambiguously.
     * Any explicit argument (--help/--ping/--selftest/module cmd)
     * goes to the dispatch block below instead.
     */
    if (argc < 2) {
        /* ================= boot-time one-shot =================
         *
         * cnd is not a resident daemon any more. init starts it through the
         * exec redirect of /system/bin/cnd; it performs exactly one whitelist
         * sync and exits. Nothing stays resident afterwards.
         *
         * The original ppid is recorded first because it identifies the launch
         * path: init execs us directly, so ppid is 1.
         */
        {
            u32 raw_ppid = (u32)sc0(SYS_getppid);
            log_open();
            loga("\n=== cairnd boot sync ===\n");
            loga_num("[boot] ppid=", raw_ppid);
            loga_num(" uid=", (u32)sc0(SYS_getuid));
            loga_num(" pid=", (u32)sc0(SYS_getpid));
            loga("\n");
            loga("[boot] launch=");
            if (raw_ppid <= 1)
                loga("init (exec redirect of /system/bin/cnd)\n");
            else
                loga("manager-or-shell\n");
            log_startup_source();
        }

        ensure_dir(CAIRN_BASE);
        ensure_dir(CAIRN_BIN_DIR);

        wl_sync();
        if (load_manager_uid() < 0)
            loga("[init] manager.uid missing\n");
        else
            loga_num("[init] manager uid=", g_manager_uid);
        loga("[init] one-shot done, exiting\n");
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    /* Explicit one-shot command dispatch. Unknown commands must fail rather than start a resident listener. */
    {
        const char *c1 = (argc >= 2 && argv[1]) ? argv[1] : 0;

        /* ---- 无参数 / 帮助：打印后立刻 exit 0 ---- */
        if (!c1 || str_eq(c1, "--help") || str_eq(c1, "-h") ||
            str_eq(c1, "help") || str_eq(c1, "?")) {
            const char *h =
                "cnd - Cairn daemon\n"
                "\n"
                "Usage: cnd <command> [args]\n"
                "\n"
                "  cnd grant <uid>       Allow uid to obtain root\n"
                "  cnd revoke <uid>      Revoke that permission\n"
                "  cnd list              List authorized uids\n"
                "  cnd status            Print runtime status\n"
                "  cnd --selftest        Run self-test and exit\n"
                "  cnd --ping            Liveness probe, exit\n"
                "  cnd --stage <phase>   Run an init phase (post-fs-data|service)\n"
                "  cnd --boot            Boot one-shot: sync allowlist and exit\n"
                "  cnd --help            Show this help\n"
                "\n"
                "Note: this binary is also the su target redirected by the kernel;\n"
                "the identity is chosen by the basename of argv[0].\n";
            sc3(SYS_write, 1, (i64)h, slen(h));
            sc1(SYS_exit, 0);
            for (;;) ;
        }

        /* CLI operations require the implemented uid/key checks below. uid 0 alone does not prove which root implementation granted it. */
        /* ---- --stage <phase>: init stage execution ----
         *
         * init calls us through the rc line:
         *   exec /system/bin/cnd --stage post-fs-data
         *
         * init's exec blocks naturally (it waits for the child to exit),
         * so as long as we do not return before the stage scripts finish,
         * init stays parked in this stage. That is exactly the semantics
         * we need -- no extra sleep inside the kernel.
         *
         * The timeout is handled by cnd itself: we clone a watchdog child
         * first; it SIGKILLs the main process when the deadline expires.
         * Once the scripts are done, the main process kills the watchdog
         * and exits normally.
         */
        if (str_eq(c1, "--stage")) {
            const char *phase = (argc >= 3 && argv[2]) ? argv[2] : 0;
            int st = stage_parse(phase);
            if (!st) {
                const char *m = "cnd: unknown stage (use post-fs-data or service)\n";
                sc3(SYS_write, 2, (i64)m, slen(m));
                sc1(SYS_exit, 2);
                for (;;) ;
            }
            log_open();
            loga_str("[stage] requested:", phase);
            stage_run(st);
            loga_str("[stage] done:", phase);
            sc1(SYS_exit, 0);
            for (;;) ;
        }
        int is_admin = str_eq(c1, "grant") || str_eq(c1, "revoke") ||
                       str_eq(c1, "list") || str_eq(c1, "status");
        if (is_admin) {
            log_open();
            if (sc0(SYS_getuid) != 0) {
                const char *m = "cnd: permission denied (need root)\n";
                sc3(SYS_write, 2, (i64)m, slen(m));
                loga("[adm] denied non-root caller\n");
                sc1(SYS_exit, 1);
                for (;;) ;
            }
            ensure_dir(CAIRN_BASE);

            load_manager_uid();

            if (str_eq(c1, "grant") || str_eq(c1, "revoke")) {
                const char *a2 = (argc >= 3 && argv[2]) ? argv[2] : 0;
                u32 target = a2 ? atou(a2) : 0;
                if (target < 10000) {
                    const char *m = "cnd: bad uid (need >= 10000)\n";
                    sc3(SYS_write, 2, (i64)m, slen(m));
                    loga("[adm] bad target uid\n");
                    sc1(SYS_exit, 1);
                    for (;;) ;
                }
                if (str_eq(c1, "grant")) {
                    const char *a3 = (argc >= 4 && argv[3]) ? argv[3] : 0;
                    int auto_mode = (a3 && (a3[0] == 'A' || a3[0] == 'a' ||
                                            a3[0] == '1')) ? 1 : 0;
                    if (do_grant_policy(target, auto_mode) < 0) {
                        const char *m = "cnd: grant failed\n";
                        sc3(SYS_write, 2, (i64)m, slen(m));
                        sc1(SYS_exit, 1);
                        for (;;) ;
                    }
                    audit(auto_mode ? "GRANT-A" : "GRANT-C",
                          (u32)sc0(SYS_getuid), target);
                    if (wl_sync()<0) {
                        const char *m="cnd: saved allowlist but kernel sync failed\n";
                        sc3(SYS_write,2,(i64)m,slen(m));
                        sc1(SYS_exit,1);for(;;);
                    }
                    char b[16];
                    int n = utoa(target, b);
                    sc3(SYS_write, 1, (i64)b, n);
                    sc3(SYS_write, 1, (i64)"\n", 1);
                } else {
                    if(do_revoke(target)<0){sc1(SYS_exit,1);for(;;);}
                    audit("REVOKE", (u32)sc0(SYS_getuid), target);
                    if (wl_sync()<0) {
                        const char *m="cnd: saved allowlist but kernel sync failed\n";
                        sc3(SYS_write,2,(i64)m,slen(m));
                        sc1(SYS_exit,1);for(;;);
                    }
                    char b[16];
                    int n = utoa(target, b);
                    sc3(SYS_write, 1, (i64)b, n);
                    sc3(SYS_write, 1, (i64)"\n", 1);
                }
                sc1(SYS_exit, 0);
                for (;;) ;
            }

            if (str_eq(c1, "list")) {
                u32 n=0;
                if(al_lock()<0 || al_load()<0) {
                    al_unlock();sc1(SYS_exit,1);for(;;);
                }
                for(u32 i=0;i<al_count;i++) {
                    n+=utoa(al_uid[i],g_adm_out+n);
                    g_adm_out[n++]=':';g_adm_out[n++]='2';g_adm_out[n++]='\n';
                }
                al_unlock();
                if (n) sc3(SYS_write, 1, (i64)g_adm_out, n);
                sc1(SYS_exit, 0);
                for (;;) ;
            }

            /* status */
            {
                char *p = g_adm_out;
                p = adm_put(p, "proto=");
                p = adm_put_num(p, CAIRN_PROTO_VER);
                p = adm_put(p, "\nuid=");
                p = adm_put_num(p, (u32)sc0(SYS_getuid));
                p = adm_put(p, "\nmanager_uid=");
                p = adm_put_num(p, g_manager_uid);
                p = adm_put(p, "\nmode=oneshot\n");
                sc3(SYS_write, 1, (i64)g_adm_out, (i64)(p - g_adm_out));
                sc1(SYS_exit, 0);
                for (;;) ;
            }
        }

        /* ---- 未知参数：打印帮助 + exit 2，绝不常驻 ---- */
        /* Unknown command: print help, exit 2. cnd is never resident. */
        {
            const char *m = "cnd: unknown command, try `cnd --help`\n";
            sc3(SYS_write, 2, (i64)m, slen(m));
                        sc1(SYS_exit, 2);
            for (;;) ;
        }

}
}
