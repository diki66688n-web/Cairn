#!/bin/bash
# 编译 cairnd（守护进程）。目标：Android arm64，静态、无 libc、无依赖。
set -u
cd /path/to/Cairn/source/daemon

OUT=cairnd
fail() { echo "!! FAIL at $1"; exit 1; }

echo "--- 1/3 编译"
aarch64-linux-gnu-gcc \
  -O2 -static -nostdlib -fno-builtin -fno-stack-protector \
  -fno-tree-loop-distribute-patterns \
  -Wall -Wextra -Wno-unused-function -Wno-unused-parameter \
  -Wl,-e,_start -Wl,--build-id=none \
  -o "$OUT" cairn_entry.c cairnd.c \
  > /tmp/daemon_build.log 2>&1 || { cat /tmp/daemon_build.log; fail "compile"; }

[ -s /tmp/daemon_build.log ] && { echo "    警告/信息："; cat /tmp/daemon_build.log; }

echo "--- 2/3 检查未定义符号"
U=$(aarch64-linux-gnu-nm -u "$OUT" 2>/dev/null)
if [ -n "$U" ]; then
  echo "$U"
  fail "有未定义符号（会导致运行时崩溃）"
fi
echo "    undefined = 0"

echo "--- 3/3 检查入口与字节数"
aarch64-linux-gnu-readelf -h "$OUT" | grep -E 'Entry|Type|Machine'
SZ=$(stat -c%s "$OUT")
echo "    size = $SZ bytes"

# 确认 _start 真的被链接进去
aarch64-linux-gnu-nm "$OUT" | grep -q ' _start$' || fail "_start 缺失"

echo
echo "=== BUILD OK ==="
ls -l "$OUT"
sha256sum "$OUT"