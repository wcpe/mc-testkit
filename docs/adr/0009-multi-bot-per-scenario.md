# ADR-0009：单场景多 bot 以「扩展 scenario 的 bot 声明（role + count）」表达，复用既有 env / 任务名

## 状态
已接受（补充 [ADR-0008](0008-cluster-and-stress-dsl.md) 的「扩 scenario 块」方向与 [ADR-0006](0006-public-contract-conventions.md) 的 DSL/命名契约，不取代）

## 背景
v0.2.1 每个 `scenario { }` 最多一个 `bot { }`：单后端 1 直连、集群 1 经代理 `/server` 切、压测每服 `botsPerServer` 个**钉服同质** bot。下游消费者 `mctk-allininventorysync-e2e` 最后两个受阻 E2E 都卡在「一个场景里要多个、可各自不同的 bot」：① **N 个会切服的玩家**（同质批量、各自经代理切服，校验数据不回档）；② **同后端两个异质角色 bot**（`admin` 经 GUI 编辑 `target`）。压测 bot 钉服不可切、集群单 bot 只有一个，三种现状形态都表达不了。

约束：ADR-0006 冻结 DSL 四个顶层块、env 前缀 `MC_TESTKIT_E2E_`、任务命名约定；ADR-0008 定下「集群/压测以扩 scenario 块表达、不加顶层块」并预留 `BOT_INDEX` / `CLUSTER_BACKENDS` / `BOT_USERNAME`。须在既有契约内**加法扩展**，不破坏冻结块、不与压测语义混淆。

## 决策
1. **DSL：扩展 scenario 的 bot 声明，不加顶层块、不加场景级新块**（守 ADR-0006/0008）。
   - 一个 scenario 可声明**多个** bot；`bot { }`（匿名）与 `bot("<role>") { }`（具名角色）追加到 bot 列表。
   - `BotSpec` 加 `count`（默认 1）：`count = N` 表示**同质复制 N 份**（各唯一 username、经 `BOT_INDEX` 1..N 区分）。
   - 异质用多个具名 `bot("admin"){…}` / `bot("target"){…}`（各自 `username` / `action` / `env`）。
2. **复用既有契约，零新增 env / 任务名**（补 ADR-0006，曾留「env 全集随实现补全」）：
   - 每进程下发**唯一** `BOT_USERNAME`、各自 `BOT_ACTION`、（同质复制时）`BOT_INDEX`（1..N）；集群下仍下发 `CLUSTER_BACKENDS`（每个 bot 都能 `/server` 切）。这些 env 名 ADR-0006/0008 已冻结，不新增。
   - 任务名不新增：`e2e<Key>` / `e2e<Key>Cluster` / `launch<Key>Bot` / `e2e<Key>WithBot` 在声明多 bot 时**起多个 bot 进程**（per-bot env 下发）。
3. **与压测（FR-11）显式划清**：压测是「大量同质钉服 bot」（规模 = `stress { botsPerServer }`、钉服不切、不分角色）；本特性是「少量/批量、可切服、可分角色」，是不同维度。**不复用 `stress {}` 形态**；压测场景禁用 `count` / 多 bot（配置期中文报错，规模仍用 `botsPerServer`）。
4. **唯一性与收尾**：进程数 >1 时**强制唯一** `BOT_USERNAME`（末位合入，盖过消费方单值 override）；唯一日志/pid key 区分各进程。多 bot 随场景结束**全部按 pid 收尾**（三路径 try/finally + 集群 `stop<Key>Cluster` 收尾全部 bot pid），单 bot 路径保持安全 no-op（向后兼容）。
5. **桩仍按结果文件聚合**（守架构不变量「结果文件唯一权威」）：多 bot 共写同一 `<scenario>.properties`，桩按 username/index 聚合判定，框架不另判。

## 理由
- 扩 bot 声明（加 `count` + `bot("role")` 重载）是**加法**、不动冻结顶层块与既有字段语义，契约稳定、消费方零迁移；与 ADR-0008「扩 scenario 块」一脉相承。
- 复用 `BOT_USERNAME` / `BOT_ACTION` / `BOT_INDEX` / `CLUSTER_BACKENDS` 与现有任务名——这些正是为「多 bot / 规模 / 切服」预留的契约，无需新造。
- 与压测划清边界，避免「同质钉服」与「可切服/分角色」两种语义挤进同一个 `stress {}` 造成混淆。

## 后果
- `BotSpec` 多 `role` / `count`；`ScenarioSpec` 由单 `bot` 变 `botSpecs` 列表（保留 `botSpec = firstOrNull()` 兼容单 bot 读取与压测取首个）。均加法，向后兼容。
- 新增纯函数展开器 `BotProcessPlanner`（场景 bot → 每进程 plan），可穷举单测；`launchBotProcess` 加 `key` 参数（分离 action 与日志/pid key）。
- 配置期新增校验：`count >= 1`、同场景 ≥2 bot 须各有唯一 `role`、压测禁 `count` / 多 bot——均中文报错。
- 多 bot（尤集群 N 切服 bot）是收尾高风险区，收尾测试与实机回收为验收重点。
- 并发登录限流：代理 `config.yml` 本就关连接限流；后端 `connection-throttle` 由消费方模板置 -1，框架不越界写后端 throttle。

## 备选方案
- **复用压测 `stress {}` 承载多切服 bot**：把「可切服 + 分角色」塞进「同质钉服」语义，混淆且钉服与切服矛盾，落选（决策 3）。
- **新增场景级 `bots { }` 块 / 顶层块**：破坏 ADR-0006/0008 冻结、消费方需认知新结构，落选；扩既有 `bot` 声明即足够。
- **给每进程新增一套 env（如 `BOT_ROLE` / `BOT_COUNT`）**：`BOT_USERNAME` + `BOT_ACTION` + `BOT_INDEX` 已足以让桩按 username/index 聚合，新增即冗余，落选。
