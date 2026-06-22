# 功能规格：FR-08 首个项目接入验证（实机）

> 状态：开发中　·　关联 PRD：FR-08、§6 验收　·　分支：master

## 1. 背景与目标

把首个真实消费者接入 mc-testkit 插件，**实机**跑通 `e2eSmoke` 与「经 Waterfall 代理购买」，作为整套编排的真源验证（单元/TestKit 全绿不替代实机，PRD §6）。本 FR 是 P1 capstone：唯有它能暴露各 FR 在隔离测试里照不到的**集成缝**。

## 2. 需求（要什么）

- 范围内：① 消费者经插件（`includeBuild` / 发布）应用 mc-testkit + `mcTestkit { }` 声明拓扑/场景/依赖；② `e2eSmoke` 实机 PASS；③「经 Waterfall 代理购买」实机 PASS（需真实依赖库/服务端模板/MySQL/Redis）。
- 不做：不在通用框架里写消费者业务场景；不为 buy 场景把业务 bot env（业务场景所需的标题/槽位/期望结果等）固化进框架契约。

## 3. 设计（怎么做）+ 实机暴露并修复的集成缝

- **消费方式**：`includeBuild("../mc-testkit")` 接入本地插件（验证期最便捷，无需发布、改动即生效）。
- **缝① 桩↔编排「场景/结果文件」交接（FR-08 修）**：verify 读 `<resultsDir>/<scenario>.properties`，但 prepare 从不告知桩「跑哪个场景、把结果写哪」。修复：契约新增 `MC_TESTKIT_E2E_SCENARIO` / `MC_TESTKIT_E2E_RESULT_FILE`；FR-04 起后端时经 `ServerLauncher.environment` 下发（结果路径 = verify 读取处，绝对路径）；template 桩 `HarnessConfig` 优先读这两个 env（覆盖 config.yml）。让通用编排无需知道桩的配置格式即可对齐结果位置。
- **缝② 桩 jar 未内联 kotlin-stdlib（FR-07 修）**：Kotlin 写的桩在真实 Paper `onEnable` 抛 `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`——桩 jar 是瘦 jar、未打入运行期 kotlin-stdlib。修复：`template/harness` 的 `jar` 任务打入 `runtimeClasspath`（fat jar），paper-api 因 compileOnly 不在运行期故不会被打入。
- **缝③ 业务 bot env 透传（buy 修）**：`scenario { bot { env(name, value) } }` 最小加一个透传位，把消费方业务 env（业务场景所需的标题/槽位/期望结果等）原样传给机器人，不进框架契约（DSL 顶层形态不破）。
- **缝④ 服务端模板 seeding（buy 修）**：prepare 在提供 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR` 时把模板（依赖插件配置 / 被测插件 test 业务配置）铺进运行目录（排除世界/日志），被测插件方有其测试配置。
- **缝⑤ 机器人连接超时默认（buy 修）**：默认连接重试窗口 20s→**180s**——真实后端含数据源/Redis 初始化常需数十秒启动（实测首个接入项目+MySQL 约 60s），20s 太短机器人会过早放弃。
- **缝⑥ 后端 JVM 收尾（buy 修，高风险区）**：`runBackendForeground` 改为**以结果文件为权威完成信号**——真实后端依赖（连接池非守护线程）使 JVM 在 `Bukkit.shutdown` 后不退出；结果写出后给 30s 优雅自停窗口，仍不退则强杀，避免空等到 600s 上限、且端口干净释放。
- **迁移消费者（首个接入项目）**：其桩 `HarnessConfig` 读 `MC_TESTKIT_E2E_SCENARIO/RESULT_FILE`、其机器人 `connectAndWait.js` 连接维度读 `MC_TESTKIT_E2E_BOT_*`（均 dual-name 回退原名，不破原有路径）；服务端模板补被测插件 test 业务配置。

## 4. 任务拆分

- [x] 消费者经 includeBuild 接入、任务注册可见。
- [x] 修缝①②，实机跑通 `e2eSmoke`（真实下载 Paper + 起服 + 桩判 PASS + 自停 + verify PASS）。
- [x] buy 场景：迁移首个接入项目编排、补缝③④⑤⑥，实机跑通「经 Waterfall 代理购买」。
- [x] doc-sync：契约 env、API.md、CHANGELOG、PRD 状态。

## 5. 验收标准

- `e2eSmoke` **实机 PASS**：真实 Paper 服务端下载并启动、桩写出 `status=PASS`、服务端自停、verify 判 PASS。**已达成**（结果文件 `status=PASS`，三次复跑一致，服务端干净自停；下载缓存命中复用）。
- 「经 Waterfall 代理购买」**实机 PASS**（PRD §6 实机维度）。**已达成**：以首个接入项目为消费者，真实下载 Paper+Waterfall、后端 BungeeCord 模式 + MySQL/Redis、机器人经 Waterfall 代理进服经业务 GUI 购买、桩判定写出 `status=PASS`（含 DB 事务 `txId`、`rewardCount=1`、`costLeft=0`）、`e2eBuySuccessBotGuiViaWf` BUILD SUCCESSFUL、代理/后端收尾端口干净释放。

## 6. 风险 / 待定

- buy 场景需真实依赖（某依赖插件等）+ DB/Redis + 业务 bot env，接入工作量大且会再暴露集成缝（缝③等），按 FR-08 capstone 预期逐个修。
- Velocity 经代理的 modern-forwarding 配置未生成（Waterfall 路径完整），与 buy 验收无关（buy 经 Waterfall）。
