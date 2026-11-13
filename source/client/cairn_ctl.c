/* LEGACY / NOT USED BY V1.0.1 APP: socket client retained for historical reference.
 * Current kernel su redirects to cnd; no corresponding resident listener is started. */
/*
 * cairnctl —— 命令行管理工具（真机自测用）
 *
 * 用途：在管理器 APP 的界面做出来之前，先验证整条链路。
 * 因为它自己不是管理器 uid，所以 GRANT/REVOKE 会被 cairnd 拒绝
 * （这正是设计意图）。它主要用来 PING / STATUS / LIST 和验证
 * 拒绝路径是否按预期工作。
 *
 * 用法：
 *   cairnctl ping
 *   cairnctl status
 *   cairnctl list
 *   cairnctl grant <uid>
 *   cairnctl revoke <uid>
 *   cairnctl exec <command...>
 */
#include "../daemon/cairn_proto.h"
#include "../daemon/cairn_sys.h"

static void wr(i64 fd, const char *s, int n)
{
    int off = 0;
    while (off < n) {
        i64 r = sc3(SYS_write, fd, (i64)(s + off), (i64)(n - off));
        if (r <= 0) return;
        off += (int)r;
    }
}
static void out(const char *s) { wr(1, s, slen(s)); }
static void outs(const char *a, const char *b) { out(a); out(b); out("\n"); }

static i64 connect_any(void)
{
    const char *cand[3];
    cand[0] = CAIRN_SOCK_TMP;
    cand[1] = CAIRN_SOCK_ADB;
    cand[2] = 0;
    for (int i = 0; cand[i]; i++) {
        i64 fd = sc3(SYS_socket, AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) continue;
        struct sockaddr_un sa;
        for (unsigned k = 0; k < sizeof(sa); k++) ((char *)&sa)[k] = 0;
        sa.sun_family = AF_UNIX;
        stpcpy_(sa.sun_path, cand[i]);
        if (sc3(SYS_connect, fd, (i64)&sa, sizeof(sa)) == 0) return fd;
        sc1(SYS_close, fd);
    }
    return -1;
}

/* 发一个请求并打印结果。返回应答 code 或 -1 */
static int do_req(u32 type, const char *payload)
{
    i64 fd = connect_any();
    if (fd < 0) { out("无法连接 cairnd\n"); return -1; }

    int plen = payload ? slen(payload) : 0;
    struct cairn_req req;
    req.magic = CAIRN_PROTO_MAGIC;
    req.type = type;
    req.cmdlen = (unsigned int)plen;
    req.flags = 0;
    req.seq = (unsigned int)sc0(SYS_getpid);

    if (sc3(SYS_write, fd, (i64)&req, sizeof(req)) != (i64)sizeof(req)) {
        out("发请求头失败\n"); sc1(SYS_close, fd); return -1;
    }
    if (plen) wr(fd, payload, plen);

    struct cairn_rsp rsp;
    int got = 0;
    while (got < (int)sizeof(rsp)) {
        i64 r = sc3(SYS_read, fd, (i64)((char *)&rsp + got),
                    (i64)((int)sizeof(rsp) - got));
        if (r <= 0) { out("读应答失败\n"); sc1(SYS_close, fd); return -1; }
        got += (int)r;
    }

    const char *cname;
    switch (rsp.code) {
    case CAIRN_RSP_OK:        cname = "OK";        break;
    case CAIRN_RSP_EPERM:     cname = "EPERM";     break;
    case CAIRN_RSP_EDENIED:   cname = "EDENIED";   break;
    case CAIRN_RSP_ETIMEOUT:  cname = "ETIMEOUT";  break;
    case CAIRN_RSP_EBADREQ:   cname = "EBADREQ";   break;
    case CAIRN_RSP_EINTERNAL: cname = "EINTERNAL"; break;
    default:                  cname = "UNKNOWN";   break;
    }
    outs("code = ", cname);
    /* PING/VERSION 复用 exit_code 字段回传协议版本，不是进程退出码，
     * 打印成 "exit =" 会误导，这里分开标注。 */
    if (rsp.code == CAIRN_RSP_OK) {
        if (type == CAIRN_REQ_PING || type == CAIRN_REQ_VERSION) {
            out("proto = ");
            char b[12]; utoa((u32)rsp.exit_code, b); out(b); out("\n");
        } else if (rsp.exit_code) {
            out("exit = ");
            char b[12]; utoa((u32)rsp.exit_code, b); out(b); out("\n");
        }
    }

    static char buf[2048];
    u32 left = rsp.outlen;
    while (left) {
        int want = (int)(left < sizeof(buf) ? left : sizeof(buf));
        i64 r = sc3(SYS_read, fd, (i64)buf, (i64)want);
        if (r <= 0) break;
        wr(1, buf, (int)r);
        left -= (u32)r;
    }
    left = rsp.errlen;
    while (left) {
        int want = (int)(left < sizeof(buf) ? left : sizeof(buf));
        i64 r = sc3(SYS_read, fd, (i64)buf, (i64)want);
        if (r <= 0) break;
        wr(2, buf, (int)r);
        left -= (u32)r;
    }

    sc1(SYS_close, fd);
    return (int)rsp.code;
}

void su_main(int argc, char **argv)
{
    if (argc < 2) {
        out("用法: cairnctl ping|status|list|grant <uid>|revoke <uid>|exec <cmd>\n");
        sc1(SYS_exit, 1); for (;;) ;
    }

    const char *sub = argv[1];

    if (seqs(sub, "ping")) {
        int c = do_req(CAIRN_REQ_PING, 0);
        sc1(SYS_exit, c == CAIRN_RSP_OK ? 0 : 1); for (;;) ;
    }
    if (seqs(sub, "status")) {
        int c = do_req(CAIRN_REQ_STATUS, 0);
        sc1(SYS_exit, c == CAIRN_RSP_OK ? 0 : 1); for (;;) ;
    }
    if (seqs(sub, "list")) {
        int c = do_req(CAIRN_REQ_LIST, 0);
        sc1(SYS_exit, c == CAIRN_RSP_OK ? 0 : 1); for (;;) ;
    }
    if ((seqs(sub, "grant") || seqs(sub, "revoke"))) {
        if (argc < 3) { out("缺少 uid\n"); sc1(SYS_exit, 1); for (;;) ; }
        u32 type = seqs(sub, "grant") ? CAIRN_REQ_GRANT : CAIRN_REQ_REVOKE;
        int c = do_req(type, argv[2]);
        sc1(SYS_exit, c == CAIRN_RSP_OK ? 0 : 1); for (;;) ;
    }
    if (seqs(sub, "exec")) {
        if (argc < 3) { out("缺少命令\n"); sc1(SYS_exit, 1); for (;;) ; }
        char cmd[CAIRN_MAX_CMD + 1];
        char *p = cmd;
        for (int i = 2; i < argc; i++) {
            if (i > 2) *p++ = ' ';
            p = stpcpy_(p, argv[i]);
            if ((int)(p - cmd) > CAIRN_MAX_CMD - 8) break;
        }
        int c = do_req(CAIRN_REQ_EXEC, cmd);
        sc1(SYS_exit, c == CAIRN_RSP_OK ? 0 : 1); for (;;) ;
    }

    out("未知子命令\n");
    sc1(SYS_exit, 1);
    for (;;) ;
}