# ADR-0008：集群 / 压测以「扩展 scenario 块」表达，编排走「后台多后端 + 轮询结果文件」

## 状态
已接受（补充 [ADR-0006](0006-public-contract-conventions.md) 的 DSL/命名契约与 [ADR-0004](0004-orchestration-model.md) 的编排模型，不取代）

## 背景
v0.1.0 只覆盖「单后端（±1 代理）」。下游消费者要把跨服一致性与压测 E2E 迁到 mc-testkit，缺两块：① 多后端集群（N 后端同 data-group 在线 + bot 经代理 `/server` 切换 + 桩跨服判定）；② 压测（N bot 峰值 + M 轮，桩聚合）。这是 PRD §7 第三期「更多拓扑形态、并发压测沉淀」，为解锁下游迁移**提前到 v0.2.0** 做（FR-10 集群、FR-11 压测）。

约束：ADR-0006 冻结了 DSL 四个顶层块（backend/proxy/scenario/dependencies）；env 契约已预留 `CLUSTER_*`/`STRESS_*`/`PROXY_BASE_PORT`；ADR-0004 已提「后台集群后端」。须在既有契约内扩展，不另起炉灶、不破坏冻结块。

## 决策
1. **DSL：扩展 scenario 块，不加顶层块**（守 ADR-0006 的顶层冻结）。
   - 集群：`scenario("x") { backends("s1","s2"); via = "wf"; bot { action = "..." } }`——新增 `ScenarioSpec.backends(vararg)`（与既有单后端 `backend =` 互斥）。声明 ≥1 个 `backends(...)` 即「集群场景」，**必须** `via` 一个代理（`/server` 切换需代理）。
   - 压测（FR-11，本 ADR 一并定形、留待实现）：场景加压测维度（峰值 bot 数 / 轮次）+ 复用 `STRESS_*`/`CLUSTER_*` 规模 env。
2. **任务命名**（补 ADR-0006 命名约定，曾标「集群/压测任务名随后续补全」）：集群 `e2e<Key>Cluster`、压测 `e2e<Key>Stress`；收尾 `stop<Key>Cluster`。
3. **集群编排模型**（补 ADR-0004）：N 后端**全部后台 JVM**（无前台自停那个），代理后台、bot 后台；**以桩写出结果文件为权威完成信号**（轮询结果文件出现，非死等某后端自停）；正常/失败/中断三路径都按 pid `finalizedBy` + `try/finally` 双保险收尾 N 后端 + 代理，端口干净。
4. **桩 / 机器人交接**：所有后端的桩拿**同一** `SCENARIO` + `RESULT_FILE`（既有契约）；新增 `MC_TESTKIT_E2E_CLUSTER_BACKENDS`（有序后端名，编排→**机器人**下发，bot 据此 `/server <name>` 切换目标）。桩**对称无角色**——靠 bot 切到目标服后发的控制标记触发判定写出（避免给每个后端塞角色 env，简单优先）。
5. **代理集群配置**：BungeeCord 系单 listener + **N 个具名 server**（server 名 = 后端名），`force_default_server` 落首个后端，bot `/server <name>` 在同一 TCP 连接上 fast-transfer 切服。

## 理由
- 扩 scenario 块（加 `backends(...)`）是**加法**、不动冻结的四个顶层块，契约稳定、消费方零迁移成本。
- 全后台 + 轮询结果文件，与 ADR-0004「后台集群后端 + 结果文件为真源」「FR-08 已落地的结果文件驱动完成」一脉相承，复用 provision/serverconfig/bot/verify 各包，不新造轮子。
- 对称桩 + bot 驱动切换，省掉「每后端角色 env」，桩逻辑最薄；`/server` 切换正是下游 FR-19 真实跨服竞态要打的点。

## 后果
- `ScenarioSpec` 多一个 `backends(...)`（与 `backend =` 互斥，配置期校验）；`McTestkitEnv` 多 `CLUSTER_BACKENDS`；`McTestkitTaskNames` 多 `cluster()`（含 `e2e<Key>Cluster`）。均加法，向后兼容。
- 集群场景必须声明 `via`，且该代理须 `routesTo` 覆盖场景的全部后端——配置期校验、中文报错。
- 多后端各自运行目录（`run-<name>`）+ 各自 pid；收尾路径变多，收尾测试是高风险区重点。
- template 增「跨服」示例桩/bot（最薄：演示切换 + 标记判定，真实数据一致性断言由消费方补）。
- 压测（FR-11）按本 ADR 既定方向后续实现，不再另写 ADR（除非偏离）。

## 备选方案
- **加新顶层块**（如 `cluster { }` / `stress { }`）：破坏 ADR-0006 冻结、消费方需认知新结构，落选。
- **多 listener 一端口对一后端**（确定性分流）：适合「每 bot 钉死一服」的压测，但**不支持 `/server` 切换**（跨服场景核心），故集群跨服用「单 listener + N server」；压测分流形态留 FR-11 按需选。
- **前台某个后端自停驱动完成**：多后端无单一前台，且真实后端常不干净退出 JVM（FR-08 已证），故统一走结果文件轮询。
