# ADR-0027：bot 命令执行通道（原版 OP + 插件消息 console 通道 + 机器人侧封装）

## 状态

已接受（新增两条**并列**的 bot 命令执行通道，并补齐机器人侧的封装 API；不取代任何既有决策正文，不改动 ADR-0006 冻结的 env 前缀 / 任务命名 / 结果文件约定）

## 背景

E2E 场景要验的东西，越来越多地落在「执行一条服务端命令之后世界变成什么样」上：改游戏模式、发物品、传送、跑 `/execute` 构造时序。但机器人侧要执行命令，长期只有一条路——`bot.chat("/cmd")`，而它有两处结构性缺口。

### 缺口一：机器人没有权限

`bot.chat("/gamemode creative")` 发出去，服务端回一句「你没有权限」，命令根本没执行。原因很直接：机器人是普通玩家，不是 OP。

框架此前完全不碰权限：`config/` 只写 `server.properties`、`spigot.yml`、`paper-global.yml` 与代理配置，**没有一处写 `ops.json`**；`McTestkitEnv` 的 bot 契约里也没有任何权限相关项。消费方若想要 OP，只能自己往服务端模板里塞一份 `ops.json`——而 `ops.json` 的 `uuid` 必须是离线模式 `OfflinePlayer:<name>` 的 MD5，写错会被服务端**静默忽略**（不报错、不告警，命令就是不生效）。这是个典型的「踩了才知道」的坑。

### 缺口二：机器人拿不到 console 身份，也拿不到命令结果

有些命令只有 console 能发（`/op`、`/reload`、`/save-all` 等），机器人以玩家身份发不了，给 OP 也不行。框架内已有的控制台通道（`ConsoleCommandPump`）只服务 `serve` 模式下**人在终端敲**的场景，机器人进程够不着。

更麻烦的是**结果**：即使命令执行成功，机器人也只能靠正则去解析聊天回显。而聊天回显从来不是可靠信号——`/save-all` 这类命令在 Folia 上取不到「当前区域」时**什么都不写也不报错**，那条成功回执永远不来。ADR-0020 的 `McTestkitServerAssertions` 已经把「判定」挪到了服务端，但「执行命令」这一半还停在聊天文本上。

## 决策

新增两条**并列**通道，各自解决一个缺口，另加机器人侧封装。三条一起构成完整的命令执行能力。

### 一、原版 OP 提权（`bot { op = true }`）

`BotSpec` 新增显式开关 `op`，默认 `false`。

```kotlin
scenario("admin-flow") {
    bot("admin") {
        op = true          // 该 bot 以 OP 身份入服
    }
}
```

开启后，框架在**起服前**按 bot 用户名写后端运行目录的 `ops.json`：

- `uuid` 按离线模式算法派生：`OfflinePlayer:<name>` 的 MD5（带连字符、小写）
- `name` 为 bot 用户名
- `level` 4、`bypassesPlayerLimit` true

覆盖直连 / 经代理 / 集群 / 压测的**每个后端**（一个场景可能有 N 个后端，各自一份 `ops.json`）。

**消费方模板自带 `ops.json` 时合并而非覆盖**：保留其原有条目，只追加缺失的 bot 条目。覆盖会静默剥夺消费方已有的 OP，那是更难排查的故障。

**1.7.10 静默跳过**：该版本本身不支持 bot（`MinecraftVersionGroup.isBotSupported` 为 false），没有 bot 也就没有要提权的对象。

### 二、插件消息 console 命令通道（`E2E_CMD` 协议）

新增一对冻结协议常量（`McTestkitProtocol`）：

| 方向 | 消息 | 说明 |
|---|---|---|
| bot → 桩 | `E2E_CMD:<id>:<command>` | 请求以 console 身份执行 `<command>`；`<id>` 由 bot 生成，用于配对 |
| 桩 → bot | `E2E_CMD_DONE:<id>:<PASS\|FAIL>:<output>` | 执行结果；`<output>` 为单行化的命令输出 / 失败原因 |

**走 Minecraft 自定义 payload（plugin message），不走聊天。** 三个理由：

1. **不进聊天流**：聊天方案要么污染其他玩家的聊天记录，要么得让桩 cancel 事件——而 cancel 又会连带影响别的监听者。payload 天然隔离。
2. **无长度上限**：聊天消息有 256 字符硬上限，长命令（带 NBT 的 `/give`、长 `/execute` 链）会被截断且**截断是静默的**。
3. **玩家不可见**：测试控制流量不该出现在任何玩家屏幕上。

桩侧收到请求后以 `server.getConsoleSender()` 身份 `dispatchCommand`，把返回值与捕获的输出拼成回执。**执行投到主线程**（命令会读写世界对象，Folia 上跨线程会抛线程检查异常），与 `McTestkitServerAssertions` 的线程约定一致。

### 三、机器人侧封装 API（`@wcpe/mc-testkit-bot`）

新增 `execCommand(bot, command)` / `execCommands(bot, commands)`：

```js
const { execCommand } = require('@wcpe/mc-testkit-bot/lib/commands')

const result = await execCommand(bot, 'gamemode creative Steve')
if (!result.ok) throw new Error(result.output)
```

返回 `{ ok: boolean, output: string }`。场景代码不再需要手写协议前缀、生成 id、解析聊天回显、判断命令是否真的跑了。

## 理由

- **两条通道各自不可替代**：OP 走**玩家权限检查路径**——被测插件挂在 `PlayerCommandPreprocessEvent` 上的逻辑、权限节点校验、指令拦截全都会被真的过一遍，这正是 E2E 该验的；console 通道走**服务端权限**，能做到玩家做不到的事。用一条顶另一条都会漏掉一半场景。
- **权限是显式开关而非默认全开**：默认自动提权会让不需要 OP 的场景也被放大权限，掩盖「这个场景其实不该需要 OP」这一信息；显式声明让需求可读、可审计。代价是消费方多写一行，且忘了开会遇到「命令没权限」——但报错来自服务端、指向明确，比默不作声的权限放大更容易发现。
- **payload 而非聊天**：聊天通道的三个缺点（污染、长度上限、可见性）没有一个是能靠实现技巧绕过的，都是通道本身的属性。
- **封装 API 是必要的而非镀金**：裸协议要求消费方拼 id、处理超时、解析回执格式——这三件事每个消费方都会写错一遍。封装的成本是几十行 JS，收益是消除一类重复的静默失效。
- **合并而非覆盖 `ops.json`**：覆盖会剥夺消费方模板里已有的 OP 条目，且**不报错**——属于「静默破坏既有行为」，与框架一贯的「不静默」原则相悖。

## 后果

- 消费方可以用 `bot.chat("/cmd")` 跑需要 OP 的命令（声明 `op = true` 即可），也可以用 `execCommand` 拿 console 身份和结构化结果。
- `BotSpec` 新增一个字段、`McTestkitProtocol` 新增一对常量、`@wcpe/mc-testkit-bot` 新增一个模块。均为**纯加法**，既有场景 DSL / 协议 / 任务命名 / 环境变量前缀全不变（SemVer minor）。
- `ops.json` 会被框架改写；消费方若在运行期手工改它，下一轮 prepare 会覆盖回来。这是「运行目录每轮重铺」的既有语义，不算新约束。
- **不覆盖 Spigot / BungeeCord 的差异**：`ops.json` 是三端通用格式，但各端对 `level` 字段的处理可能不同；本次只保证 Paper / Folia（有测试覆盖的六个平台中的后端部分），其他端行为待真实需求驱动。
- 插件消息通道需要桩侧注册 incoming channel；**消费方的桩必须升级 `harness-core`** 才能用 FR-30/31。这是构件版本约束，不是破坏性变更（旧桩照常工作，只是没有这条能力）。

## 替代方案（均未采纳）

- **默认给所有 bot 提权**：省掉 DSL 开关，但把权限放大变成不可见的默认行为，且违反「不为未来预留 / 按需声明」的取向。落选。
- **只做 OP、不做 console 通道**：`/op`、`/reload`、`/save-all` 这类 console-only 命令永远做不到，验不到「服务端视角」的场景。落选。
- **只做 console 通道、不做 OP**：所有命令都能执行了，但全部绕过玩家权限检查——被测插件的权限逻辑等于没测。落选。
- **用 RCON 做 console 通道**：RCON 是标准协议、覆盖全部版本，但需要额外开端口、管密码、处理连接生命周期，且 ADR-0022 已反编译确认 Paper 的 RCON 没有 suggestion 通道。相比之下插件消息零端口、零凭据、随连接生命周期自然收尾。落选。
- **聊天前缀方案（`E2E_CMD:` 走 `bot.chat`）**：实现最简、可复用现有 `E2E_ASSERT:` 同构模式，但受 256 字上限、污染聊天流、需 cancel 事件。落选。
- **让消费方自己在模板里放 `ops.json`**：框架零改动，但每个消费方都要重新踩一遍 MD5 派生与静默忽略的坑。落选。
- **在 `harness-core` 新增「命令执行 API」**：服务端本来就有 `dispatchCommand`，加一层包装没有收益。落选（这也是本 ADR 初稿的错误判断，已在评审中纠正）。
