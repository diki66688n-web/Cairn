package com.cairn.manager;

/**
 * 与 libcairn.so 的 JNI 绑定。
 *
 * 注意：这里【没有】任何会让本进程提权到底的接口暴露给 UI 直接滥用。
 * 提权只发生在 clone 出的子进程里，本进程 uid 永不变。
 */
public final class Native {
    static {
        System.loadLibrary("cairn");
    }

    private Native() {}

    /** 0=成功 -1=密钥错 -2=内核无补丁（会改变本进程凭据，仅取证用） */
    public static native long auth(long k0, long k1);

    /**
     * 内核探针。
     *   +1 => 有补丁（魔数被识别，走的是"密钥错"分支返回 -1）
     *    0 => 无补丁（未知 option，内核原生返回 -22 EINVAL）
     *   -3 => 未知返回码
     */
    public static native long probe();

    public static native long getuid();

    /**
     * 子进程提权执行 cmdPath 指向的脚本，结果写入 outPath。
     *
     * 路径由 Java 每次传入唯一文件名：refreshStatusAsync 会被并发触发多次，
     * 共用同一对文件时后一次 O_TRUNC 会把前一次的输出抹掉。
     * 父进程（本进程）uid 全程不变。
     *
     * @return 0 全部成功；1..254 子进程退出码；-1 clone 失败；-2 子进程被信号杀死
     */
    public static native long spawnRoot(long k0, long k1, String cmdPath, String outPath);
}
