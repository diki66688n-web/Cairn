/*
 * Cairn native —— fork 子进程提权模型
 *
 * 真机实测得出的硬约束：
 *   1) app 进程自己提权到 uid 0 后，system_server 的身份校验全崩
 *      （实测 Toast 报 "Package com.cairn.manager is not owned by uid 0"），
 *      所以 app 进程永不提权，只在 clone 出的子进程里提权。
 *
 *   2) 子进程提权后【不能 execve】。提权只改 cred，不改 SELinux domain：
 *      子进程仍是 u:r:untrusted_app:s0。内核 hook 只覆盖
 *      security_inode_* / security_file_* / security_mmap_file 这些
 *      「文件访问层」，被早退 return 0 后读写确实放行；但 exec 走的是
 *      security_bprm_check 与 process:transition，不在 hook 列表里。
 *      因此子进程不 exec shell，改用内置 syscall 解释器直接完成操作。
 *
 * 脚本格式（一行一条，动词大写，参数以空格分隔）：
 *   ECHO   <text>
 *   CAT    <path>              以 hex 输出（最多 4096 字节）
 *   TEXT   <path>              以文本输出（不可打印字符替换为 .）
 *   WRITE  <path> <hex>        截断写入
 *   APPEND <path> <hex>        追加写
 *   LS     <path>              列目录
 *   STAT   <path>              ino/mode/uid/gid/size/mtime
 *   MKDIR  <path>
 *   RM     <path>
 *   MV     <old> <new>
 *   UID                        子进程 uid/euid
 *   EXEC   <cmd>               尝试 execve（取证用，可能被 SELinux 拒）
 */
#include <stdint.h>

#define CAIRN_MAGIC 0x43414952UL

#define SYS_openat     56
#define SYS_close      57
#define SYS_getdents64 61
#define SYS_read       63
#define SYS_write      64
#define SYS_mkdirat    34
#define SYS_unlinkat   35
#define SYS_dup3       24
#define SYS_renameat   38
#define SYS_newfstatat 79
#define SYS_execve     221
#define SYS_clone      220
#define SYS_wait4      260
#define SYS_exit       93
#define SYS_getuid     174
#define SYS_geteuid    175

#define AT_FDCWD   (-100)
#define O_RDONLY   0
#define O_WRONLY   1
#define O_CREAT    0100
#define O_TRUNC    01000
#define O_APPEND   02000
/*
 * arm64 用 asm-generic/fcntl.h：
 *   O_DIRECTORY = 040000 = 0x4000
 *   O_NOFOLLOW  = 0100000 = 0x8000
 *   O_DIRECT    = 0200000 = 0x10000   <- 之前误用这个，开目录拿 -22(EINVAL)
 *   O_LARGEFILE = 0400000 = 0x20000
 */
#define O_DIRECTORY 0x4000

static long sc0(long n)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = 0;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}

static long sc1(long n, long a)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}

static long sc3(long n, long a, long b, long c)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static long sc4(long n, long a, long b, long c, long d)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3)
                     : "memory");
    return x0;
}

/*
 * clone 在 arm64 上是 5 参数：clone(flags, stack, parent_tid, child_tid, tls)。
 * 只传前两个会让 x2/x3/x4 残留旧值，内核可能当成合法指针去写 —— 必须清零。
 */
static long sc5(long n, long a, long b, long c, long d, long e)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    return x0;
}

/* ---------------- output helpers ---------------- */

static int slen(const char *s) { int n = 0; while (s[n]) n++; return n; }

static void emit(int fd, const char *s)
{
    int n = slen(s);
    while (n > 0) {
        long w = sc3(SYS_write, fd, (long)s, n);
        if (w <= 0) break;
        s += w; n -= (int)w;
    }
}

static void emitc(int fd, char c) { sc3(SYS_write, fd, (long)&c, 1); }

static void emitdec(int fd, long v)
{
    char b[24];
    int i = 0;
    unsigned long u;
    if (v < 0) { emitc(fd, '-'); u = (unsigned long)(-v); }
    else u = (unsigned long)v;
    if (u == 0) { emitc(fd, '0'); return; }
    while (u > 0 && i < 23) { b[i++] = (char)('0' + (u % 10)); u /= 10; }
    while (i > 0) emitc(fd, b[--i]);
}

static void emithex(int fd, unsigned long v, int digits)
{
    const char *H = "0123456789abcdef";
    char b[17];
    int i;
    for (i = 0; i < digits; i++) { b[i] = H[v & 0xf]; v >>= 4; }
    while (i > 0) emitc(fd, b[--i]);
}

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* ---------------- auth ---------------- */

static long raw_prctl_auth(uint64_t k0, uint64_t k1)
{
    register long x8 __asm__("x8") = 167;
    register long x0 __asm__("x0") = (long)CAIRN_MAGIC;
    register long x1 __asm__("x1") = (long)k0;
    register long x2 __asm__("x2") = (long)k1;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    return x0;
}

long cairn_authenticate(uint64_t k0, uint64_t k1)
{
    long r = raw_prctl_auth(k0, k1);
    if (r == 0) return 0;
    if (r == -1 || (unsigned long)r == 0xFFFFFFFFUL) return -1;
    return -2;
}

long cairn_probe(void)
{
    register long x8 __asm__("x8") = 167;
    register long x0 __asm__("x0") = (long)CAIRN_MAGIC;
    register long x1 __asm__("x1") = 0x1111111111111111L;
    register long x2 __asm__("x2") = 0x2222222222222222L;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    if (x0 == -1 || (unsigned long)x0 == 0xFFFFFFFFUL) return 1;
    if (x0 == -22) return 0;
    return -3;
}

long cairn_getuid(void) { return sc0(SYS_getuid); }

/* ---------------- child command interpreter ---------------- */

static int g_out  = 1;
static int g_fail = 0;
static char g_ibuf[4096];

static int op_rd(const char *p)
{
    return (int)sc4(SYS_openat, AT_FDCWD, (long)p, O_RDONLY, 0);
}

static void perr(const char *what, const char *p, long rc)
{
    emit(g_out, "E: ");
    emit(g_out, what);
    if (p) { emitc(g_out, ' '); emit(g_out, p); }
    emit(g_out, " -> ");
    emitdec(g_out, rc);
    emitc(g_out, '\n');
    g_fail = 1;
}

static void cmd_cat(char *path, int as_text)
{
    if (!path || !*path) { perr("CAT needs path", 0, 0); return; }
    int fd = op_rd(path);
    if (fd < 0) { perr("open", path, fd); return; }
    static const char H[] = "0123456789abcdef";
    long n = sc3(SYS_read, fd, (long)g_ibuf, sizeof(g_ibuf));
    sc1(SYS_close, fd);
    if (n < 0) { perr("read", path, n); return; }
    emit(g_out, "len=");
    emitdec(g_out, n);
    emitc(g_out, '\n');
    long i;
    if (as_text) {
        for (i = 0; i < n; i++) {
            unsigned char c = (unsigned char)g_ibuf[i];
            emitc(g_out, ((c >= 0x20 && c < 0x7f) || c == '\n' || c == '\t') ? (char)c : '.');
        }
        if (n > 0 && g_ibuf[n - 1] != '\n') emitc(g_out, '\n');
    } else {
        for (i = 0; i < n; i++) {
            emitc(g_out, H[(g_ibuf[i] >> 4) & 0xf]);
            emitc(g_out, H[g_ibuf[i] & 0xf]);
            if ((i & 15) == 15) emitc(g_out, '\n');
        }
        if (n > 0 && (n & 15) != 0) emitc(g_out, '\n');
    }
}

/* hex 字符串 -> 字节写入文件 */
static void cmd_write(char *path, char *hex, int append)
{
    if (!path || !*path) { perr("WRITE needs path", 0, 0); return; }
    if (!hex)  hex = (char *)"";
    int flags = append ? O_APPEND : O_TRUNC;
    int fd = (int)sc4(SYS_openat, AT_FDCWD, (long)path,
                      flags | O_WRONLY | O_CREAT, 0644);
    if (fd < 0) { perr("create", path, fd); return; }
    static char out[2200];
    int n = 0;
    while (hex[0] && hex[1] && n < (int)sizeof(out)) {
        int h = hexval(hex[0]), l = hexval(hex[1]);
        if (h < 0 || l < 0) break;
        out[n++] = (char)((h << 4) | l);
        hex += 2;
    }
    long w = sc3(SYS_write, fd, (long)out, n);
    sc1(SYS_close, fd);
    if (w != n) { perr("write", path, w); return; }
    emit(g_out, "wrote ");
    emitdec(g_out, n);
    emit(g_out, append ? " bytes (append)\n" : " bytes\n");
}

static void cmd_ls(char *path)
{
    if (!path || !*path) path = (char *)"/";
    int fd = (int)sc4(SYS_openat, AT_FDCWD, (long)path,
                      O_RDONLY | O_DIRECTORY, 0);
    if (fd < 0) { perr("opendir", path, fd); return; }
    static char dbuf[2048];
    long n;
    int cnt = 0;
    while ((n = sc3(SYS_getdents64, fd, (long)dbuf, sizeof(dbuf))) > 0) {
        long off = 0;
        while (off < n) {
            /* struct linux_dirent64 { u64 ino; s64 off; u16 reclen; u8 type; char name[]; } */
            unsigned char *e = (unsigned char *)dbuf + off;
            unsigned short reclen = *(unsigned short *)(e + 16);
            unsigned char dtype = *(e + 18);
            char *name = (char *)(e + 19);
            emit(g_out, name);
            if (dtype == 4) emitc(g_out, '/');
            emitc(g_out, '\n');
            cnt++;
            if (reclen == 0) break;
            off += reclen;
        }
    }
    sc1(SYS_close, fd);
    emit(g_out, "entries=");
    emitdec(g_out, cnt);
    emitc(g_out, '\n');
}

struct cairn_stat {
    unsigned long dev, ino, nlink;
    unsigned int mode, uid, gid;
    unsigned int pad;
    unsigned long rdev;
    long size, blksize, blocks;
    long atime_s, atime_n, mtime_s, mtime_n, ctime_s, ctime_n;
};

static void cmd_stat(char *path)
{
    if (!path || !*path) { perr("STAT needs path", 0, 0); return; }
    static struct cairn_stat st;
    long r = sc4(SYS_newfstatat, AT_FDCWD, (long)path, (long)&st, 0);
    if (r < 0) { perr("stat", path, r); return; }
    emit(g_out, "ino=");    emitdec(g_out, (long)st.ino);
    emit(g_out, " mode=");  emithex(g_out, st.mode, 4);
    emit(g_out, " uid=");   emitdec(g_out, (long)st.uid);
    emit(g_out, " gid=");   emitdec(g_out, (long)st.gid);
    emit(g_out, " size=");  emitdec(g_out, st.size);
    emit(g_out, " mtime="); emitdec(g_out, st.mtime_s);
    emitc(g_out, '\n');
}

static void cmd_uid(void)
{
    long u = sc0(SYS_getuid);
    long e = sc0(SYS_geteuid);
    emit(g_out, "uid=");
    emitdec(g_out, u);
    emit(g_out, " euid=");
    emitdec(g_out, e);
    emitc(g_out, '\n');
}
/*
 * IDS：读 /proc/self/status 里的 Uid: / Gid: / CapBnd: / CapEff: 四行。
 * 这是唯一能拿到 fsuid（Uid 行第 4 个字段）的用户态途径——
 * getuid/geteuid 不反映 fsuid，而 openat/mkdirat 的 DAC 判定用的正是 fsuid。
 */
static void cmd_ids(void)
{
    int fd = op_rd((char *)"/proc/self/status");
    if (fd < 0) { perr("open /proc/self/status", 0, fd); return; }
    long n = sc3(SYS_read, fd, (long)g_ibuf, sizeof(g_ibuf));
    sc1(SYS_close, fd);
    if (n <= 0) { perr("read /proc/self/status", 0, n); return; }
    long i = 0;
    while (i < n) {
        long s = i;
        while (i < n && g_ibuf[i] != '\n') i++;
        long e = i;
        if (i < n) i++;
        /* 只输出我们关心的四行 */
        int hit = 0;
        if (e - s >= 4 && g_ibuf[s]=='U' && g_ibuf[s+1]=='i' && g_ibuf[s+2]=='d' && g_ibuf[s+3]==':') hit = 1;
        if (e - s >= 4 && g_ibuf[s]=='G' && g_ibuf[s+1]=='i' && g_ibuf[s+2]=='d' && g_ibuf[s+3]==':') hit = 1;
        if (e - s >= 7 && g_ibuf[s]=='C' && g_ibuf[s+1]=='a' && g_ibuf[s+2]=='p' &&
            g_ibuf[s+3]=='B' && g_ibuf[s+4]=='n' && g_ibuf[s+5]=='d' && g_ibuf[s+6]==':') hit = 1;
        if (e - s >= 7 && g_ibuf[s]=='C' && g_ibuf[s+1]=='a' && g_ibuf[s+2]=='p' &&
            g_ibuf[s+3]=='E' && g_ibuf[s+4]=='f' && g_ibuf[s+5]=='f' && g_ibuf[s+6]==':') hit = 1;
        if (hit) {
            long k;
            for (k = s; k < e; k++) emitc(g_out, (char)g_ibuf[k]);
            emitc(g_out, '\n');
        }
    }
}

/* ---------------- parsing + dispatch ---------------- */

static char *skip_sp(char *p)
{
    while (*p == ' ' || *p == '\t' || *p == '\r') p++;
    return p;
}

static char *tok_next(char **pp)
{
    char *p = skip_sp(*pp);
    if (!*p) { *pp = p; return 0; }
    char *s = p;
    while (*p && *p != ' ' && *p != '\t' && *p != '\r') p++;
    if (*p) { *p = 0; p++; }
    *pp = p;
    return s;
}

static int streq(const char *a, const char *b)
{
    if (!a) return 0;
    while (*a && *b) { if (*a != *b) return 0; a++; b++; }
    return *a == *b;
}

static void run_line(char *line)
{
    char *p = line;
    char *v = tok_next(&p);
    if (!v) return;
    char *rest = skip_sp(p);

    if (streq(v, "ECHO"))   { emit(g_out, rest); emitc(g_out, '\n'); return; }
    if (streq(v, "UID"))    { cmd_uid(); return; }
    if (streq(v, "IDS"))    { cmd_ids(); return; }
    if (streq(v, "CAT"))    { cmd_cat(tok_next(&p), 0); return; }
    if (streq(v, "TEXT"))   { cmd_cat(tok_next(&p), 1); return; }
    if (streq(v, "WRITE"))  { char *a = tok_next(&p); char *b = tok_next(&p);
                               cmd_write(a, b, 0); return; }
    if (streq(v, "APPEND")) { char *a = tok_next(&p); char *b = tok_next(&p);
                               cmd_write(a, b, 1); return; }
    if (streq(v, "LS"))     { cmd_ls(tok_next(&p)); return; }
    if (streq(v, "STAT"))   { cmd_stat(tok_next(&p)); return; }

    if (streq(v, "MKDIR")) {
        char *a = tok_next(&p);
        if (!a) { perr("MKDIR needs path", 0, 0); return; }
        long r = sc3(SYS_mkdirat, AT_FDCWD, (long)a, 0755);
        if (r < 0) perr("mkdir", a, r);
        else emit(g_out, "mkdir ok\n");
        return;
    }
    if (streq(v, "RM")) {
        char *a = tok_next(&p);
        if (!a) { perr("RM needs path", 0, 0); return; }
        long r = sc3(SYS_unlinkat, AT_FDCWD, (long)a, 0);
        if (r < 0) perr("unlink", a, r);
        else emit(g_out, "unlink ok\n");
        return;
    }
    if (streq(v, "MV")) {
        char *a = tok_next(&p);
        char *b = tok_next(&p);
        if (!a || !b) { perr("MV needs old new", 0, 0); return; }
        long r = sc4(SYS_renameat, AT_FDCWD, (long)a, AT_FDCWD, (long)b);
        if (r < 0) perr("rename", a, r);
        else emit(g_out, "rename ok\n");
        return;
    }
    if (streq(v, "EXEC")) {
        /* 仅取证用：提权不改 SELinux domain，这里很可能被拒 */
        char *argv[4];
        argv[0] = (char *)"/system/bin/sh";
        argv[1] = (char *)"-c";
        argv[2] = rest;
        argv[3] = 0;
        emit(g_out, "trying execve /system/bin/sh\n");
        long r = sc3(SYS_execve, (long)argv[0], (long)argv, 0);
        perr("execve denied", "/system/bin/sh", r);
        return;
    }

    emit(g_out, "E: unknown verb ");
    emit(g_out, v);
    emitc(g_out, '\n');
    g_fail = 1;
}

/*
 * 读整个脚本到静态缓冲区（上限 8KB）。
 * 返回字节数，出错返回 -1。
 */
static char g_script[8192];

static long read_all(int fd, char *dst, long cap)
{
    static char rbuf[2048];
    long total = 0, n;
    while ((n = sc3(SYS_read, fd, (long)rbuf, sizeof(rbuf))) > 0) {
        long i;
        for (i = 0; i < n; i++) {
            if (total < cap - 1) dst[total++] = rbuf[i];
        }
    }
    dst[total] = 0;
    return total;
}

/*
 * 若脚本首行是 SHELL，则整段交给 /system/bin/sh 执行。
 *
 * 真机实测：untrusted_app 可以 execve /system/bin/sh（Java 的 Runtime.exec
 * 走的就是这条路，只会 execute_no_trans，域不变）。所以提权后 execve
 * 是可行的，而 sh 能做所有我们内置解释器做不到的事。
 *
 * 返回：-1 表示不是 SHELL 模式（调用方应走内置解释器）；
 *       否则返回 execve 的错误码（只有失败才会返回）。
 */
/* Passed only to the authenticated child shell; never stored on disk. */
static char g_key_env[43] = "CAIRN_KEY=";
static void child_key_env(uint64_t k0, uint64_t k1)
{
    const char *hex = "0123456789ABCDEF";
    for (int i = 0; i < 16; i++) {
        uint64_t k = i < 8 ? k0 : k1;
        unsigned byte = (unsigned)(k >> ((i & 7) * 8)) & 255;
        g_key_env[10 + i*2] = hex[byte >> 4];
        g_key_env[11 + i*2] = hex[byte & 15];
    }
    g_key_env[42] = 0;
}
static long try_shell_mode(int out_fd, long n)
{
    long i = 0;
    while (i < n && (g_script[i] == ' ' || g_script[i] == '\t')) i++;
    if (n - i < 5) return -1;
    if (g_script[i] != 'S' || g_script[i+1] != 'H' ||
        g_script[i+2] != 'E' || g_script[i+3] != 'L' || g_script[i+4] != 'L') return -1;
    long body = i + 5;
    if (body < n && g_script[body] == '\r') body++;
    if (body < n && g_script[body] == '\n') body++;

    /* 把 stdout / stderr 接到结果文件 */
    sc3(SYS_dup3, out_fd, 1, 0);
    sc3(SYS_dup3, out_fd, 2, 0);

    char *argv[4];
    argv[0] = (char *)"/system/bin/sh";
    argv[1] = (char *)"-c";
    argv[2] = g_script + body;
    argv[3] = 0;

    /*
     * execve 会清掉我们这份进程映像。提权后的 cred 跟着进程走，
     * 所以 sh 以及它 fork 出来的子进程都继承 uid 0。
     */
    char *child_env[] = { g_key_env, "PATH=/system/bin:/system/xbin", 0 };
    long r = sc3(SYS_execve, (long)argv[0], (long)argv, (long)child_env);
    return r;   /* 只有失败才会到这里 */
}

static void run_buffer(const char *buf, long n)
{
    static char line[4096];
    long i = 0;
    int len = 0;
    while (i < n) {
        char c = buf[i++];
        if (c == '\n') {
            line[len] = 0;
            if (len > 0) run_line(line);
            len = 0;
        } else if (len < (int)sizeof(line) - 1) {
            line[len++] = c;
        }
    }
    if (len > 0) { line[len] = 0; run_line(line); }
}

/*
 * 在子进程里提权并执行脚本，结果写回 out_path。父进程 uid 始终不变。
 * 返回：0 全部成功，1..254 子进程退出码，-1 clone 失败，-2 子进程被信号杀死
 */
long cairn_spawn_root(uint64_t k0, uint64_t k1,
                      const char *cmd_path, const char *out_path)
{
    if (!cmd_path || !out_path) return -1;

    long pid = sc5(SYS_clone, 17 /* SIGCHLD */, 0, 0, 0, 0);
    if (pid < 0) return -1;

    if (pid == 0) {
        int of = (int)sc4(SYS_openat, AT_FDCWD, (long)out_path,
                          O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (of < 0) sc1(SYS_exit, 1);
        g_out = of;

        /*
         * 【必须先读脚本，再提权 —— 顺序错了就 EACCES(-13)】
         *
         * 真机日志证据：
         *   [child] escalate OK  uid=0 euid=0
         *   E: open script /data/user/0/com.cairn.manager/files/cairn.cmd -> -13
         *
         * -13 是 EACCES，而且是 **DAC 拒的，不是 SELinux 拒的**：
         *   /data/user/0/com.cairn.manager/files/ 属主 uid 10389、模式 0700。
         *   提权后进程 uid=0 —— 不是属主、不在 group、other 位为 0，
         *   内核在普通权限位这一层就返回 EACCES，根本走不到 SELinux 判定，
         *   所以内核里 avc_has_perm 那个「bit50 全放行」补丁再好也救不了。
         *
         * 反证：上面 out_path 是提权前打开的（那时还是 uid 10389，
         * 能进自己目录），所以 "[child] escalate OK" 这行写得出来，
         * 偏偏到 open 脚本就 -13 —— 问题就锁在这一步的顺序上。
         *
         * 正确做法：uid 还是 10389 时把脚本读进内存 → 提权 → 执行内存里的脚本。
         * 「执行」需要 root，「读文件」不需要。
         */
        int cf = op_rd(cmd_path);
        long sn = 0;
        if (cf < 0) {
            perr("open script", cmd_path, cf);
        } else {
            sn = read_all(cf, g_script, sizeof(g_script));
            sc1(SYS_close, cf);
            if (sn < 0) sn = 0;
        }

        long r = raw_prctl_auth(k0, k1);
        if (r == 0) {
            emit(of, "[child] escalate OK  uid=");
            emitdec(of, sc0(SYS_getuid));
            emit(of, " euid=");
            emitdec(of, sc0(SYS_geteuid));
            emitc(of, '\n');
        } else if (r == -1 || (unsigned long)r == 0xFFFFFFFFUL) {
            emit(of, "[child] escalate FAILED: bad key\n");
        } else {
            emit(of, "[child] escalate FAILED: ret=");
            emitdec(of, r);
            emitc(of, '\n');
        }

        if (r != 0) { sc1(SYS_close, of); sc1(SYS_exit, 2); }

        /* 脚本已在提权前读进 g_script，这里直接执行 */
        if (sn > 0) {
            child_key_env(k0, k1);
            long er = try_shell_mode(of, sn);
            if (er != -1) {
                /* 只有 execve 失败才会返回 */
                emit(of, "[child] SHELL mode execve failed -> ");
                emitdec(of, er);
                emitc(of, '\n');
                sc1(SYS_close, of);
                sc1(SYS_exit, 4);
            }
        }

        /* 内置解释器路径 */
        emit(of, "--- script begin ---\n");
        run_buffer(g_script, sn);
        emit(of, "--- script end  fail=");
        emitdec(of, g_fail);
        emit(of, " ---\n");
        sc1(SYS_close, of);
        sc1(SYS_exit, g_fail ? 5 : 0);
    }

    {
        int st = 0;
        sc4(SYS_wait4, pid, (long)&st, 0, 0);
        if (st & 0x7f) return -2;          /* 被信号杀死 */
        return (st >> 8) & 0xff;
    }
}

/*
 * 脚本与结果文件的固定路径。
 * 不用 JNI 传字符串：手写 JNINativeInterface_ 偏移极易算错（把
 * EnsureLocalCapacity 当成 GetByteArrayRegion 会直接跳飞）。
 * 主用户下 Context.getFilesDir() 就是 /data/user/0/<pkg>/files，
 * 与这里硬编码的路径一致。
 */
const char *cairn_cmd_path = "/data/user/0/com.cairn.manager/files/cairn.cmd";
const char *cairn_out_path = "/data/user/0/com.cairn.manager/files/cairn.out";
