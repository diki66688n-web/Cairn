/* Shared paths and legacy request structures.
 * Current authorization uses manager-key prctl calls and a persisted centralized allowlist; no active socket listener.
 * Protocol structs, auth/pending paths and vu constants below are legacy compatibility definitions, not current entry points. */
#ifndef CAIRN_PROTO_H
#define CAIRN_PROTO_H

#define CAIRN_PROTO_MAGIC   0x434149524E303032UL  /* "CAIRN002" */
#define CAIRN_PROTO_VER     2

/* ---- 请求类型 ---- */
#define CAIRN_REQ_EXEC      1   /* 执行命令，回传 stdout/stderr/exit，需两层授权 */
#define CAIRN_REQ_PING      2   /* 探活，不需要授权 */
#define CAIRN_REQ_VERSION   3   /* 取协议版本，不需要授权 */

/* Legacy wire-protocol management request types; current manager uses cnd CLI. */
#define CAIRN_REQ_GRANT     10  /* 授权某个 uid，cmd 内容 = 十进制 uid */
#define CAIRN_REQ_REVOKE    11  /* 收回某个 uid，cmd 内容 = 十进制 uid */
#define CAIRN_REQ_LIST      12  /* 列出已授权 uid，结果走输出流 */
#define CAIRN_REQ_STATUS    13  /* 守护进程自述状态，结果走输出流 */

/* ---- 应答类型 ---- */
#define CAIRN_RSP_OK        0
#define CAIRN_RSP_EPERM     1   /* 未授权（未勾选），或管理接口 uid 不符 */
#define CAIRN_RSP_EDENIED   2   /* 已勾选但用户拒绝 */
#define CAIRN_RSP_ETIMEOUT  3   /* 弹窗超时 */
#define CAIRN_RSP_EBADREQ   4   /* 报文非法 */
#define CAIRN_RSP_EINTERNAL 5   /* 内部错误 */

/* 单条报文上限 */
#define CAIRN_MAX_CMD       4096
#define CAIRN_MAX_OUT       65536

struct cairn_req {
    unsigned long magic;
    unsigned int  type;
    unsigned int  cmdlen;
    unsigned int  flags;       /* bit0 = 强制弹窗 */
    unsigned int  seq;         /* 客户端提供，用于 pending 文件命名唯一 */
    /* 紧随其后 cmdlen 字节命令文本（不要求 NUL 结尾） */
};

struct cairn_rsp {
    unsigned long magic;
    unsigned int  type;
    unsigned int  code;        /* CAIRN_RSP_* */
    int           exit_code;
    unsigned int  outlen;
    unsigned int  errlen;
    int           truncated;
    /* 紧随其后 outlen + errlen 字节 */
};

/* ---- 路径 ---- */
#define CAIRN_BASE          "/data/adb/cairn"
#define CAIRN_BIN_DIR       CAIRN_BASE "/bin"
#define CAIRN_AUTH_DIR      CAIRN_BASE "/auth"
#define CAIRN_PENDING_DIR   CAIRN_BASE "/pending"
#define CAIRN_LOG           CAIRN_BASE "/daemon.log"
#define CAIRN_AUDIT         CAIRN_BASE "/audit.log"
#define CAIRN_MGR_UID_FILE  CAIRN_BASE "/manager.uid"

/* Legacy vu entry constants. Current kernel payload matches /system/bin/su and redirects authorized execution to /data/adb/cnd. These constants do not configure that payload. */
#define CAIRN_VU_REAL       CAIRN_BIN_DIR "/vu"

/* Legacy vu path, not the current kernel hook entry. */
#define CAIRN_VU_ENTRY      "/system/bin/vu"

/* No socket listener is started by the current dispatcher; older wire declarations remain for unremoved legacy code. */

#endif /* CAIRN_PROTO_H */