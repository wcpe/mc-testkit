# ADR-0023：serve 附加控制台改用 pty4j，跨平台支持 Windows ConPTY

## 状态

已接受，取代 [ADR-0022](0022-serve-attach-console-pty.md) 中「只用 POSIX `script`、不引原生依赖」的实现决策；保留 ADR-0022 的附加控制台协议、单会话、回环令牌、日志清洗和默认关闭原则。（决策 2 中「JLine 用 `jline-terminal-jna`」的构件选择已被 [ADR-0024](0024-attach-console-jline-native-provider.md) 取代。）

## 背景

ADR-0022 的第一版附加控制台使用 Linux/macOS 的系统 `script` 分配 PTY，优点是零第三方原生依赖，缺点是 Windows 无法使用。用户明确要求 Windows 可用，需要接入 Windows ConPTY；同时 attach 客户端原先用 POSIX `stty` 管理 raw 模式，也无法在 Windows 控制台运行。

pty4j 提供统一的 Java API：`PtyProcessBuilder` 创建 PTY 子进程、`PtyProcess.setWinSize` 调整尺寸，并在 Windows 优先走 ConPTY、必要时由库回退 WinPty。JLine Terminal API 可在 Linux/macOS/Windows 上统一管理 attach 客户端当前终端的 raw 模式、尺寸和属性恢复。

## 决策

1. 使用 `org.jetbrains.pty4j:pty4j:0.13.13` 作为 PTY 底座；Windows 调用 `setUseWinConPty(true)`，由 pty4j 处理 ConPTY/WinPty 路径；Linux/macOS 也统一走 pty4j，不再调用 `script`、`stty`、`ps`。
2. 使用 JLine `jline-terminal` + `jline-terminal-jna` 管理 attach 客户端终端：`TerminalBuilder.system(true)` → `enterRawMode()` → 字节桥 → `setAttributes(saved)` 恢复；尺寸从 JLine Terminal 读取后通过 host 回调调用 pty4j 的 `setWinSize`。
3. `ServerLauncher.launch(pty = false)` 的默认管道 / 日志行为不变；`pty = true` 才创建 `PtyProcess`，stdout/stderr 交调用方消费，pid 仍落盘，收尾先清理后代再清理父进程。
4. 由于 pty4j 0.13.13 的发布物包含 Kotlin 2.1 元数据，而本项目源码/API 必须锁 Kotlin 1.9：
   - 排除 pty4j 的传递 Kotlin stdlib，继续使用项目自己的 Kotlin 1.9 runtime；
   - Kotlin 编译任务只增加 `-Xskip-metadata-version-check` 以允许读取该外部库元数据；不改变源码语言版本、API 版本、目标字节码或消费方 Kotlin 兼容契约；
   - PTY 尺寸使用 pty4j 的 `WinSize` 类型，ConPTY/WinPty 的运行时能力由 pty4j 原生库提供。
5. pty4j/JLine native 加载失败、非 TTY 或平台能力异常时，`attachConsole` 只给中文说明并退回已有的行级控制台；默认 `attachConsole = false`，普通 e2e/stress 路径不改变。
6. Windows CI 增加独立 job，运行 JDK 17 + `gradlew.bat build`，验证依赖解析、native backend 加载和单测；真实 Paper attach 仍作为手动 E2E。

## 理由

- **Windows 覆盖**：ConPTY 是 Windows 原生终端接口，pty4j 已封装 ConPTY 与 WinPty fallback；继续依赖 `script` 无法覆盖 Windows。
- **统一尺寸与终端状态**：服务端尺寸由 `WinSize` 设置，客户端 raw/恢复由 JLine 统一处理，不再维护两套 POSIX shell 命令。
- **保持兼容**：pty 只在 opt-in 路径启用；默认管道、日志、任务名、DSL 语义不变。
- **可控依赖风险**：pty4j/JLine/JNA 的版本固定并检查依赖树；pty4j 的 Kotlin 元数据兼容处理集中在构建规则，不升级项目 Kotlin 语言/API。

## 后果

- 附加控制台支持 Linux/macOS/Windows；Windows 需要支持 ConPTY 的系统版本，pty4j native backend 加载失败时仍能退回行级控制台。
- 插件运行时新增 pty4j、JLine terminal、JLine terminal-jna、JNA 依赖；离线消费方需要提前缓存这些构件。
- PTY 模式 `<key>.log` 仍是终端流清洗后的日志；attach 客户端的终端输出保持原始字节，不由框架解析命令。
- pty4j 版本升级必须重新验证 Java 17、Kotlin 元数据、Windows ConPTY/WinPty、Linux/macOS PTY 和 native 资源加载。

## 被否方案

- **继续使用系统 `script`**：Windows 无统一等价物，无法满足要求，已被本 ADR 取代。
- **只在 Windows 自己调用 ConPTY API**：会形成 Windows 专用分支，Linux/macOS 仍需另一套实现；pty4j 已提供统一抽象，避免重复维护。
- **引入 JLine 自带的完整服务端命令编辑器**：服务端本身已经有 JLine；客户端只需管理当前终端状态并转发字节，避免重复实现命令树。
