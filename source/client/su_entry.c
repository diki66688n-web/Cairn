/*
 * su_entry.c —— su 客户端 / 管理 CLI 的进程入口
 *
 * 只做一件事：把内核给的初始栈（argc/argv）转交给 C 函数 su_main。
 * 真正定义 _start 的汇编在 cairn_entry_asm.h 里，原因见该文件注释。
 */

void su_main(int argc, char **argv);

#include "cairn_entry_asm.h"