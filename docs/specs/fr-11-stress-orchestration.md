# 功能规格：FR-11 压测编排

> 状态：开发中　·　关联 PRD：FR-11、ADR-0008（DSL/编排决策）、ADR-0004（编排模型）　·　分支：master

## 1. 背景与目标

v0.2.0 有了跨服切换集群（FR-10），但首个接入项目的 `continuous-stress`（N 服 × M bot 持续随机购买、验证不超卖）是另一种形态——每个 bot **钉死在一服**持续施压（不 `/server` 切换），现有 DSL 无法表达，迁移卡住。本 FR 让 mc-testkit 能编排「N 服 × M bot 持续压测」：N 后端 + 代理（N-listener 一端口对一后端钉服）或直连 + 每服 M 个 bot 进程持续随机动作 + 各 bot 上报 + 每服结果文件聚合判定。属 PRD §7 第三期「并发压测沉淀」，与 FR-10 同批到 v0.2.0。

## 2. 需求（要什么）

- 范围内：① DSL 在 scenario 块加 `stress { botsPerServer; durationSeconds; randomSeed }`（声明即「压测场景」）；② 编排：后台拉起 N 后端 + 代理（N-listener 一端口对一后端、`priority` 钉服）或直连 + 每服 M 个 bot 进程（唯一名 + `BOT_INDEX` + 共享 seed + duration）；③ 每服桩收集本服各 bot 的 `E2E_STRESS_RESULT` + 到时聚合写**本服**结果文件；④ 框架读全部 per-server 结果文件聚合判定；⑤ 收尾 N 后端 + 代理 + bot，端口干净；⑥ template 薄压测示例桩/bot。
- 不做：**框架不做「不超卖」等业务不变量断言**——那是消费方桩查共享 DB 自行判 PASS/FAIL（框架只收集 + 聚合，守「结果文件唯一权威」、不耦合各家 DB schema）；不内置带 DB 的完整自测（薄自举无 DB）；MySQL/Redis 由消费方提供。

## 3. 设计（怎么做）见 ADR-0008

- **DSL**：`ScenarioSpec.stress { }`（`StressSpec`：`botsPerServer` 必填 >0、`durationSeconds` 必填 >0、`randomSeed` 可选默认固定值）；声明 `stress` 即压测场景，复用 `backends(...)` 表 N 服（≥1），`via` 可选（有 → N-listener 钉服代理；无 → bot 直连后端端口）。
- **校验**（TopologyResolver）：压测场景需 ≥1 `backends`；`botsPerServer` / `durationSeconds` 均 >0；`via` 若设须 `routesTo` 覆盖全部 `backends`；与 `backend =` 互斥。配置期中文报错。
- **任务**：`e2e<Key>Stress`（+ 收尾 `stop<Key>Stress`）。
- **编排**（McTestkitTasks.registerStressTask）：每后端 prepare 独立运行目录 `run-<name>`（模板 seed + 注入 jar + 端口 + BungeeCord 模式 if `via`）→ 全后台起 N 后端（同 `SCENARIO`、各自 per-server `RESULT_FILE`）→ 若 `via` 后台起代理（N-listener 钉服 config）→ 每服起 M 个 bot 进程（连本服端口：`via` 用对应 listener 端口、直连用后端端口；唯一名 `<base>-s<n>-<i>`、`BOT_INDEX`、`STRESS_RANDOM_SEED`、`STRESS_DURATION_SECONDS`、协议版本 if `via`）→ 等所有 per-server 结果文件出现（duration + 宽限超时）→ 读全部聚合判定（任一 FAIL/缺失 → 失败并报哪服）→ finalizedBy/try-finally 双保险收尾全部后端 + 代理（bot 随连接断自停）。
- **契约新增**：env `MC_TESTKIT_E2E_BOT_INDEX`（每 bot 序号）、`MC_TESTKIT_E2E_STRESS_RANDOM_SEED`（共享种子）；`STRESS_DURATION_SECONDS` 复用。ProxyConfig 压测变体 `bungeeStressProxyConfigYml`（N listener 一端口对一后端、`priority` 钉服 + 全后端 servers 段）；RunLayout per-server 结果文件 + per-bot 报告路径。
- **template**：压测场景——桩（首 bot 加入起 duration 计时、收集各 bot `E2E_STRESS_RESULT`、到时聚合写 PASS + 各 bot 摘要 details，不做业务断言）；bot `continuousStress.js`（连服 → 等 `READY` → 按 `seed ^ BOT_INDEX` 播种 RNG → 循环一个假动作 ok/err 计数到 duration → 发 `E2E_STRESS_RESULT:ok=,err=,buckets=`）+ `lib/random.js`（mulberry32 / weightedPick / jitter / sleep）。真·不超卖断言以注释指引消费方在桩里查共享 DB 补。

## 4. 任务拆分

- [ ] PRD FR-11 → 开发中、本 spec（ADR-0008 已定方向，不另写 ADR）。
- [ ] DSL `stress{}` + 契约 env/任务名 + 校验（测试先行）。
- [ ] 编排器 `registerStressTask` + N-listener 代理配置 + per-server 聚合 + 收尾；RunLayout per-server/per-bot 路径。
- [ ] template 薄压测桩/bot 示例 + `lib/random.js`。
- [ ] 实机自举验收 + doc-sync（API/ARCHITECTURE/CHANGELOG）+ 中文提交。

## 5. 验收标准

- 配置期：压测场景非法（无 `backends` / `botsPerServer`≤0 / `durationSeconds`≤0 / `via` 路由未覆盖全部后端 / `backend` 与 `backends` 并用）→ 中文报错；`e2e<Key>Stress` 等任务按命名注册（TestKit）。
- **实机**（PRD §6 实机维度，薄自举）：声明 2 个 Paper 后端 + 1 个 Waterfall（N-listener 钉服路由到二者）+ 压测场景（`botsPerServer` 若干假 bot、短 `durationSeconds`）；`./gradlew e2e<Key>Stress` 真实起 2 后端 + 代理 → 每服 M 个 bot 钉在本服持续跑假动作 → 各 bot 发 `E2E_STRESS_RESULT` → 每服桩聚合写 `status=PASS` → 框架聚合 PASS → 收尾 2 后端 + 代理 + bot **端口全部释放、无残留**。
- 纯函数（压测校验、N-listener ProxyConfig、RunLayout 路径、per-server 聚合判定）穷举单测；收尾（N 后端 + 代理 + N×M bot）是高风险区重点测。
- 真·不超卖（连 MySQL 的业务不变量）由首个接入项目迁移时在消费方桩验证，**不在本 FR 自测范围**。

## 6. 风险 / 待定

- 收尾路径最多（N 后端 + 代理 + N×M bot 进程）：bot 设计为 duration 到自停 + 连接断自停；框架按 pid 收尾后端 + 代理，bot 随连接断自停；实机复验无残留。
- per-server 结果文件聚合：任一服未按时写出 → 判失败并报哪服（同首个接入项目 missing 报服号）。
- N-listener 钉服就绪时机：bot 连对应 listener 端口需该后端已就绪；沿用连接重试窗口。
- 框架不判业务不变量（不超卖）：明确写进 spec / API / template 注释，避免消费方误以为框架自动验证。
