# ADR-0024：attach 客户端的 JLine 原生 provider 改用 terminal-jni

## 状态

已接受，取代 [ADR-0023](0023-windows-pty4j-console.md) 决策 2 中「JLine 用 `jline-terminal-jna`」的构件选择。ADR-0023 的其余决策（pty4j 作 PTY 底座、Windows 优先 ConPTY、opt-in 且默认关闭、单会话与回环令牌）不变。

## 背景

Windows CI 新增的 PTY 冒烟测试（`PtyAttachConsoleSmokeTest`：桩 JLine 控制台放进真 PTY，再经附加控制台端点桥接）在 Windows 上失败，子进程里 JLine 报：

```
WARNING: Unable to create a system terminal, creating a dumb terminal
STUB_TERMINAL:dumb
```

同一份代码在 Linux 通过（`exec` provider 调 `stty`）。定位到 JLine 3.30 的 provider 加载约定——运行期**只按名读取** `META-INF/jline/providers/{name}`（见 `jline-terminal` 自带的 `META-INF/jline/README.md`），标准 SPI 文件 `META-INF/services/org.jline.terminal.spi.TerminalProvider` 仅供 jlink/JPMS 使用。而 `jline-terminal-jna:3.30.16` 只带了旧路径 `META-INF/services/org/jline/terminal/provider/jna`，于是运行期加载不到它（冒烟诊断原样打印）：

```
STUB_PROVIDERS:jna=FAIL:IOException:Unable to find terminal provider jna,...,exec=ok:ansi,dumb=ok:ansi
```

Windows 上因此只剩「jna 加载不到 + exec 需要 `stty`（Windows 没有）」→ 退化 dumb。影响不止冒烟：`ServeConsoleAttach` 明确拒绝 dumb 终端（`isRealTerminal()`），Windows 用户会直接 attach 不上——ADR-0023 要修掉的 Windows 不可用问题仍然存在。

## 决策

1. 客户端 JLine 原生 provider 构件由 `org.jline:jline-terminal-jna:3.30.16` 换为 `org.jline:jline-terminal-jni:3.30.16`：它带 `META-INF/jline/providers/jni`，注册路径与类名都符合 3.30 的运行期约定；原生库随 `jline-native` 提供（`jline-terminal` 的传递依赖，内含 Windows x86_64 / aarch64 DLL）。
2. `AttachCommand` 的客户端类路径按类源改为 JLine 的 `Terminal` / `TerminalBuilder` / `JniTerminalProvider` / `JLineNativeLoader`，不再包含 JNA 类。
3. 冒烟测试的桩服务端改用**客户端同款类路径**（`AttachCommand.classpathEntries()` + 测试模块自身），让「客户端在这台机器上能否拿到真终端」在 CI 上被直接验证，而不只验桩自己。

## 理由

- **按上游约定选构件**：JLine 3.30 已把按名加载收敛到 `META-INF/jline/providers/{name}`，换用已注册的构件，比替上游补一份注册文件（见备选）更稳。
- **不新增原生依赖来源**：`jline-native` 本来就随 `jline-terminal` 进来，`terminal-jni` 只是把它用起来。
- **可验证**：冒烟在两个平台的 CI 上打印终端类型与各 provider 成败，Windows 可用性不再靠「理论上应该行」。

## 后果

- 插件运行时依赖：去掉 `jline-terminal-jna`、加入 `jline-terminal-jni`（都发生在本功能未发布期间，无对外兼容影响）。
- Windows 上 attach 客户端从「拒 attach」变为可用（CI 冒烟给出真终端证据）；Linux/macOS 仍可用，只是 provider 由 `exec` 优先转为 `jni`。
- 若将来 JLine 恢复 `jna` 的按名注册，本决策仍成立——客户端用的是当前版本里唯一已注册的原生 provider。

## 备选方案

- **给 `jline-terminal-jna` 补一份 `META-INF/jline/providers/jna` 放在本插件资源里**：零依赖变更即可让旧路径生效，但等于替上游打补丁——上游改类名或补上同名文件后行为不再可控，故不采用。
- **改用 `jline-terminal-jansi`**：Jansi 也是成熟的 Windows 控制台方案，但会引入第三个 JLine 原生 provider 家族，而 JLine 3.30 的原生路线是 jni/ffm，故不采用。
- **`jline-terminal-ffm`**：需要 JDK 22+，本项目消费方基线是 JDK 17，不采用。
