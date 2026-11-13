# Cairn

**以内核提供权限，以脚本扩展功能。不做模块挂载，不修改 SELinux 策略数据。**

[English](README.md) · [简体中文](README.zh-CN.md)

Cairn 是开发中的 Android root 与脚本模块管理器，由设备专用静态内核补丁、原生控制程序 cnd 和支持模块 WebUI 的 Android 管理器组成。

## 设计原则

- 不通过模块挂载、bind mount 或文件覆盖替换系统分区内容。
- 不修改 SELinux 策略数据，不增加 context type。
- UID 白名单明确授权与撤销，磁盘授权记录同步到内核运行时内存。
- 安装与生效分离：执行安装定制后暂存，重启提交。

**必须区分：** 当前 root 实现对部分 SELinux 权限判定做绕过，因此“不改策略数据”不等于“没有改变任何 SELinux 执行路径”。root 模块脚本尚无隔离沙箱，不能保证恶意第三方脚本不自行调用外部挂载或策略工具。

## 当前能力

- 应用 root 授权、撤销与内核白名单同步。
- KSU ZIP 格式子集：module.prop、customize.sh、post-fs-data.sh、service.sh、webroot/index.html。
- Cairn 自有横幅与英文安装日志页。
- modules_update 暂存和开机提交，启用、禁用、待重启状态。
- WebUI 原生接口：Cairn.version()、Cairn.status()、Cairn.readFile(relativePath)。状态与读取接口需当前会话同意，限定自身模块，不提供任意 root 命令或密钥接口。
- 默认跟随系统语言，设置中可选 English 或简体中文。翻译仍在补齐；安装器输出默认英文，模块脚本自身输出原样保留。

## 当前状态

不是通用刷机包，也不是 KernelSU 的完全替代品。硬编码符号和结构偏移仅针对当前测试的 PLU110 内核，不能在其他设备或其他内核版本直接使用。

wl5 真机开机、root 授权和白名单已由用户确认。后续模块暂存、WebUI 等仅部分完成构建／QEMU 测试，不能冒称真机全量验收。QEMU 测试 init 不等于 Android init 解析器。开机授权恢复依赖解锁后系统广播和管理器密钥可用；SKIPUNZIP、完整 KSU 安装环境、掉电一致性和模块全生命周期仍有待完善。

## 参考与致谢

- KernelSU：模块 ZIP 约定、安装环境与 WebUI 交互参考。
- KernelPatch：研究 root 授权和运行时内存设计思路，不表示复用其源码。
- APatch：安装过程展示参考，Cairn 使用独立横幅。
- Toybox：随包 shell，保留其原许可证与源文件通知。
- Android、LLVM、GNU 工具链。
- LiquidGlass 二进制依赖：来源、版本及许可证尚待审计，未完成前不可视作已满足公开分发条件。

英文 README 含项目链接。参见 [许可证方案](LICENSING.md) 和 [发布检查表](RELEASE_CHECKLIST.md)。

## 开源协议与发布

独立、自有 APP／控制程序代码计划采用 GPL-3.0-only；Linux 衍生或链接进内核的部分按 GPL-2.0-only 及上游适用例外处理。第三方材料保留原协议，不能用 GPLv3 重授权整个 Linux。

当前构建脚本仍依赖本地绝对路径；公开发布前必须完成可复现构建、依赖许可、密钥与隐私清理。不要直接上传整个开发目录，不要上传设备固件、签名密钥、生产授权密钥或私人日志。

## AI 辅助开发说明

本项目部分内容由 AI 辅助制作，包括部分代码、文档、问题排查和测试准备。AI 辅助不代表正确性、安全性或许可证合规保证，也不能替代人工审查与真机验证。本说明不改变第三方代码的来源标注及适用许可证。

## 联系方式

如果发现问题，或对代码来源、参考标注、开源协议及第三方权益有疑问，请联系维护者：[diki66688n@gmail.com](mailto:diki66688n@gmail.com)。请附上相关细节，便于核查和处理。
