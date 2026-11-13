/*
 * cairn_sys.h —— 无 libc 的 syscall 封装与极小工具集
 *
 * 为什么不用 libc：
 *   守护进程的 SELinux 域是 u:r:untrusted_app（我们从不碰 cred->security），
 *   但 uid=0。静态链接 libc 会让二进制膨胀到 MB 级，而且 bionic 的
 *   某些初始化会在 untrusted_app 域下做被拒的操作。全部用裸 syscall
 *   最稳，也不依赖任何 .so。
 *
 * arm64 系统调用号来源：kernel 的 include/uapi/asm-generic/unistd.h
 *   注意 arm64 没有 socketcall，socket/bind/listen/accept4/getsockopt
 *   都是独立编号，其中 getsockopt 是 209（不是 55，55 是 x86 的）。
 */
#ifndef CAIRN_SYS_H
#define CAIRN_SYS_H

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

/* ---- 系统调用号（arm64） ---- */
#define SYS_read        63
#define SYS_write       64
#define SYS_openat      56
#define SYS_close       57
#define SYS_fstat       80
#define SYS_mkdirat     34
#define SYS_unlinkat    35
#define SYS_pipe2       59
#define SYS_dup3        24
#define SYS_socket      198
#define SYS_bind        200
#define SYS_listen      201
#define SYS_accept4     242
#define SYS_connect     203
#define SYS_setsockopt  208
#define SYS_getsockopt  209
/* arm64 没有 chmod(path) 这个系统调用！52 是 fchmod(fd)，
 * 直接把路径当 fd 传进去只会得到 EBADF。改权限必须用 fchmodat。 */
#define SYS_fchmod      52
#define SYS_fchmodat    53
#define AT_SYMLINK_NOFOLLOW 0x100
#define SYS_clone       220
#define SYS_wait4       260
#define SYS_execve      221
#define SYS_exit        93
#define SYS_getuid      174
#define SYS_geteuid     175
#define SYS_getpid      172
#define SYS_getppid     173
#define SYS_setsid      157
#define SYS_prctl       167
#define SYS_clock_nanosleep 115
#define SYS_getdents64  61
#define SYS_ppoll       73
#define SYS_kill        129
#define SYS_lseek       62
#define SYS_renameat    38
#define SYS_unlinkat    35
#define SEEK_SET_       0
#define SEEK_END_       2
#define SYS_ftruncate   46

/* ---- 常量 ---- */
#define AF_UNIX         1
#define SOCK_STREAM     1
#define SOL_SOCKET      1
#define SO_PEERCRED     17
#define SO_RCVTIMEO     20
#define SO_SNDTIMEO     21

#define AT_FDCWD        (-100)
#define O_RDONLY        0
#define O_WRONLY        1
#define O_RDWR          2
#define O_CREAT         0100
#define O_TRUNC         01000
#define O_APPEND        02000
#define O_DIRECTORY     0x4000
#define O_CLOEXEC       0x80000

#define PR_SET_PDEATHSIG 1
#define SIGCHLD_        17
#define WNOHANG         1

struct ucred {
    int pid;
    unsigned int uid;
    unsigned int gid;
};

struct sockaddr_un {
    unsigned short sun_family;
    char sun_path[108];
};

struct timespec_ {
    long tv_sec;
    long tv_nsec;
};

struct timeval_ {
    long tv_sec;
    long tv_usec;
};

struct pollfd_ {
    int fd;
    short events;
    short revents;
};

#define POLLIN_  0x001
#define POLLERR_ 0x008
#define POLLHUP_ 0x010

struct dirent64_ {
    u64 d_ino;
    i64 d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
};

/* ---- syscall 包装 ---- */
static i64 sc0(i64 n)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = 0;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}
static i64 sc1(i64 n, i64 a)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}
static i64 sc2(i64 n, i64 a, i64 b)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1) : "memory");
    return x0;
}
static i64 sc3(i64 n, i64 a, i64 b, i64 c)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}
static i64 sc4(i64 n, i64 a, i64 b, i64 c, i64 d)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    __asm__ volatile("svc #0" : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
static i64 sc5(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    register i64 x4 __asm__("x4") = e;
    __asm__ volatile("svc #0" : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory");
    return x0;
}
static i64 sc6(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f)
{
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    register i64 x4 __asm__("x4") = e;
    register i64 x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory");
    return x0;
}

/* ---- 字符串 ---- */
static int slen(const char *s) { int n = 0; while (s[n]) n++; return n; }

static char *stpcpy_(char *d, const char *s)
{
    while (*s) *d++ = *s++;
    *d = 0;
    return d;
}

static int seqs(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

/* 无符号十进制 -> 字符串，返回长度 */
static int utoa(u32 v, char *out)
{
    char tmp[12];
    int n = 0, i = 0;
    if (v == 0) { out[0] = '0'; out[1] = 0; return 1; }
    while (v) { tmp[n++] = (char)('0' + (v % 10)); v /= 10; }
    while (n) out[i++] = tmp[--n];
    out[i] = 0;
    return i;
}

/* 十进制字符串 -> 无符号，遇到非数字停止。返回解析出的值 */
static u32 atou(const char *s)
{
    u32 v = 0;
    while (*s >= '0' && *s <= '9') { v = v * 10 + (u32)(*s - '0'); s++; }
    return v;
}

/* ---- 日志（追加写） ----
 * 路径必须用 cairn_proto.h 里的宏，不能硬编码：
 * 否则改 CAIRN_BASE 时日志会漏改，落到别的目录去。 */
static i64 log_fd_cached = -1;

static void log_open(void)
{
    log_fd_cached = sc4(SYS_openat, AT_FDCWD, (i64)CAIRN_LOG,
                        O_WRONLY | O_CREAT | O_APPEND, 0600);
}

static void loga(const char *s)
{
    if (log_fd_cached >= 0)
        sc3(SYS_write, log_fd_cached, (i64)s, slen(s));
}

static void loga_str(const char *tag, const char *s)
{
    if (log_fd_cached < 0) return;
    sc3(SYS_write, log_fd_cached, (i64)tag, slen(tag));
    sc3(SYS_write, log_fd_cached, (i64)s, slen(s));
    sc3(SYS_write, log_fd_cached, (i64)"\n", 1);
}

static void loga_num(const char *tag, u32 v)
{
    char b[12];
    if (log_fd_cached < 0) return;
    utoa(v, b);
    sc3(SYS_write, log_fd_cached, (i64)tag, slen(tag));
    sc3(SYS_write, log_fd_cached, (i64)b, slen(b));
    sc3(SYS_write, log_fd_cached, (i64)"\n", 1);
}

/* 审计日志：管理操作专用，与运行日志分开，便于用户核对 */
static void audit(const char *op, u32 actor, u32 target)
{
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)CAIRN_AUDIT,
                 O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    char b1[12], b2[12], line[96];
    char *p = line;
    p = stpcpy_(p, op);
    p = stpcpy_(p, " actor=");
    utoa(actor, b1); p = stpcpy_(p, b1);
    p = stpcpy_(p, " target=");
    utoa(target, b2); p = stpcpy_(p, b2);
    stpcpy_(p, "\n");
    sc3(SYS_write, fd, (i64)line, slen(line));
    sc1(SYS_close, fd);
}

#endif /* CAIRN_SYS_H */