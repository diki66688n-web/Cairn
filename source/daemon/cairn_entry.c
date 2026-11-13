/*
 * cairn_entry.c —— 守护进程进程入口
 *
 * 用 -nostdlib 编译，没有 libc 的 _start。
 *
 * _start 必须用裸汇编写，不能写 C 函数：
 *   编译器会在 C 函数序言插 stp x29,x30,[sp,#-16]!，
 *   此时 sp 已经减 16，再 ldr [sp] 读到的是保存的 x29，
 *   而不是内核压的 argc。实测表现就是 --selftest 失效、
 *   进程直接进守护循环。
 *
 * 内核 execve 入口布局（AArch64）：
 *   [sp]      = argc
 *   [sp+8]    = argv[0]
 *   ...
 *   [sp+8*(argc+1)] = NULL
 *   [sp+8*(argc+2)] = envp[0]
 */

void cairnd_main(long argc, char **argv, char **envp);

/* 裸汇编入口：不碰 sp，直接从内核给的栈取 argc/argv/envp */
__asm__(
    ".text\n"
    ".global _start\n"
    ".type _start, %function\n"
    "_start:\n"
    "    mov  x9, sp\n"          /* x9 = 原始 sp */
    "    ldr  x0, [x9]\n"        /* argc */
    "    add  x1, x9, #8\n"      /* argv */
    "    add  x2, x0, #1\n"      /* argc+1 */
    "    add  x2, x1, x2, lsl #3\n" /* envp = argv + 8*(argc+1) */
    "    bl   cairnd_main\n"
    /* cairnd_main 正常不返回；返回到这里说明异常，直接 exit(1) */
    "    mov  x8, #93\n"         /* SYS_exit */
    "    mov  x0, #1\n"
    "    svc  #0\n"
    "1:  b    1b\n"
    ".size _start, .-_start\n"
);
