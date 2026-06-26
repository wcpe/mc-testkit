# 功能规格：FR-16 单场景多 bot

> 状态：已交付@v0.2.2　·　关联 PRD：FR-16、ADR-0009（单场景多 bot 的 scenario 块扩展，补 ADR-0008）、ADR-0006（对外契约·env 前缀/任务命名）　·　分支：master

## 1. 背景与目标

mc-testkit 现状：每个 `scenario { }` 最多一个 `bot { }`——
- 单后端：1 个 bot 直连；
- 集群（FR-10）：1 个 bot 经代理 `/server` 切换；
- 压测（FR-11）：每服 `botsPerServer` 个 bot，但**钉服、同质**（同 action、不切服、不分角色）。

下游消费者 `mctk-allininventorysync-e2e`（AllinInventorySync 跨服 E2E）最后两个受阻场景三种形态都表达不了，都卡在「一个场景里要**多个、可各自不同**的 bot」：

1. **多玩家并发切服**：N 个玩家各自经代理落首服 → `/server` 切次服 → 校验数据不回档。即「N 个会切服的 bot」（同质即可，但要 N 个独立玩家）——压测 bot 不能切，集群单 bot 只有一个。
2. **管理 GUI 双角色**：同一后端要 2 个**不同角色**的 bot——`admin`（OP，经 mineflayer 点管理菜单编辑 target）+ `target`（被编辑对象）。即「同场景多个**异质** bot」。

属 PRD §7 第三期「更多拓扑形态」配套能力，与集群/压测同族（FR-10/11/15 均 P3）；为解锁下游最后两个 E2E 场景而做。

## 2. 需求（要什么）

- 范围内：**单个 scenario 能驱动多个 bot**，两种维度：
  - **异质**：多个 bot 各有 `username` / `action` / `env`（用例 2）；
  - **同质批量**：某 bot 声明 `count = N` 复制 N 份、各唯一 username、经 `BOT_INDEX`（1..N）区分（用例 1）。
  - 用在**集群**（多 bot 各自经代理 `/server` 切，用例 1）与**单后端**（多 bot 直连，可分角色，用例 2）。
  - 每个 bot 进程下发**唯一** `BOT_USERNAME`、各自 `BOT_ACTION`、（同质复制时）`BOT_INDEX`；集群下仍下发 `CLUSTER_BACKENDS`（每个 bot 都能切）。结果仍由桩写、按 username/index 聚合（沿用「结果文件即真源」）。
  - 多 bot 进程随场景结束**全部回收、不残留**（高风险区，见 testing-and-quality §2）。
- 不做（范围外）：
  - **不复用压测 `stress {}` 形态**——压测是「大量同质钉服 bot」，本需求是「少量/批量、可切服、可分角色」，是不同维度（ADR-0009）。压测场景禁用 `count` / 多 bot（规模用 `botsPerServer` 表达）。
  - **不新增 env 名 / 任务名**——复用既有 `BOT_USERNAME` / `BOT_ACTION` / `BOT_INDEX` / `CLUSTER_BACKENDS` 与现有 `e2e<Key>` / `e2e<Key>Cluster` / `launch<Key>Bot` / `e2e<Key>WithBot`（声明多 bot 时这些任务**起多个 bot 进程**）。
  - 不实现真实游戏客户端；不碰后端 `connection-throttle`（消费方后端模板已 `connection-throttle=-1`；代理侧本就 `connection_throttle: -1`，见 ProxyConfig）。
  - 不预置多 bot **业务**示例桩/机器人到 `template/`（g16 跨服数据、gui-edit admin/target 等业务场景由消费方自加）。
    〔后续（未发布版本）补充：为把 FR-16 纳入**自举实机 E2E**，`template/` 增设了一个**通用薄** `multi-bot` 示例场景——
    同质 `count=N` 直连、桩按入服 username 聚合（settle 窗口）写 PASS，不含任何业务玩法，与既有 `cross-server` /
    `continuous-stress` 薄示例同族；唯一 username 的精确断言由 CI grep 完成。见 CHANGELOG 未发布段与 `.github/workflows/e2e.yml`。〕

## 3. 设计（怎么做）

涉及「单场景多 bot」这一 scenario 块扩展决策 → 见 **ADR-0009**（补 ADR-0008，不取代）。此处不重复决策正文。

- **DSL**（`dsl/Specs.kt`）：
  - `BotSpec` 加 `val role: String?`（构造参数，`bot("admin") { }` 的标签；`bot { }` 为 null）与 `var count: Int = 1`（同质复制份数）。
  - `ScenarioSpec`：`mutableBot` 改为 `mutableBots: MutableList<BotSpec>`；`bot(configure)` 追加匿名 bot、新增 `bot(role, configure)` 追加具名 bot；暴露 `botSpecs: List<BotSpec>`；保留 `botSpec: BotSpec?`（= `firstOrNull()`，向后兼容单 bot 读取 + 压测取首个）。
- **纯函数展开器**（`task/BotProcessPlanner.kt`，新增，可穷举单测）：`expand` 把场景 `botSpecs` 展开为每进程一项的 `BotProcessPlan(action, username, key, botIndex?, env)`；`extraEnvironments` 把 plans 折成每进程「追加 env」（进程数 >1 强制唯一 `BOT_USERNAME`、同质下发 `BOT_INDEX`、合入共享 env）——二者皆纯函数、不依赖 Gradle，故契约可穷举单测。展开规则——
  - `action = bot.action ?: bot.role ?: scenarioName`（保持单 bot 旧默认 `action ?: scenarioName`）；
  - 唯一 key（日志/pid，文件名安全）= `bot.role ?: bot.action ?: scenarioName`，`count>1` 追加 `-<i>`；
  - 唯一 username = `bot.username ?: bot.role ?: bot.action ?: scenarioName`，`count>1` 追加 `<i>`（离线名 ≤16 字符、`[A-Za-z0-9_]`，多 bot 请用短基名）；
  - `botIndex`：`count>1` 为 1..N，否则 null（不下发，单 bot 向后兼容）。
- **配置期校验**（`topology/TopologyResolver.kt`）：① `count >= 1`；② 同场景 ≥2 个 bot 时每个须有唯一 `role`（匿名 `bot { }` 只能一个）；③ 压测场景禁 `count>1` / 多 bot（规模用 `botsPerServer`）。均抛**中文** `GradleException`。
- **编排**（`task/McTestkitTasks.kt`）：
  - `launchBotProcess` 加 `key` 参数（分离「BOT_ACTION 分发」与「日志/pid key」），返回 `Process`。
  - 新增 `launchScenarioBots(...)`：`expand` 出 plans、`extraEnvironments` 装配每进程追加 env（唯一 `BOT_USERNAME` / `BOT_INDEX` / 共享 `CLUSTER_BACKENDS`，纯函数），逐对起进程；返回全部 `Process`。
  - 单后端直连 `launch<Key>Bot` 起全部 bot；`e2e<Key>` 的 verify 包 try/finally，结束（成功/失败）按 plan key 收尾全部 bot pid（单 bot 为安全 no-op，向后兼容）。
  - 经代理 / 集群路径：起全部 bot 并在 try/finally 收尾；集群 `stop<Key>Cluster` 加收尾全部 bot pid。
- **契约**：`McTestkitEnv` / `McTestkitTaskNames` **不改**（复用既有名）。

## 4. 任务拆分

- [x] DSL：`BotSpec.role` / `BotSpec.count` + `ScenarioSpec.bot(role){}` / `botSpecs`，保留 `botSpec` 兼容。
- [x] 纯函数：`BotProcessPlanner.expand`（展开）+ `extraEnvironments`（每进程 env 装配）+ 穷举单测（单 bot 向后兼容 / 同质 count=N / 异质双角色 / 混合 / env 契约：唯一名·序号·共享 env·override 覆盖）。
- [x] 配置期校验：count≥1、多 bot 唯一 role、压测禁 count/多 bot + 单测。
- [x] 编排：`launchBotProcess` 加 key、`launchScenarioBots`（env 装配下沉纯函数）；单后端 / 经代理 / 集群三路径起多 bot 并全收尾（集群收尾接线 dry-run 断言）。
- [x] 功能测试（TestKit）：集群 `count=N` 与单后端双角色注册任务 + 集群 finalizedBy 收尾 + 配置期中文报错。
- [x] 文档同步：PRD（FR-16）、ARCHITECTURE（多 bot 机制）、API（DSL + 任务表注）、CHANGELOG、ADR-0009。
- [x] 单元/TestKit + 根 `build` 绿（含 ktlint），向后兼容既有测试不改语义（184 测试全绿、FR-16 新增 34）。
- [ ] 实机（PRD §6 维度，下游闭环）：下游 AllinInventorySync 加 g16 / gui-edit 跑通验收 + 退役 `integration-test/`。

## 5. 验收标准

- **DSL/契约**（单测）：`bot("admin"){}` 记录 role；`count` 记录；`botSpecs` 多项；`botSpec` 返回首个。env/任务名零新增。
- **展开**（单测）：单匿名 bot → key=action、无 index、username 来自 username/action（与旧一致）；`count=8` → 8 项 username `P1..P8`、key `<base>-1..8`、index 1..8；双角色 → 各自 action/username/key。
- **校验**（单测）：count<1 / 多 bot 缺唯一 role / 压测带 count → 中文报错。
- **编排**（TestKit，配置期）：集群 `count=N` 切服 bot 场景注册 `e2e<Key>Cluster` / `stop<Key>Cluster`；单后端双角色场景注册 `launch<Key>Bot` / `e2e<Key>` / `e2e<Key>WithBot`；`tasks` / `help` 成功。
- **向后兼容**（回归）：现有单 `bot { }` 的单后端/集群/压测行为不变、既有测试全绿（新增为纯增量）。
- **实机**（PRD §6 实机维度，需用户在备齐环境确认 / 由下游闭环验收）：
  1. 集群 `count=N`：N 个 bot 各经代理落首服、`/server` 切次服，桩看到 N 玩家两服 join 并按 index 聚合判定 PASS。
  2. 单后端双角色：admin + target 两进程各以自己 username/action 进同一后端，桩分别识别、判定 PASS。
  3. 多 bot 进程随场景结束全部回收、端口无残留。

## 6. 风险 / 待定

- **唯一 username 与离线名长度**：`count` 大时 `<base><i>` 可能超 16 字符或越界字符集 → 文档提示用短基名（同压测）。
- **并发登录限流**：N bot 从 127.0.0.1 并发登录——代理 `config.yml` 本就 `connection_throttle: -1`；后端 `bukkit.yml connection-throttle` 由消费方模板（`SERVER_TEMPLATE_DIR`）置 -1。框架不另写后端 throttle（不越环境契约边界）。
- **收尾**：多 bot（尤集群 N 切服 bot）是收尾高风险区——三路径均 try/finally + 集群 stop 任务按 plan key 收尾全部 bot pid，单 bot 路径保持安全 no-op。
