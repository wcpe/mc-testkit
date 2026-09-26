# 功能规格：serve 附加控制台（PTY，原版控制台体验）

> 状态：实现完成（待发版）　·　关联 PRD：FR-25　·　关联决策：[ADR-0022](../adr/0022-serve-attach-console-pty.md)　·　依赖：FR-17（serve 内核）

## 1. 背景与目标

serve 挂住后能在 Gradle 终端里敲服务端命令，也有框架侧的行级 Tab 补全与历史（FR-17 增补）。但 **Gradle 的终端归客户端进程所有**，构建（守护进程）只拿到回车后的整行，服务端自己在管道 stdin 下也没有终端——于是「参数补全、行内编辑、颜色」这些原版控制台能力拿不到（实测见 ADR-0021 的证据表）。

**目标**：给服务端一个**真终端**（PTY），并让开发者**在自己另一个终端里 attach** 上去，从而拿到**原版控制台体验**（Tab 补全含参数、↑↓ 历史、←→ 行编辑、颜色、Ctrl+C 停服），同时不放弃框架既有的编排与排障能力（插件注入、桩空闲、日志跟随、按 pid/台账收尾）。

**范围内**：可选 DSL 声明 `serve { attachConsole = true }`；PTY 启动服务端；本地回环的 attach 端点（随机端口 + 随机令牌）；随 jar 发布的 attach 客户端；attach 时同步终端尺寸；PTY 模式下的日志与终端流处理。
**范围外**：Windows（无 POSIX `script`，此时明确提示并保持现状形态）；同时多个 attach 会话；attach 期间改窗口大小（附 attach 时同步一次）；把 attach 端点暴露到本机之外。

## 2. 需求（要什么）

- `serve("dev") { attachConsole = true }` 后，`serveDev` 把服务端放进 PTY 启动，并在就绪时打印**可直接复制执行的 attach 命令**（含插件 jar 路径、随机端口与令牌）。
- 在另一个终端跑该命令 → 该终端成为**服务端的原版控制台**：补全（含参数由服务端命令树给出）、历史、行编辑、颜色、Ctrl+C 停服，均为服务端 JLine 的原生行为；客户端只做字节桥，不自己实现编辑器。
- **不 opt-in 则一切照旧**（现有行级补全 / 历史 / 干净日志），避免影响既有体验与契约。
- PTY 不可用时（Windows / 无 `script`）**不阻断 serve**：中文说明原因，并按现状形态继续（行级补全仍可用）。
- 收尾语义不变：Ctrl+C / `stop<Key>Serve` / shutdown hook **三路都不残留**——PTY 包装器被杀时服务端随之退出（已实测：杀 wrapper → 服务端进程消失），另加**后代进程先杀**兜底。
- 该能力**只 serve 接线**：`e2e*` 自动化任务不受影响（结果确定性）。

## 3. 设计（怎么做）

决策与实测证据见 [ADR-0022](../adr/0022-serve-attach-console-pty.md)，此处只记落地面。

**PTY 分配**（`provision/ServerLauncher`）：`launch(...)` 新增可选参数 `pty: Boolean = false`（加法、非破坏）。为 true 时在 java 命令前套一层平台 PTY 分配器：

- Linux（util-linux）：`script -qec "<quoted java cmd>" /dev/null`
- macOS（BSD script）：`script -q /dev/null <java cmd...>`

并在 pty 内先设一个默认窗口尺寸（`stty rows 50 cols 200; exec <java cmd>`），避免 0×0 尺寸下 JLine 折行错乱。**可用性探测**：就绪前用一条极短命令试跑该分配器，失败即判定不可用（不抛、不阻断）。

**输出处理**：PTY 模式下 stdout **不重定向到文件**（否则无法同时桥给 attach 客户端），由调用方消费：

- 原始终端流写入 `<key>.log`（忠实记录服务端的终端流，含 JLine 转义与提示符重绘；该模式下日志用途与含义在 API/OPERATIONS 写明）；
- 同一份流经 `console/ConsoleOutput` 清洗（去 ANSI、去纯提示符片段、去 `\r`）后交给**控制台跟随**与**命令表抓取解析**（隐藏窗口机制沿用），Gradle 控制台视图保持可读。

**attach 端点与协议**（`console/ServeConsoleHost`）：

- `ServerSocket` 绑定 `127.0.0.1` 随机端口；令牌随机（32 hex），就绪提示里一并打印。
- 握手：客户端首行发 `MC_TESTKIT_ATTACH <token> <cols> <rows>\n`；令牌不符即断开并记 warn。单会话（已有会话时新连接被拒并提示）。
- 握手通过后：`socket ↔ pty 进程 stdin/stdout` 双向字节桥（不解析、不改写）；并按客户端报的尺寸用 `stty` 设 pty 尺寸（best-effort：设备路径经 `ps -o tty= -p <服务端 pid>` 推出，失败静默）。
- 会话结束（客户端断开 / 服务端退出）时释放会话；服务端进程照旧由既有三路收尾。

**attach 客户端**（`console/ServeConsoleAttach`，随插件 jar 发布，`main` 入口）：

- 连接端点 → 发握手 → `stty raw -echo`（保持 `-isig`，Ctrl+C 交给服务端）→ 双向桥接自身终端与 socket；
- `Ctrl+]` 断开（不触发服务端任何行为），退出前用 `stty -g` 保存的设置**恢复终端**（异常路径也恢复，避免留下 raw 终端）；
- 打印中文使用提示与「服务端仍在运行」的说明。

**就绪提示**（serve 任务）：`attachConsole = true` 且 PTY 可用时，追加两行：能力说明 + 可直接粘贴的命令；不可用时给出中文原因与「仅本终端行级补全」的说明。

**不变量遵守**：不反依赖消费项目 / `template/`；不新增下载/运行第三方库（PTY 用系统 `script`，POSIX 自带）；`e2e*` 不接线；中文分级日志；不写死本机绝对路径（路径均由 `RunLayout` 推导）。

## 4. 任务拆分

- [x] ADR-0022（PTY + attach 控制台；修正 ADR-0021「不引 PTY」一条）
- [x] `ServerLauncher.launch(pty = ...)`：PTY 分配器命令构造 + 可用性探测 + 输出交调用方（含单测：命令构造 / 平台分支 / 探测失败退化）
- [x] `console/ConsoleOutput`：终端流清洗（去 ANSI / 提示符残留 / `\r`，应用退格）纯函数 + 单测（含真实 PTY 样本）
- [x] `console/ServeConsoleHost`：端点、握手、单会话、字节桥、pty 尺寸同步 + 单测（握手校验 / 拒绝规则 / 双向搬运）
- [x] `console/ServeConsoleAttach`：客户端（raw 模式、桥接、Ctrl+] 退出、终端恢复）+ `console/AttachCommand`（类路径推导 + 命令文本，含单测）
- [x] serve 接线：DSL `attachConsole`、PTY 启动分支、输出泵、就绪提示、进程树收尾
- [x] 文档同步：PRD（FR-25 行）、API §3.2.2、ARCHITECTURE §5、OPERATIONS（用法 + 排障）、CHANGELOG、ADR-0021 状态修正
- [x] 验证：`./gradlew build` 全绿；真机端到端见 §5

## 5. 验收标准

- **[自动]** `attachConsole` 未声明时：serve 行为与产物（日志文件、行级补全、就绪提示）与本功能引入前一致。
- **[自动]** PTY 命令构造：Linux / macOS 分支各生成预期命令（含 shell 引用与 `stty` 前置）；`pty = true` 时输出不重定向（由调用方消费）。
- **[自动]** 不可用探测：`script` 缺失 / 非零退出 / 平台不支持三条路径都判不可用、中文说明、不抛（单测注入运行器覆盖）。
- **[自动]** 终端流清洗：ANSI（含 CSI / OSC / `ESC M` 类双字符）、提示符重绘、`\r`、增量回显退格都被还原；命令表解析在清洗后文本上仍能取到命令。
- **[自动]** attach 协议：令牌不符 / 格式非法 / 并发第二个会话被拒（中文日志）；通过后双向字节搬运成立。
- **[真机]** 真实 Paper 1.20.1（2026-09-27 实测）：`attachConsole = true` 启动后，从另一个终端 attach → `whi<Tab>` 由**服务端**补全为 `whitelist<--[HERE]`、`list` 执行并回显、`Ctrl+]` 退出后终端恢复且服务端仍在运行、随后 `stop` 停服且端口释放、无残留进程。
- **[真机]** 退化路径（用必然失败的影子 `script` 模拟）：日志给出「PTY 分配器探测失败…本次退回本终端的行级补全」、serve 照常就绪、命令可用、`stop` 正常收尾；且 help 命令表**没有**漏进控制台（隐藏窗口自愈生效）。
- **[手动，需用户确认]** 在真实项目里 attach 手测：参数补全（如 `whitelist add <Tab>`）、←→ 行编辑、颜色显示符合预期。

## 6. 风险 / 待定

- **平台覆盖**：仅 POSIX（依赖系统 `script`）。Windows 保持现状形态并中文说明；若要覆盖 Windows，需引入 PTY 原生库（pty4j / ConPTY），属独立决策（ADR-0022 备选）。
- **日志语义变化（仅 opt-in 时）**：`<key>.log` 变成服务端**终端流**（含 JLine 转义与提示符重绘），不再是纯文本日志；控制台视图会清洗。这是「服务端真的拿到了终端」的必然代价，文档写明。
- **`script` 依实现差异**：util-linux 与 BSD 的参数形态不同（已按平台分支）；最小发行版可能没有 `script`（判定不可用并提示）。
- **窗口尺寸**：只在 attach 时同步一次；attach 后改窗口大小需重新 attach（可在后续版本加 `SIGWINCH` 轮询）。
- **单会话**：同时只能有一个附加控制台；多人手测需排队（避免两个终端抢同一 pty 造成错乱）。
