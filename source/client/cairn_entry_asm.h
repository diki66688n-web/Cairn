/*
 * cairn_entry_asm.h —— 用汇编直接定义 _start
 *
 * 为什么不用 __attribute__((naked))：
 *   GCC 对 aarch64 的 naked 支持并不可靠（会报 "naked attribute
 *   directive ignored"），且 naked 函数体内一旦出现任何被编译器
 *   认为需要序言的东西，插入的指令会破坏 [sp] = argc 的约定。
 *   实测：用 naked 版本编译出的 cairnctl 在真机上 100% SIGSEGV，
 *   用本文件的汇编版本则正常（与 ct/t1、ct/t2 验证结果一致）。
 *
 * 内核 execve 后的初始栈布局：
 *   [sp]            = argc
 *   [sp+8]          = argv[0]
 *   [sp+8*argc+8]   = NULL
 *   ...             = envp
 */
__asm__(
    ".text\n"
    ".globl _start\n"
    ".type  _start, %function\n"
    ".align 4\n"
    "_start:\n"
    "    ldr  x0, [sp]        \n"   /* x0 = argc */
    "    add  x1, sp, #8      \n"   /* x1 = argv */
    "    bl   su_main          \n"
    "    mov  x0, #1          \n"   /* 不应返回；返回则 exit(1) */
    "    mov  x8, #93         \n"
    "    svc  #0              \n"
    "    b    .               \n"
    ".size _start, .-_start\n"
);