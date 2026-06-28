# 功能规格：持久模式可选并起 bot（人机混场，serve）

> 状态：草拟　·　关联 PRD：FR-19　·　分支：master　·　决策：[ADR-0011](../adr/0011-persistent-serve-mode.md)　·　依赖：FR-17（serve 内核）

## 1. 背景与目标

serve（FR-17/18）默认纯手测。有时想让 bot 把环境**驱到某状态**（造点数据、模拟其他玩家、加点轻负载）同时真人连入「人机混场」同测。FR-19 让 `serve { }` **可选**起 bot。P3 / 第三期，serve 模式的 bot 增量（[ADR-0011](../adr/0011-persistent-serve-mode.md)，不另写 ADR）。

## 2. 需求（要什么）

- `ServeSpec` 加 `bot { }` / `bot("role") { }`（复用 `BotSpec`，多 bot 规则同场景 FR-16：各唯一 `role`、`count` 同质复制）。
- `serve<Key>` 起服就绪后起声明的 bot（单后端直连 / 经代理；集群下经 `CLUSTER_BACKENDS` 下发可 `/server` 切），但**不据结果文件判定 / 收尾**——挂住人机混场；起 bot 需 `npmInstallE2eBot`（`dependsOn`）。
- 收尾含 bot：`stop<Key>Serve` / Ctrl+C(shutdown hook) / `finally` **三重**收尾 bot + 后端 + 代理。
- **不做（范围外）**：bot 不判定（serve 不判 PASS/FAIL）；bot 行为由消费方 `action` 决定。**注意**：serve 桩**空闲、不发 `E2E_READY`**，故 serve 用的 bot 应是「自驱」动作（连上即自行动作），不要复用「等桩 ready 信号再动」的场景 action（会干等到超时）。

## 3. 设计（怎么做）

- **DSL**（`dsl/Specs.kt`）：`ServeSpec` 加 `bot()` / `bot(role)` + `botSpecs`（复用 `BotSpec`）。
- **配置期校验**（`TopologyResolver.validateServeBots` + `McTestkitTasks.register` 的 `BotProcessPlanner.firstConflict`）：每 bot `count>=1`、多 bot 唯一 `role`、展开后 key/username 唯一（防 pid 互覆盖漏杀）。
- **任务体**（`task/McTestkitTasks`）：抽出 `launchBots(name, botSpecs, ...)` / `stopBots(name, botSpecs)`（场景与 serve **共用**，`launchScenarioBots` 委托之）；`serveForeground` / `serveClusterForeground` 就绪后 `launchBots`（单后端直连 `protocolVersion=null` / 经代理固定后端版本；集群下发 `CLUSTER_BACKENDS`），`finally` + 停任务按 pid 收尾 bot。`botSpecs` 非空时 `serve<Key>` `dependsOn npmInstallE2eBot`。

## 4. 任务拆分

- [x] DSL：`ServeSpec.bot()` / `bot(role)` + `botSpecs`
- [x] 校验：serve bots count / 唯一 role（`validateServeBots`）+ 展开 key 唯一（`register`）
- [x] 任务：抽 `launchBots` / `stopBots` 共用；serve 就绪后起 bot + 三重收尾含 bot + `dependsOn` npm
- [x] 测试：单元（DSL / 校验）+ TestKit（注册）
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API.md、CHANGELOG
- [x] 本机真机验收（见 §5）

## 5. 验收标准

- **[自动]** serve 记录 bot 声明；配置期校验 serve 多匿名 bot / count<1 → 中文报错；serve 带 bot 注册 `serveDev`（`dependsOn npmInstallE2eBot` 不报错）。
- **[本机真机·自动，已达成]** serve 起声明的 bot 入服、服务端挂住、`stopDevServe` 收尾含 bot、端口释放——已验：bot `Filler` `joined the game`，`stopDevServe` 杀后端 pid + `bot-example-bot.pid`，端口 25605 释放无残留。
- **[手动 / 真机，需用户确认]** 真人客户端 + bot 同时在场手测（需消费方提供「自驱」bot action）。**单元 / 集成全绿不替代此项**。

## 6. 风险 / 待定

- serve 桩空闲不发 `E2E_READY`：serve 的 bot 应用自驱 action（不等桩信号）。template 的 `example-bot` 仅作「能起 bot 入服」的自举演示（它会干等 ready 超时，不影响 serve 挂住）；真实人机混场 bot 由消费方按需写。
