/* Module boot-stage execution. post-fs-data is synchronous; service is launched through init exec_background.
 * Commit modules_update at post-fs-data before scanning active modules. Honor disable/remove and boot-id pending markers.
 * Scripts execute via the bundled shell with PATH/CAIRN environment. Root scripts are not sandboxed.
 * Stage code itself adds no mounts or SELinux policy mutations. */

#ifndef CAIRN_STAGE_C
#define CAIRN_STAGE_C

/* str_eq is defined later in cairnd.c; declare it here. */
static int str_eq(const char *a, const char *b);

/* ---- 信号号 ---- */
#ifndef SIGKILL_
#define SIGKILL_ 9
#endif
#ifndef SIGCHLD_
#define SIGCHLD_ 17
#endif

/* ---- 模块与脚本路径 ---- */
#define CAIRN_MODULES_DIR   "/data/adb/modules"
#define CAIRN_SH_PATH       "/data/adb/bin/sh"

/* ---- 阶段枚举 ---- */
#define STAGE_POST_FS_DATA  1
#define STAGE_SERVICE       2

/* 各阶段超时（秒）。超时后定时器子进程 SIGKILL 主进程。 */
#define STAGE_TIMEOUT_POST_FS_DATA  40
#define STAGE_TIMEOUT_SERVICE       0   /* 0 = 不限时 */

/* 解析阶段名 -> 阶段号；不认识返回 0 */
static int stage_parse(const char *name)
{
    if (!name) return 0;
    if (str_eq(name, "post-fs-data")) return STAGE_POST_FS_DATA;
    if (str_eq(name, "service"))      return STAGE_SERVICE;
    return 0;
}

/* 该阶段要跑哪个脚本文件名 */
static const char *stage_script_name(int stage)
{
    if (stage == STAGE_POST_FS_DATA) return "post-fs-data.sh";
    if (stage == STAGE_SERVICE)      return "service.sh";
    return 0;
}

/* 该阶段超时秒数 */
static u32 stage_timeout(int stage)
{
    if (stage == STAGE_POST_FS_DATA) return STAGE_TIMEOUT_POST_FS_DATA;
    if (stage == STAGE_SERVICE)      return STAGE_TIMEOUT_SERVICE;
    return 0;
}

/*
 * 定时器子进程：睡 sec 秒，然后 SIGKILL 父进程。
 * 用 clone(SIGCHLD) 而不是 fork —— arm64 没有 fork 系统调用。
 * 返回子进程 pid 给父进程用（用来取消定时器）。
 */
static long stage_arm_timer(u32 sec)
{
    long pid = sc6(SYS_clone, (i64)SIGCHLD_, 0, 0, 0, 0, 0);
    if (pid < 0) return -1;
    if (pid == 0) {
        /* ---- 子进程：定时器 ---- */
        struct timespec_ ts;
        ts.tv_sec  = (long)sec;
        ts.tv_nsec = 0;
        /* CLOCK_REALTIME = 0，TIMER_ABSTIME 不设 -> 相对睡眠 */
        sc4(SYS_clock_nanosleep, 0, 0, (i64)&ts, 0);
        /* 醒来后杀父进程 */
        sc2(SYS_kill, (i64)sc0(SYS_getppid), (i64)SIGKILL_);
        /* 父进程若已死，这里无副作用 */
        sc1(SYS_exit, 0);
        for (;;) ;
    }
    return pid;   /* 父进程拿到子进程 pid */
}

/* 取消定时器：SIGKILL 定时器子进程 */
static void stage_cancel_timer(long timer_pid)
{
    if (timer_pid > 0)
        sc2(SYS_kill, (i64)timer_pid, (i64)SIGKILL_);
}

/*
 * 等待子进程退出，返回其退出码。
 * 无 WNOHANG = 阻塞等待。子进程已被 kill 时返回它被杀的信号值。
 */
static int stage_wait(long pid)
{
    int status = 0;
    sc4(SYS_wait4, (i64)pid, (i64)&status, 0, 0);
    /* WIFEXITED: (status & 0x7f) == 0 ; 退出码 = (status >> 8) & 0xff */
    if ((status & 0x7f) == 0)
        return (int)((status >> 8) & 0xff);
    /* 被信号杀死：status & 0x7f = 信号号 */
    return 128 + (int)(status & 0x7f);
}

/*
 * 执行一个脚本（已 fork 出子进程），返回退出码。
 * 用自带 sh，不依赖系统 shell。
 */
static int stage_exec_script(const char *script_path)
{
    long pid = sc6(SYS_clone, (i64)SIGCHLD_, 0, 0, 0, 0, 0);
    if (pid < 0) return -1;
    if (pid == 0) {
        /* ---- 子进程：exec sh 跑脚本 ---- */
        char *argv[4];
        argv[0] = (char *)CAIRN_SH_PATH;
        argv[1] = (char *)script_path;
        argv[2] = 0;
        argv[3] = 0;
        char *envp[4];
        envp[0] = (char *)"PATH=/data/adb/bin:/system/bin:/system/xbin";
        envp[1] = (char *)"CAIRN=true";
        envp[2] = (char *)"CAIRN_API=1";
        envp[3] = 0;
        sc3(SYS_execve, (i64)CAIRN_SH_PATH, (i64)argv, (i64)envp);
        /* exec 失败（自带 sh 不存在等） */
        sc1(SYS_exit, 127);
        for (;;) ;
    }
    return stage_wait(pid);
}

/* Scan active module directories and execute eligible scripts. modules_commit may rename staged/active trees before this scan; scripts may write files. */
static int stage_run_modules(int stage)
{
    const char *script_name = stage_script_name(stage);
    if (!script_name) return 0;

    char dirpath[256];
    char *p = dirpath;
    p = stpcpy_(p, CAIRN_MODULES_DIR);
    stpcpy_(p, "/");

    int fd = sc3(SYS_openat, AT_FDCWD, (i64)dirpath, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        loga_str("[stage] modules dir missing:", dirpath);
        return 0;
    }

    int ran = 0;
    struct dirent64_ *de;
    /* getdents64 的 buffer 大小必须是 8 的倍数 */
    long nread;
    /* 用栈上缓冲：dbuf 需按 8 对齐 */
    union { struct dirent64_ d; char raw[512]; } u;
    (void)de;

    for (;;) {
        nread = sc3(SYS_getdents64, fd, (i64)u.raw, 512);
        if (nread <= 0) break;
        long off = 0;
        while (off < nread) {
            de = (struct dirent64_ *)(u.raw + off);
            const char *name = de->d_name;
            off += de->d_reclen;
            if (!name || !name[0]) continue;
            if (name[0]==46 || !mod_id(name)) continue;
            if(mod_pending(name)){loga_str("[stage] pending reboot, skipped:",name);continue;}

            /* 拼出模块目录 */
            char moddir[320];
            char *q = moddir;
            q = stpcpy_(q, CAIRN_MODULES_DIR);
            q = stpcpy_(q, "/");
            q = stpcpy_(q, name);

            /* 标记文件检查：disable 存在 = 跳过 */
            char flagpath[352];
            char *r = flagpath;
            r = stpcpy_(r, moddir);
            r = stpcpy_(r, "/disable");
            int dflag = sc3(SYS_openat, AT_FDCWD, (i64)flagpath, O_RDONLY);
            if (dflag >= 0) {
                sc1(SYS_close, dflag);
                loga_str("[stage] module disabled, skipped:", name);
                continue;
            }

            r=stpcpy_(flagpath,moddir);stpcpy_(r,"/remove");
            if(mod_exists(flagpath))continue;
            /* 脚本路径 */
            char script[384];
            char *s = script;
            s = stpcpy_(s, moddir);
            s = stpcpy_(s, "/");
            s = stpcpy_(s, script_name);

            int sfd = sc3(SYS_openat, AT_FDCWD, (i64)script, O_RDONLY);
            if (sfd < 0) continue;      /* 没这个脚本 -> 跳过 */
            sc1(SYS_close, sfd);

            loga_str("[stage] running:", script);
            int rc = stage_exec_script(script);
            ran++;
            if (rc == 0) {
                loga_str("[stage] script OK:", name);
            } else {
                loga_num("[stage] script FAILED rc=", (u32)rc);
                loga_str("[stage] module:", name);
            }
        }
    }
    sc1(SYS_close, fd);
    loga_num("[stage] scripts executed:", (u32)ran);
    return ran;
}

/*
 * 阶段模式主入口。
 *
 * 流程：
 *   1. 若该阶段有超时 -> clone 定时器子进程
 *   2. 执行该阶段所有模块脚本（post-fs-data 才阻塞 init）
 *   3. 取消定时器
 *   4. 返回
 *
 * 注意：这里不自己 sleep 阻塞 init。init 的 exec 本身会等我们退出，
 * 所以只要我们不提前退出，init 就停在当前阶段 —— 这正是要的语义。
 */
static int stage_run(int stage)
{
    if(stage==STAGE_POST_FS_DATA)modules_commit();
    u32 timeout = stage_timeout(stage);
    long timer_pid = -1;

    loga_str("[stage] entering:", stage_script_name(stage) ? stage_script_name(stage) : "?");

    if (timeout > 0) {
        timer_pid = stage_arm_timer(timeout);
        if (timer_pid < 0)
            loga_str("[stage] timer clone failed:", "no watchdog");
        else
            loga_num("[stage] watchdog armed, timeout sec=", timeout);
    }

    int ran = stage_run_modules(stage);

    if (timer_pid > 0) {
        stage_cancel_timer(timer_pid);
        loga_str("[stage] watchdog cancelled:", "done");
    }

    loga_num("[stage] finished, scripts=", (u32)ran);
    return 0;
}

#endif /* CAIRN_STAGE_C */
