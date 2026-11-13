/* Shared controller paths and diagnostic ABI version. No socket wire protocol. */
#ifndef CAIRN_PROTO_H
#define CAIRN_PROTO_H
#define CAIRN_PROTO_VER     2
#define CAIRN_BASE          "/data/adb/cairn"
#define CAIRN_BIN_DIR       CAIRN_BASE "/bin"
#define CAIRN_LOG           CAIRN_BASE "/daemon.log"
#define CAIRN_AUDIT         CAIRN_BASE "/audit.log"
#define CAIRN_MGR_UID_FILE  CAIRN_BASE "/manager.uid"
#endif
