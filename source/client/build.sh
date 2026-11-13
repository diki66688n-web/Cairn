#!/bin/bash
# LEGACY: builds unused socket clients, not the current su dispatch executable.
# 编译 su 客户端 + 管理 CLI
#
# 关键链接参数（都是真机踩出来的，不要删）：
#   -nostdlib          不引入 libc，二进制小、无 bionic 初始化副作用
#   -Wl,-e,_start      入口用我们自己的 _start（定义在 cairn_entry_asm.h 的汇编里）
#   -Wl,-z,max-page-size=0x1000
#                      默认按 0x10000 对齐会把 .bss 所在 RW 段的 p_offset
#                      推到 0xfff0，此时 p_filesz=0 但 offset 已远超文件大小，
#                      真机上 execve 后一访问即 SIGSEGV（ct/t4 实验已证实）。
#                      强制 4K 页对齐可消除该空洞。
#   -Wl,--build-id=none 不生成 build-id 段，减少无用段
set -u
cd /path/to/workspace/cairn/client

CFLAGS="-O2 -static -nostdlib -fno-builtin -fno-stack-protector \
 -fno-tree-loop-distribute-patterns \
 -Wall -Wextra -Wno-unused-function -Wno-unused-parameter \
 -Wl,--build-id=none -Wl,-z,max-page-size=0x1000"

fail() { echo "!! FAIL at $1"; exit 1; }

echo "--- 1/3 编译 su"
aarch64-linux-gnu-gcc $CFLAGS -Wl,-e,_start \
  -o su su_entry.c cairn_su.c \
  > /tmp/su_build.log 2>&1 || { cat /tmp/su_build.log; fail "su compile"; }
[ -s /tmp/su_build.log ] && { echo "    信息："; cat /tmp/su_build.log; }

echo "--- 2/3 编译 cairnctl（管理 CLI，用于真机自测）"
aarch64-linux-gnu-gcc $CFLAGS -Wl,-e,_start \
  -o cairnctl su_entry.c cairn_ctl.c \
  > /tmp/ctl_build.log 2>&1 || { cat /tmp/ctl_build.log; fail "ctl compile"; }
[ -s /tmp/ctl_build.log ] && { echo "    信息："; cat /tmp/ctl_build.log; }

echo "--- 3/3 检查"
for b in su cairnctl; do
  U=$(aarch64-linux-gnu-nm -u "$b" 2>/dev/null)
  if [ -n "$U" ]; then echo "$b 有未定义符号:"; echo "$U"; fail "$b undefined"; fi

  # 段布局自检：任何 LOAD 段的 p_offset + p_filesz 都不能超过文件大小
  # 不用 gawk 的 strtonum（mawk/busybox awk 没有），统一按十六进制解析
  SZ=$(stat -c%s "$b")
  BAD=$(aarch64-linux-gnu-readelf -lW "$b" | awk -v sz="$SZ" '
    function h2d(s,   i,c,v) {
      v = 0
      sub(/^0x/, "", s)
      for (i = 1; i <= length(s); i++) {
        c = index("0123456789abcdef", tolower(substr(s, i, 1))) - 1
        v = v * 16 + c
      }
      return v
    }
    $1 == "LOAD" {
      off = h2d($2); fsz = h2d($5)
      if (off + fsz > sz) print "  bad LOAD: off=" $2 " filesz=" $5
    }')
  [ -z "$BAD" ] || { echo "$b $BAD"; fail "$b segment out of file"; }

  printf '    %-9s %6s bytes  undefined=0  segs_OK\n' "$b" "$SZ"
done

echo
echo "=== BUILD OK ==="
ls -l su cairnctl
sha256sum su cairnctl