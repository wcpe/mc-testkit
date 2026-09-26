# ADR-0022：serve 附加控制台——给服务端分配 PTY，另开终端 attach 得到原版控制台

## 状态

已被 [ADR-0023](0023-windows-pty4j-console.md) 取代（保留历史正文；默认关闭、attach 协议、单会话、日志清洗与收尾原则仍沿用）

## 背景

serve 的控制台能力分两步走完：先接通「终端输入 → 服务端 stdin」（0.13.0），再由框架在行级实现 Tab 补全与历史（0.14.x，见 ADR-0021）。ADR-0021 当时判定 PTY 路线落选，理由是「用户侧仍是按行转发，PTY 换来的只是更晚出现的补全结果与双回显」。

用户随后提出：**能不能真的把终端接管过去？** 这要求重新审视那个判定——尤其是「服务端自己那套 JLine 到底能不能用起来」。

实测（真实 Paper 1.20.1，2026-09-27）：

| 事实 | 证据 |
|---|---|
| 给服务端 PTY 后，它的 JLine 立刻激活 | `script -qec "java -jar paper.jar --nogui" /dev/null` 启动：日志里 **0 次** `Advanced terminal features are not available in this environment`（管道形态下必现），并出现 JLine 的 ANSI 绘制（清行 `ESC[K`、带色提示符） |
| 往 PTY 写原始按键，服务端会**真补全** | 往 wrapper stdin 写 `whi\t`（原始 Tab）→ 服务端输出 `whil elist<--[HERE]` 形态的补全提示（JLine 的候选与 `<--[HERE]` 光标标记）；再写 `list\n` → 命令执行、输出正常 |
| 杀掉 wrapper，服务端**不残留** | 杀 `script` 后其子服务端进程随之消失（pty master 关闭 → 服务端控制终端消失）；实测 `pgrep` 前后为空 |
| pty 尺寸可从外部设置 | pty 内 `stty size` 得 `0 0`（无尺寸）；用 `stty -F /dev/pts/N rows 30 cols 120` 从外部改成功（`/dev/pts/N` 由 `ps -o tty= -p <服务端 pid>` 推出） |
| Paper 的 RCON 给不出补全 | 反编译 `net.minecraft.server.rcon.thread.RemoteControlSession`：只有命令分发（`RemoteControlCommandListener`），无 suggestion 通道 → 「用 RCON 换补全」不可行 |

也就是说：**PTY 正是缺失的那一半**（服务端侧），而「按键」那一半必须由一个**握着真终端**的进程提供——它不可能是 Gradle 守护进程（实测 `inheritIO` 连输出都到不了终端，见 ADR-0021），只能是**开发者自己在另一个终端里跑的一个小客户端**。

## 决策

新增**可选**的「附加控制台」能力（`serve { attachConsole = true }`）：

1. **服务端跑在 PTY 里**：用平台自带的 PTY 分配器包装启动命令——Linux（util-linux）`script -qec "<cmd>" /dev/null`、macOS（BSD script）`script -q /dev/null <cmd...>`；并在 pty 内先设默认尺寸（避免 0×0 下 JLine 折行错乱）。**不引入第三方依赖**。
2. **另开终端 attach**：serve 在 `127.0.0.1` 随机端口开一个端到端字节桥（随机令牌校验、单会话），就绪提示里打印**可直接粘贴**的客户端命令（客户端随插件 jar 发布）。该客户端只做三件事：把本终端切到 raw、把字节双向搬运、退出时恢复终端；**编辑器与补全完全由服务端 JLine 提供**（因此补全含参数、行编辑、颜色、Ctrl+C 停服都是原版行为）。
3. **不 opt-in 就一切照旧**：默认仍是管道 + 行级补全 + 干净日志（ADR-0021 的形态保留）。
4. **PTY 不可用不阻断**：Windows（无 POSIX `script`）或无该命令时，中文说明原因并退回默认形态；补全/历史仍可用（只是没有原生控制台）。
5. **收尾语义不变**：wrapper 被杀时服务端随之退出（实测），另加「先杀后代进程」兜底；pid 文件与进程台账照旧。
6. **日志语义在 opt-in 下改变**：`<key>.log` 记录的是服务端的**终端流**（含 JLine 转义与提示符重绘）——这是「服务端真的拿到终端」的必然结果；Gradle 控制台视图与命令表抓取走清洗后的文本（去 ANSI / 纯提示符片段）。

## 理由

- **只有 PTY 能同时满足「原版体验」与「框架编排不丢」**：手工 `java -jar`（此前提出的另一条路）能拿到原版控制台，但会丢掉插件注入、桩空闲、日志跟随与按 pid/台账收尾；本方案让服务端仍在框架的编排里，只是多了一条「真终端」的交互通道。
- **不引依赖优先**：PTY 分配用系统自带 `script`（Linux util-linux 与 macOS 自带），避免为一个交互增强引入原生库（pty4j 之类）——那会改变本插件的依赖面与离线/CI 友好性（NFR）。
- **按键侧只能由用户终端承担**：Gradle 守护进程拿不到终端（已实测），把终端切到 raw 的进程必须就是「用户直接运行的那个客户端」——这也是终端的物理属性决定的，不是实现选择。
- **默认不变**：新能力对既有用户零影响；日志语义变化只在 opt-in 时发生，并在 API/OPERATIONS 写明。

## 后果

- 手测体验对齐原版：补全（含参数）、历史、行编辑、颜色、Ctrl+C 停服都由服务端 JLine 提供；框架侧的行级补全仍服务「不 attach 也能用」的场景。
- 增加了三类新组件：PTY 包装（`ServerLauncher.launch(pty = ...)`）、attach 端点与协议（`console/ServeConsoleHost`）、attach 客户端（`console/ServeConsoleAttach`，随 jar 发布，需要被 `java -cp` 直接启动）。
- **平台面变宽但不均匀**：POSIX 可用；Windows 明确不支持（要支持需引入原生库，属独立决策）。
- opt-in 时 `<key>.log` 是终端流：`grep`/`cat -v` 可读，但不再是纯文本；命令表抓取与日志跟随必须是 ANSI 容忍的（已在实现里清洗）。
- attach 客户端需要能拿到插件 jar 与 kotlin-stdlib 的路径——就绪提示里的命令由框架按自身类源（`codeSource`）拼出，消费方不需要自己拼。

## 备选方案

- **维持 ADR-0021 的判定（不做 PTY）**：够用但永远拿不到参数补全与行内编辑；用户明确要原版体验，落选。
- **手工起服脚本**（serve 铺好目录后由用户自己 `java -jar`）：零成本、100% 原版，但绕过框架编排（插件注入/桩空闲/日志/收尾都要用户自理）——保留为文档里的退路，不作为主方案。
- **pty4j / ConPTY 原生库**：能覆盖 Windows，但要改本插件的依赖面（离线/CI 友好性受影响），且第一版没有 Windows 需求；记为将来若要覆盖 Windows 的路径。
- **经 RCON 取补全**：实测 Paper 的 RCON 没有 suggestion 通道；且只覆盖 Bukkit 系，落选。
- **框架自己在 Gradle 里做行编辑器**：Gradle 客户端终端处于规范模式、回显与退格由终端驱动处理（ADR-0021 证据表），拿不到逐键输入，落选。
- **让 attach 客户端自己实现补全**：客户端只做字节桥是最省事也最忠实的选择；自己实现等于把 JLine 重写一遍，且拿不到服务端的命令树。落选。
