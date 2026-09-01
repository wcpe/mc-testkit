# ADR-0014：发布共享桩协议胶水库（harness-core）与机器人内核包（bot-core）（取代 ADR-0002）

## 状态

已接受（取代 [ADR-0002](0002-plugin-and-template-only.md) 的「暂不发布共享库」条款）

## 背景

ADR-0002 决策「本期只交付编排插件 + 模板，暂不发布共享 Kotlin 桩基类库与共享 npm 机器人包」，理由是 N=1 抽象风险：从单一用例抽象「通用桩/机器人库」易把特例固化进框架，触发条件是**第 2 个真实消费者**出现、暴露出真实差异后再抽。

触发条件已满足：仓库外已有多个真实消费者——AllinCore 的 `acceptance-driver`（自研整套 harness）、MultiCurrencyEconomy 的 `e2e-harness`、ServerProbe、AllinInventorySync 等。实测比对发现：**消费者并未照抄 `template/`，而是各自对着同一套 mc-testkit 协议契约（docs/API.md §3.3 / §3.4 / §3.5）重新实现了同一段胶水**——环境变量读取（`MC_TESTKIT_E2E_SCENARIO` / `RESULT_FILE` 等）、serve 空闲判断、结果文件原子写出、`E2E_READY` 控制消息、mineflayer 连接 / 端口探测 / 重连内核。这就是「第 2、3 次真实复用暴露出的真实差异」——差异集中在**业务场景**，胶水部分高度一致，正是该抽的部分。

## 决策

发布两个共享制品（FR-09）：

1. **`harness-core`**（`top.wcpe.mc:harness-core`，Maven / maven.wcpe.top）——桩插件协议胶水库，**刻意纯 Java、零 Kotlin 依赖**（消费方如 MCE 刻意用 Java 写桩以避免打包 kotlin-stdlib，库必须可被 Java / Kotlin 两类桩直接依赖）：
   - `McTestkitEnv`（纯 JDK）：契约环境变量常量 + 读取器 + serve 空闲判断；
   - `McTestkitResultWriter`（纯 JDK）：结果文件**原子**写出（status/message/明细，对齐契约 §3.5）；
   - `McTestkitHarnessPlugin`（Bukkit 抽象基类，paper-api compileOnly）：env 场景/结果文件解析、serve 空闲短路、判定收尾（原子写 + 关服、幂等门）、E2E_READY 控制消息、Paper/Folia 兼容调度；消费方继承它只写业务场景。
2. **`@wcpe/mc-testkit-bot`**（npm）——mineflayer 机器人公共内核：端口探测 / 重试 / action 分发 / 断线重连（崩溃接管）/ 优雅收尾，场景驱动函数由消费方注入。

`template/harness` 与 `template/bot` 由「纯拷贝」改为**依赖上述构件**的薄骨架（示例场景保留）；`template/` 仍是照抄物，但消费者复制后不再需要自带协议胶水。

## 理由

- 触发条件已满足，且**真实差异证明了抽取边界**：多个消费者重复实现的是协议胶水（库），各自差异的是业务场景（留在消费方）。这正好回答 ADR-0002 的「过早抽象」担忧——抽象对象是已出现 2+ 次重复的胶水，不是想象中的通用场景框架。
- 纯 Java 形态同时满足「Java 桩免 kotlin-stdlib」的既有约束（MCE 已验证）与 Kotlin 桩（template）的兼容。
- 修复协议胶水 bug 从「每个消费者重拷重改」变为「升级一个构件版本」，收敛拷贝漂移（ADR-0002 后果里接受的代价）。

## 后果

- **正面**：桩 / 机器人的协议层单一真源；新消费者接入成本下降（依赖构件而非手写胶水）。
- **约束**：
  - `harness-core` 的 API 一经发布即契约，改动须遵守兼容性（破坏性变更升 major）。
  - `harness-core` 保持纯 Java、paper-api compileOnly——未来不得引入 Kotlin / Bukkit 运行期依赖（否则 Java 桩约束被破坏）。
  - `template/` 仍不入插件构建（架构不变量不变）；它现在是构件的**示例消费者**。
  - 既有消费者（AllinCore 等）迁移是渐进式，不强制；ADR 只管制品形态，迁移节奏由各项目自定。
- **ADR-0002 剩余条款**：模板照抄模式仍保留（示例 + 快速起步）；共享库发布后，模板更新由「手动同步」升级为「构件升级 + 模板同步」。

## 备选方案

- **继续只做模板（维持 ADR-0002）**：拷贝漂移继续累积——消费者要么分叉维护、要么手写胶水，本次 MCE 迁移已证明后者普遍存在，落选。
- **抽「全功能 harness 框架」而非协议胶水**（把示例场景/通用判定也入库）：会把消费方差异（业务断言）固化进库，正是 ADR-0002 担心的过早抽象，落选——库只收协议胶水，场景留在消费方。
