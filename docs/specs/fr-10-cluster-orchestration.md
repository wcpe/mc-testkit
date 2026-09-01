# 功能规格：FR-10 多后端集群编排

> 状态：已交付@v0.1.0　·　关联 PRD：FR-10、ADR-0008（DSL/编排决策）、ADR-0004（编排模型）

## 1. 背景与目标

v0.1.0 只支持单后端（±1 代理）。下游消费者的跨服一致性 E2E（双后端同 data-group + bot 经代理 `/server` 切服 + 桩跨服判定）无法表达，迁移卡住。本 FR 让 mc-testkit 能编排「N 后端 + 代理」集群拓扑、驱动 bot 在后端间切换、按结果文件判定。属 PRD §7 第三期「更多拓扑形态」，提前到 v0.2.0 解锁下游。

## 2. 需求（要什么）

- 范围内：① DSL 扩展 scenario 块声明集群（`backends("s1","s2")` + `via`）；② 编排：同时后台拉起 N 后端（各自端口/运行目录、BungeeCord 模式）+ 1 代理（单 listener + N 具名 server）+ 切换 bot；③ 以结果文件为权威判定；④ 收尾 N 后端 + 代理，端口干净；⑤ template 跨服示例桩/bot。
- 不做：压测（FR-11，另做）；不替消费方写真实跨服数据断言（template 示例最薄，演示机制）；MySQL/Redis 仍由消费方提供。

## 3. 设计（怎么做）见 ADR-0008

- **DSL**：`ScenarioSpec.backends(vararg)`（与 `backend =` 互斥）；声明 `backends` 即集群场景，必须 `via`。
- **校验**（TopologyResolver）：集群场景的后端均存在、必须有 `via`、且该代理 `routesTo` 覆盖场景全部后端；`backend` 与 `backends` 不可并用——配置期中文报错。
- **任务**：`e2e<Key>Cluster`（+ 收尾 `stop<Key>Cluster`）。
- **编排**（McTestkitTasks.registerClusterTask）：每后端 prepare 独立运行目录 `run-<name>`（模板 seed + 注入 jar + 端口 + BungeeCord 三件套）→ 全后台起 N 后端（同 `SCENARIO`/`RESULT_FILE`，pid 落盘）→ 后台起代理（集群 config）→ 起 bot（`CLUSTER_BACKENDS` 下发切换目标）→ 轮询结果文件（出现即完成，给优雅窗口）→ verify → finalizedBy/try-finally 双保险收尾全部。
- **契约新增**：env `MC_TESTKIT_E2E_CLUSTER_BACKENDS`（有序后端名→bot）；ProxyConfig 集群变体（单 listener + N server）；RunLayout per-backend 运行目录/pid。
- **template**：跨服场景——桩对称（on join 发 `E2E_READY`、收到 bot 切换标记写 PASS）；bot `cross-server` action（连代理→首服→`/server` 切到目标服→发标记）。

## 4. 任务拆分

- [x] PRD FR-10/11 登记、ADR-0008、本 spec。
- [x] DSL + 契约 + 校验（测试先行）：ScenarioSpec.backends、TaskNames.cluster、env CLUSTER_BACKENDS、TopologyResolver 集群校验。
- [x] 编排器：ProxyConfig 集群变体、RunLayout per-backend、McTestkitTasks.registerClusterTask（含收尾）。
- [x] template 跨服桩/bot 示例。
- [x] 实机验收 + doc-sync（API.md/ARCHITECTURE/CHANGELOG）+ 中文提交。

## 5. 验收标准

- 配置期：集群场景非法（后端不存在 / 无 via / 代理路由未覆盖 / backend 与 backends 并用）→ 中文报错；`e2e<Key>Cluster` 等任务按命名注册（TestKit）。
- **实机**（PRD §6 实机维度）：声明 2 个 Paper 后端（同服务端模板）+ 1 个 Waterfall 路由到二者 + 跨服示例场景；`./gradlew e2e<Key>Cluster` 真实下载+起 2 后端+起代理→bot 连代理落 s1→`/server` s2→桩判 `status=PASS`→收尾 2 后端 + 代理**端口全部释放、无残留**。
- 纯函数（集群校验、ProxyConfig 集群 config、RunLayout 路径）穷举单测；收尾是高风险区重点测。

## 6. 风险 / 待定

- 多后端收尾路径多（N 后端 + 代理 + bot），收尾不净会残留占端口——双保险 + 实机复验端口。
- 代理 `/server` fast-transfer 的就绪时机：bot 切服需目标后端已就绪；沿用 180s 连接窗口与重试。
- template 跨服示例只演示「切换 + 标记判定」，真实跨服数据一致性断言由消费方在桩里补（同 example-bot 占位精神）。
