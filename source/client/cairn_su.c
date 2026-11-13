/* LEGACY / NOT USED BY V1.0.1 APP: socket client retained for historical reference.
 * Current kernel su redirects to cnd; no corresponding resident listener is started. */
/*
 * cairn_su —— 第三方 APP 调用的 su 客户端
 *
 * 它是什么：一个纯粹的 socket 客户端。里面【没有任何密钥】。
 * 它的存在意义只是提供一个文件名，让 APP 里硬编码的
 * execve("/system/bin/su") 有个落点。
 *
 * 为什么没有密钥也不怕：
 *   真正的判定在 cairnd 里，靠 SO_PEERCRED 拿内核填的对端 uid。
 *   客户端就算被反编译、被改成随便发指令，也只会以【它自己的 uid】
 *   去请求。未勾选就 EPERM，勾选了还要用户点确认。
 *   所以这个二进制可以公开，可以被任何人拿去跑。
 *
 * 与标准 su 的差异（必须让用户知道）：
 *   1) 非流式：命令跑完才一次性回传 stdout/stderr。所以不适合
 *      logcat -f、tail -f 这类永不结束的命令。
 *   2) 不支持交互式（没有 pty）。`su` 不带参数会报错而不是给你 shell。
 *   3) exec 出的子进程 SELinux 域仍是 u:r:untrusted_app（我们从不碰
 *      cred->security），所以是 uid=0 但域不变。文件操作全通，
 *      binder 调 system_server 受限。这是 Cairn 的性质，不是 bug。
 */
#include "../daemon/cairn_proto.h"
#include "../daemon/cairn_sys.h"

/* 候选 socket 路径，按顺序试 */
static const char *SOCK_CANDIDATES[] = {
    CAIRN_SOCK_TMP,
    CAIRN_SOCK_ADB,
    0
};

static void wr(i64 fd, const char *s, int n)
{
    int off = 0;
    while (off < n) {
        i64 r = sc3(SYS_write, fd, (i64)(s + off), (i64)(n - off));
        if (r <= 0) return;
        off += (int)r;
    }
}

static void err(const char *s) { wr(2, s, slen(s)); }

/* 连接任意一个可用 socket，返回 fd 或 -1 */
static i64 connect_any(void)
{
    for (int i = 0; SOCK_CANDIDATES[i]; i++) {
        const char *path = SOCK_CANDIDATES[i];
        i64 fd = sc3(SYS_socket, AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) continue;

        struct sockaddr_un sa;
        for (unsigned k = 0; k < sizeof(sa); k++) ((char *)&sa)[k] = 0;
        sa.sun_family = AF_UNIX;
        stpcpy_(sa.sun_path, path);

        if (sc3(SYS_connect, fd, (i64)&sa, sizeof(sa)) == 0)
            return fd;

        sc1(SYS_close, fd);
    }
    return -1;
}

/* 从 argv 里解析出要执行的命令，写入 out（上限 CAIRN_MAX_CMD）。
 * 返回 0 成功，-1 无法解析。
 *
 * 支持的形态：
 *   su -c "cmd"                 （最常见）
 *   su -c cmd
 *   su 0 cmd arg1 arg2          （uid/用户名 后跟命令）
 *   su root -c "cmd"
 *   su --command "cmd"
 *   su                          （无参数 -> 报错，不支持交互）
 */
static int build_cmd(int argc, char **argv, char *out)
{
    int i = 1;

    /* 跳过前置选项 -l/-/--login */
    while (i < argc) {
        const char *a = argv[i];
        if ((a[0] == '-' && a[1] == 0) ||
            seqs(a, "-l") || seqs(a, "--login")) { i++; continue; }
        break;
    }

    /* 找 -c / --command */
    for (int k = i; k < argc; k++) {
        const char *a = argv[k];
        if (seqs(a, "-c") || seqs(a, "--command")) {
            if (k + 1 >= argc) return -1;
            /* -c 之后的所有参数拼成命令 */
            char *p = out;
            for (int j = k + 1; j < argc; j++) {
                if (j > k + 1) { *p++ = ' '; }
                p = stpcpy_(p, argv[j]);
                if ((int)(p - out) > CAIRN_MAX_CMD - 8) return -1;
            }
            return 0;
        }
        /* -cCMD 形式 */
        if (a[0] == '-' && a[1] == 'c' && a[2]) {
            stpcpy_(out, a + 2);
            return 0;
        }
    }

    /*
     * 没有 -c。可能形如 `su 0 cmd args`。
     * 第一个非选项参数若是 0 或 root，跳过它。
     */
    int start = i;
    if (start < argc) {
        const char *a = argv[start];
        if (seqs(a, "0") || seqs(a, "root") || seqs(a, "2000") || seqs(a, "shell"))
            start++;
    }
    if (start >= argc) return -1;   /* 只有 su 或 su 0，不支持交互 */

    char *p = out;
    for (int j = start; j < argc; j++) {
        if (j > start) *p++ = ' ';
        p = stpcpy_(p, argv[j]);
        if ((int)(p - out) > CAIRN_MAX_CMD - 8) return -1;
    }
    return 0;
}

void su_main(int argc, char **argv)
{
    static char cmd[CAIRN_MAX_CMD + 1];
    if (build_cmd(argc, argv, cmd) < 0) {
        err("su: 不支持交互式 shell（Cairn 的 su 是转发式客户端）\n");
        err("用法: su -c \"command\"    或    su 0 command\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    }

    i64 fd = connect_any();
    if (fd < 0) {
        /* 守护进程没起：这是环境问题，不是授权问题，必须能区分 */
        err("su: 无法连接 cairnd（守护进程未运行？）\n");
        err("    尝试过: " CAIRN_SOCK_TMP " 和 " CAIRN_SOCK_ADB "\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    }

    struct cairn_req req;
    req.magic = CAIRN_PROTO_MAGIC;
    req.type = CAIRN_REQ_EXEC;
    req.cmdlen = (unsigned int)slen(cmd);
    req.flags = 1;
    req.seq = (unsigned int)sc0(SYS_getpid);

    if (sc3(SYS_write, fd, (i64)&req, sizeof(req)) != (i64)sizeof(req)) {
        err("su: 发送请求头失败\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    }
    wr(fd, cmd, (int)req.cmdlen);

    struct cairn_rsp rsp;
    int got = 0;
    while (got < (int)sizeof(rsp)) {
        i64 r = sc3(SYS_read, fd, (i64)((char *)&rsp + got),
                    (i64)((int)sizeof(rsp) - got));
        if (r <= 0) {
            err("su: 读取应答失败\n");
            sc1(SYS_exit, 1);
            for (;;) ;
        }
        got += (int)r;
    }

    if (rsp.magic != CAIRN_PROTO_MAGIC) {
        err("su: 协议不匹配\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    }

    switch (rsp.code) {
    case CAIRN_RSP_EPERM:
        err("su: 权限被拒绝（这个 APP 未在 Cairn 管理器中勾选）\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    case CAIRN_RSP_EDENIED:
        err("su: 用户拒绝本次请求\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    case CAIRN_RSP_ETIMEOUT:
        err("su: 等待确认超时\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    case CAIRN_RSP_EBADREQ:
        err("su: 请求格式错误\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    case CAIRN_RSP_EINTERNAL:
        err("su: 守护进程内部错误\n");
        sc1(SYS_exit, 1);
        for (;;) ;
    default:
        break;
    }

    /* 回传数据 */
    static char buf[4096];
    unsigned int left = rsp.outlen;
    while (left) {
        int want = (int)(left < sizeof(buf) ? left : sizeof(buf));
        i64 r = sc3(SYS_read, fd, (i64)buf, (i64)want);
        if (r <= 0) break;
        wr(1, buf, (int)r);
        left -= (unsigned int)r;
    }
    left = rsp.errlen;
    while (left) {
        int want = (int)(left < sizeof(buf) ? left : sizeof(buf));
        i64 r = sc3(SYS_read, fd, (i64)buf, (i64)want);
        if (r <= 0) break;
        wr(2, buf, (int)r);
        left -= (unsigned int)r;
    }

    sc1(SYS_close, fd);
    sc1(SYS_exit, (i64)rsp.exit_code);
    for (;;) ;
}