#!/bin/bash
set -u
cd /path/to/Cairn/source/app
ANDROID_JAR=/usr/lib/android-sdk/platforms/android-33/android.jar

R8=/opt/kotlin/tools/r8.jar
OUT=cairn-manager.apk
fail() { echo "!! FAIL at $1"; exit 1; }
step() { echo "--- $1"; }
rm -rf build/classes build/dex build/tmp.apk build/aligned.apk build/stage \
       build/res_flat build/gen
mkdir -p build/classes build/dex build/res_flat build/gen

# ---------------------------------------------------------------------------
# 步骤顺序说明（这里踩过坑，写清楚）：
#   aapt2 link 会生成 R.java，而 Java 代码里引用了 R.color.xxx / R.dimen.xxx
#   这类常量。所以资源链接【必须排在 javac 之前】，否则 javac 会报
#   "cannot find symbol: R"。同时 aapt2 link 又需要 AndroidManifest.xml，
#   而 manifest 里引用了 @style/Theme.Cairn —— 所以 res 编译必须在 link 之前。
#
#   正确顺序：aapt2 compile → aapt2 link(+生成 R.java) → javac → d8 → 打包
# ---------------------------------------------------------------------------

step "1/8 aapt2 compile"
if [ -d res ] && [ -n "$(ls -A res 2>/dev/null)" ]; then
  aapt2 compile --dir res -o build/res_flat/restmp.zip \
    > /tmp/b_aapt2c.log 2>&1 || { cat /tmp/b_aapt2c.log; fail "aapt2 compile"; }
  N=$(unzip -l build/res_flat/restmp.zip | tail -1 | awk '{print $2}')
  echo "    compiled $N resource files"
else
  echo "    (no res/)"
fi

step "2/8 aapt2 link + R.java"
aapt2 link \
  -o build/tmp.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 23 \
  --target-sdk-version 33 \
  --auto-add-overlay \
  build/res_flat/restmp.zip \
  > /tmp/b_aapt2l.log 2>&1 || { cat /tmp/b_aapt2l.log; fail "aapt2 link"; }
[ -f build/gen/com/cairn/manager/R.java ] || fail "R.java 未生成"
echo "    R.java=$(wc -l < build/gen/com/cairn/manager/R.java) 行"

step "3/8 javac"
# 注意：classpath 用 android-33.jar，但 --release 8 保留。
# 原因：--release 8 决定的是「字节码版本」（dex 需要 <= Java 8 字节码），
# 而 classpath 决定「能看到哪些 API」。两者独立。
# 用 33 的 jar 是为了能引用 RuntimeShader / RenderEffect（AGSL 液态玻璃），
# 但代码里一律走反射调用（见 glass/GlassCompat.java），
# 因为 minSdk 仍是 23，直接引用 API 31+ 的类会在低版本设备上
# 在类验证阶段抛 NoClassDefFoundError。
javac --release 8 -classpath "$ANDROID_JAR:libs/liquidglass.jar" -d build/classes \
  build/gen/com/cairn/manager/R.java \
  src/com/cairn/manager/*.java src/com/cairn/manager/glass/*.java \
  > /tmp/b_javac.log 2>&1 || { cat /tmp/b_javac.log; fail javac; }
for c in MainActivity Native FileLog KeyStoreBox CairnCore AppList R; do
  [ -f "build/classes/com/cairn/manager/$c.class" ] || fail "missing $c.class"
done
for c in GlassCard GlassCompat GlassBackdrop LiquidNavBar; do
  [ -f "build/classes/com/cairn/manager/glass/$c.class" ] || fail "missing glass/$c.class"
done
echo "    classes=11 (6 业务 + 4 玻璃 + R)"
step "4/8 d8"
# 只把【我们自己的 class】转 dex；官方 LiquidGlass.dex 原样使用，
# 不经过 d8 重编（它已经是成品 dex，重编没有任何好处，只会多一次风险）
java -cp "$R8" com.android.tools.r8.D8 --min-api 23 --lib "$ANDROID_JAR" \
  --classpath libs/liquidglass.jar --output build/dex \
  $(find build/classes -name '*.class') > /tmp/b_d8.log 2>&1 || { cat /tmp/b_d8.log; fail d8; }
step "5/8 dex check"
python3 -c "
d=open('build/dex/classes.dex','rb').read()
need=[b'MainActivity',b'FileLog',b'KeyStoreBox',b'Native',b'GlassCard',b'GlassCompat',b'GlassBackdrop']
bad=[n.decode() for n in need if d.count(n)==0]
print('    dex=%d bytes missing=%s'%(len(d),bad or 'none'))
raise SystemExit(1 if bad else 0)" || fail "dex missing classes"
step "6/8 native so"
mkdir -p build/lib/arm64-v8a
aarch64-linux-gnu-gcc -O2 -fPIC -shared -fno-builtin \
  -fno-tree-loop-distribute-patterns -Wall -Wextra -nostdlib -Wl,-e,0 \
  -o build/lib/arm64-v8a/libcairn.so jni/cairn.c jni/jni_bridge.c \
  > /tmp/b_so.log 2>&1 || { cat /tmp/b_so.log; fail "native so"; }
[ -s /tmp/b_so.log ] && { echo "    warnings:"; cat /tmp/b_so.log; }
# 不能有未定义符号：so 用 -nostdlib 编译，任何 libc 引用都会在真机上解析失败
U=$(aarch64-linux-gnu-nm -D --undefined-only build/lib/arm64-v8a/libcairn.so)
[ -z "$U" ] || { echo "$U"; fail "so has undefined symbols"; }
# 4 个 JNI 入口必须导出
for f in auth probe getuid spawnRoot; do
  aarch64-linux-gnu-nm -D --defined-only build/lib/arm64-v8a/libcairn.so \
    | grep -q "Java_com_cairn_manager_Native_$f$" || fail "missing JNI $f"
done
echo "    so=$(stat -c%s build/lib/arm64-v8a/libcairn.so) bytes, undefined=0, jni=4"
mkdir -p build/stage/lib/arm64-v8a
cp build/dex/classes.dex build/stage/classes.dex
# 官方液态玻璃库：原样拷过去改名 classes2.dex。
# Android 会按 classes.dex / classes2.dex / classes3.dex 的顺序全部加载，
# 文件名必须是这个格式，随便叫会直接找不到类。
cp libs/LiquidGlass.dex build/stage/classes2.dex
cp build/lib/arm64-v8a/libcairn.so build/stage/lib/arm64-v8a/
# ---- 守护进程二进制 ----
# 以 lib<name>.so 的形式放进 APK 的 lib/<abi>/。
# 原因：untrusted_app 只被允许在 apk_data_file（即 nativeLibraryDir）
# 执行文件；放在 assets/ 或 /data/data 下都 exec 不了。
# 系统安装时会把 lib/<abi>/*.so 解压到 nativeLibraryDir 并置 0755，
# 路径通过 applicationInfo.nativeLibraryDir + "/libcairnd.so" 得到。
# ---------------------------------------------------------------------------
# 防呆:assets/cairnd 必须与刚编出来的 daemon/cairnd 逐字节一致。
#
# 为什么加这道闸:
#   APP 每次启动都会把自己的 libcairnd.so 拷到 /data/adb/cnd
#   (CairnCore.deployDaemon),会【覆盖】手动推上去的那份。
#   只要 assets/cairnd 落后于 daemon/cairnd,刷了带新内核的 boot 后
#   一打开管理器就会被打回旧二进制 -> 内核注入的
#   "exec ... cnd --stage post-fs-data" 跑进不认 --stage 的旧版,
#   直接静默退出,现象是“内核明明刷了却没反应”,极难定位。
#   宁可在这里 fail,也不要让版本脱节进到设备。
# ---------------------------------------------------------------------------
DAEMON_REAL=../daemon/cairnd
if [ -s "$DAEMON_REAL" ]; then
  A=$(sha256sum assets/cairnd 2>/dev/null | cut -d' ' -f1)
  B=$(sha256sum "$DAEMON_REAL" 2>/dev/null | cut -d' ' -f1)
  if [ "$A" != "$B" ]; then
    fail "assets/cairnd 与 daemon/cairnd 不一致
    assets=$A
    daemon=$B
    修:cp daemon/cairnd app/assets/cairnd"
  fi
  echo "    assets/cairnd 与 daemon/cairnd 一致 ($A)"
else
  echo "  [警告] ../daemon/cairnd 不存在,跳过一致性校验"
fi

CAIRND_SRC=assets/cairnd
[ -s "$CAIRND_SRC" ] || fail "assets/cairnd 缺失（先编 rustd）"

# 必须是静态 ET_EXEC。真机实证（v43）：
#   - PIE(ET_DYN) 版虽然能被 linker64 加载，但会【二次经过
#     security_bprm_check】——内核 exec 桩在第一次 bprm_check 里已经把
#     /system/bin/su 改写成 /data/adb/cnd，紧接着 linker64 再次触发
#     bprm_check 时状态已变，链路断掉。
#   - 纯静态 ET_EXEC 不经过任何解释器，只过一次 bprm_check，链路干净。
#   - 早期 musl static-PIE 崩（RC=139）是 musl 启动代码问题，与静态链接
#     本身无关；当前是 NDK r27c clang + bionic sysroot 静态链接，真机可跑。
#   - 静态文件不受 Android 15+ 的 16KB 页对齐约束（那只对 PIE/动态库生效）。
ET=$(readelf -h "$CAIRND_SRC" | awk '/Type:/{print $2}')
[ "$ET" = "EXEC" ] || fail "cairnd 不是静态 EXEC(ET_EXEC)，实际是 $ET"
readelf -l "$CAIRND_SRC" | grep -q INTERP \
  && fail "cairnd 带 PT_INTERP（动态可执行），会二次过 bprm_check，必须静态"
# 静态可执行文件不允许有任何动态依赖
BAD=$(readelf -d "$CAIRND_SRC" 2>/dev/null | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}')
[ -z "$BAD" ] || fail "cairnd 存在动态依赖（不是静态链接）: $BAD"

cp "$CAIRND_SRC" build/lib/arm64-v8a/libcairnd.so
chmod 0755 build/lib/arm64-v8a/libcairnd.so
# 注意：zip 是在 build/stage 里执行的，所以必须同时放进 stage。
cp build/lib/arm64-v8a/libcairnd.so build/stage/lib/arm64-v8a/libcairnd.so
chmod 0755 build/stage/lib/arm64-v8a/libcairnd.so
# ---- 自带 sh(模块脚本解释器) ----
# stage.c 写死 CAIRN_SH_PATH=/data/adb/bin/sh,模块脚本全用它执行。
# 与 cnd 同一约束:必须是 static ET_EXEC,且必须走 lib/<abi>/。
SH_SRC=assets/bin/sh
[ -s "$SH_SRC" ] || fail "assets/bin/sh 缺失(模块脚本会 exit 127)"
SH_ET=$(readelf -h "$SH_SRC" | awk '/Type:/{print $2}')
[ "$SH_ET" = "EXEC" ] || fail "assets/bin/sh 不是静态 EXEC(ET_EXEC),实际是 $SH_ET"
readelf -l "$SH_SRC" | grep -q INTERP \
  && fail "assets/bin/sh 带 PT_INTERP(动态可执行),必须静态"
SH_BAD=$(readelf -d "$SH_SRC" 2>/dev/null | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}')
[ -z "$SH_BAD" ] || fail "assets/bin/sh 存在动态依赖: $SH_BAD"
cp "$SH_SRC" build/lib/arm64-v8a/libcairnsh.so
chmod 0755 build/lib/arm64-v8a/libcairnsh.so
cp build/lib/arm64-v8a/libcairnsh.so build/stage/lib/arm64-v8a/libcairnsh.so
chmod 0755 build/stage/lib/arm64-v8a/libcairnsh.so
echo "    libcairnsh.so=$(stat -c%s "$SH_SRC") bytes @ lib/arm64-v8a/ (static EXEC, no INTERP)"

mkdir -p build/stage/assets
cp assets/module-install.sh build/stage/assets/
( cd build/stage && zip -q -X ../tmp.apk assets/module-install.sh classes.dex classes2.dex \
    lib/arm64-v8a/libcairn.so lib/arm64-v8a/libcairnd.so \
    lib/arm64-v8a/libcairnsh.so ) || fail zip
# 打包后立刻验证：装出来的 APK 里必须真的有这个 lib，且路径在 lib/ 下
unzip -l build/tmp.apk | grep -q 'lib/arm64-v8a/libcairnd.so' \
  || fail "APK 内缺少 lib/arm64-v8a/libcairnd.so"
unzip -l build/tmp.apk | grep -q 'lib/arm64-v8a/libcairnsh.so' \
  || fail "APK 内缺少 lib/arm64-v8a/libcairnsh.so"
echo "    libcairnd.so=$(stat -c%s "$CAIRND_SRC") bytes @ lib/arm64-v8a/ (static EXEC, no INTERP)"
step "7/8 zipalign"
zipalign -f -p 4 build/tmp.apk build/aligned.apk > /tmp/b_al.log 2>&1 || { cat /tmp/b_al.log; fail zipalign; }
step "8/8 sign"
apksigner sign --ks build/cairn.keystore --ks-pass pass:cairn123 --key-pass pass:cairn123 --ks-key-alias cairn --out "build/$OUT" build/aligned.apk > /tmp/b_sign.log 2>&1 || { cat /tmp/b_sign.log; fail sign; }
apksigner verify "build/$OUT" > /dev/null 2>&1 || fail verify
echo
echo "=== BUILD OK ==="
ls -l "build/$OUT"
sha256sum "build/$OUT"
unzip -l "build/$OUT" | tail -8
