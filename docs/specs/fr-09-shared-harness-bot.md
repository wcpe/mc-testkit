# 功能规格：共享桩协议胶水库与机器人内核包（harness-core / bot-core）

> 状态：已实现（待发版标交付）　·　关联 PRD：FR-09　·　决策：[ADR-0014](../adr/0014-shared-harness-bot.md)（取代 ADR-0002 的「暂不发布」条款）

## 1. 背景与目标

E2E 桩插件（harness）与机器人（bot）的**协议胶水**在每个消费方项目里被反复实现：读 `MC_TESTKIT_E2E_*` 契约环境变量、serve 空闲判断、结果文件原子写出、`E2E_READY` 控制消息、mineflayer 连接 / 端口探测 / 重连内核。实测（AllinCore acceptance-driver、MCE e2e-harness）显示消费者**并未照抄 `template/`**，而是各自对着同一份契约手写胶水——差异集中在业务场景，胶水高度一致。

FR-09 把胶水抽成**可发布构件**：桩侧 `harness-core`（Maven）、机器人侧 `@wcpe/mc-testkit-bot`（npm），让消费者依赖构件而非重复实现 / 拷贝分叉。

## 2. 需求（要什么）

- **`harness-core`（`top.wcpe.mc:harness-core`，Maven / maven.wcpe.top）**：
  - `McTestkitEnv`：契约环境变量常量（`SCENARIO` / `RESULT_FILE` / `BACKEND_NAME` / `STRESS_DURATION_SECONDS`）+ 读取器 + serve 空闲判断（`__mc_testkit_serve__`）。
  - `McTestkitProtocol`：冻结控制协议常量（`E2E_READY` 等）。
  - `McTestkitResultWriter`：结果文件**原子**写出（status/message/明细，对齐契约 §3.5）。
  - `McTestkitHarnessPlugin`：Bukkit 抽象基类——env 场景 / 结果文件解析（回退 config.yml）、serve 空闲短路、判定收尾（原子写 + 关服、幂等门）、`E2E_READY` 发送、Paper/Folia 兼容调度；消费方继承它只写业务场景。
  - **刻意纯 Java、零 Kotlin 依赖**：MCE 等 Java 桩依赖它不会引入 kotlin-stdlib（既有「规避 Paper 插件类加载器找不到 kotlin/jvm/internal/Intrinsics」的约束）。paper-api 仅 compileOnly。
- **`@wcpe/mc-testkit-bot`（npm）**：mineflayer 公共内核——端口探测 / 重试到总超时 / spawn 后按 action 分发 / 断线重连（崩溃接管 fallback）/ 优雅收尾；场景驱动函数由消费方注入（`runBot({ scenarios })`），业务场景留在消费方。
- **`template/harness`、`template/bot` 改为依赖构件**：删除自带的协议胶水（结果写出器、env 读取、Folia 调度、机器人内核），保留示例场景；template 仍是照抄物，但消费者复制后不再需要自带胶水。
- 不做：把消费方业务场景 / 判定逻辑收进库（那是 ADR-0002 担心的过早抽象，见 ADR-0014 备选）。

## 3. 设计（怎么做）

- `harness-core/`：仓库内独立 Gradle 工程（`java-library` + `maven-publish`，groupId `top.wcpe.mc`，版本 `0.1.0`），paper-api compileOnly，`--release 17`；发布到 maven.wcpe.top（凭据走 WCPE_MAVEN_USERNAME / PASSWORD，与根工程同款约定）。
- `bot-core/`：仓库内 npm 包（`@wcpe/mc-testkit-bot`，版本 `0.1.0`），`exports` 映射 `./lib/*` → `src/lib/*.js`；内核 `src/kernel.js` 导出 `runBot({ scenarios })`。
- 消费方接线：桩插件 `implementation("top.wcpe.mc:harness-core:0.1.0")`（打进插件 jar）；机器人 `npm i @wcpe/mc-testkit-bot`，入口只登记 action → 场景驱动表。
- `template/bot` 本地开发期经 `file:../../bot-core` 引用；发布后切 npm 版本号。

## 4. 任务拆分

- [x] 实现 `harness-core`：McTestkitEnv / McTestkitProtocol / McTestkitResultWriter / McTestkitHarnessPlugin（纯 Java）+ JUnit 单测（结果文件原子写、键序、serve 判断、READY 消息）。
- [x] 实现 `bot-core`：kernel.js（runBot）+ lib 工具（env / messages / normalize / random）+ node:test 单测。
- [x] 迁移 `template/harness`：继承 McTestkitHarnessPlugin，删除重复胶水（ScenarioResultWriter 等），保留示例场景。
- [x] 迁移 `template/bot`：入口改 `runBot({ scenarios })`，场景依赖改 `@wcpe/mc-testkit-bot/lib/*`。
- [x] 自举实机验证：e2eSmoke / e2eExampleBotWithBot 经迁移后模板跑通 PASS（Paper 1.20.1）。
- [x] 真实消费者迁移验证：MCE `e2e-harness` 改为继承 McTestkitHarnessPlugin，`e2eMceOfflineEvent` 实机 PASS（离线存款 → relay → PlayerEconomyChangeEvent 投递，type=DEPOSIT seq=1 after=100）。
- [x] 文档同步：ADR-0014 取代 ADR-0002、PRD、ARCHITECTURE、API.md、CHANGELOG。
- [x] 发布：harness-core → maven.wcpe.top（`top.wcpe.mc:harness-core:0.1.0`）；bot-core → wcpe npm 源（`@wcpe/mc-testkit-bot@0.1.0`，maven.wcpe.top/npm/npm-release/）；template 依赖切正式版本号并 registry 安装验证。

## 5. 验收标准

- 库单测全绿；模板与 MCE 编译全绿。
- 自举 E2E（smoke + bot 直连）与 MCE E2E 实机 PASS——证明「消费者依赖构件而非手写胶水」的真实链路通。
- 库 API 稳定后发版，PRD FR-09 状态由发版流程标 `已交付@vX.Y.Z`。

## 6. 风险 / 待定

- `harness-core` API 一经发布即契约：破坏性变更须升 major（ADR-0014 后果）。
- 未来不得给 harness-core 引入 Kotlin / Bukkit 运行期依赖（Java 桩约束）。
- `template/` 仍是照抄物，现在是构件的示例消费者；拷贝后 `npm install` 会从 wcpe npm 源拉 `@wcpe/mc-testkit-bot`（消费方需在 npmrc 配好 `@wcpe:registry`）。
