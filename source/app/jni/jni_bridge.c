/*
 * JNI 桥接（不依赖 jni.h / NDK）
 *
 * 重要设计：这里【不做任何 Java 字符串/数组转换】。
 * 手写 JNINativeInterface_ 的函数表偏移是高风险操作（GetByteArrayRegion
 * 在表中的位置远在 208 之后，208 实际是 EnsureLocalCapacity，算错就会跳飞）。
 * 因此改为：
 *   - 路径由 native 侧固定推导（app 私有 files 目录 + 固定文件名）
 *   - Java 只传密钥，脚本内容由 Java 写文件，结果由 Java 读文件
 */
#include <stdint.h>

typedef int32_t jint;
typedef int64_t jlong;
typedef void *jobject;
typedef void *jstring;
/*
 * JNIEnv 就是一个指向函数表的指针。函数表第 169 项是 GetStringUTFChars，
 * 第 170 项是 ReleaseStringUTFChars（JNINativeInterface_ 定义顺序，
 * 从 reserved0..3 起算，JNI 1.6 起未变）。
 * 只用这两项，别的都不碰 —— 手写偏移越少越安全。
 */
typedef const struct JNINativeInterface_ *JNIEnv;
typedef const char *(*fn_get_utf)(JNIEnv *, jstring, unsigned char *);
typedef void (*fn_rel_utf)(JNIEnv *, jstring, const char *);

extern long cairn_authenticate(uint64_t k0, uint64_t k1);
extern long cairn_probe(void);
extern long cairn_getuid(void);
extern long cairn_spawn_root(uint64_t k0, uint64_t k1,
                             const char *cmd_path, const char *out_path);

jlong Java_com_cairn_manager_Native_auth(JNIEnv *env, jobject thiz,
                                         jlong k0, jlong k1)
{
    (void)env; (void)thiz;
    return (jlong)cairn_authenticate((uint64_t)k0, (uint64_t)k1);
}

jlong Java_com_cairn_manager_Native_probe(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return (jlong)cairn_probe();
}

jlong Java_com_cairn_manager_Native_getuid(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return (jlong)cairn_getuid();
}

/*
 * spawnRoot(k0, k1, cmdPath, outPath) —— 由 Java 以 String 传入。
 * 这里用 GetStringUTFChars 的稳定偏移会踩上面的坑，故改为让 Java
 * 只传「脚本文件序号 + 结果文件序号」不可行；最终采用 String 但
 * 用 ART 上稳定的 jstring 内部布局：不，仍然不可靠。
 *
 * => 真正采用：路径常量在 native 与 Java 两侧保持同一字符串。
 */
extern const char *cairn_cmd_path;
extern const char *cairn_out_path;

/*
 * spawnRoot(k0, k1, cmdPath, outPath)
 *
 * 真机 11:05:41 日志实证：refreshStatusAsync 会被 onCreate / onResume /
 * 点按钮并发触发多次，而旧签名把 cairn.cmd / cairn.out 写死在 native 里，
 * 所有并发调用共用同一对文件 —— B 调用 O_TRUNC 掉 A 的输出，
 * A 读到的就是 B 的脚本结果，CAIRN_UID=0 找不到，状态永远停在检测中。
 * 所以路径必须由 Java 每次传入唯一文件名。
 */
jlong Java_com_cairn_manager_Native_spawnRoot(JNIEnv *env, jobject thiz,
                                              jlong k0, jlong k1,
                                              jstring jcmd, jstring jout)
{
    (void)thiz;
    if (!jcmd || !jout) return -3;
    const void **ftab = (const void **)(*env);
    fn_get_utf get_utf = (fn_get_utf)ftab[169];
    fn_rel_utf rel_utf = (fn_rel_utf)ftab[170];
    const char *cp = get_utf(env, jcmd, 0);
    const char *op = get_utf(env, jout, 0);
    if (!cp || !op) {
        if (cp) rel_utf(env, jcmd, cp);
        if (op) rel_utf(env, jout, op);
        return -3;
    }
    long ret = cairn_spawn_root((uint64_t)k0, (uint64_t)k1, cp, op);
    rel_utf(env, jcmd, cp);
    rel_utf(env, jout, op);
    return (jlong)ret;
}
