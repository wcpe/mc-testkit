# 功能规格：FR-12 每后端身份注入

> 状态：已交付@v0.2.0　·　关联 PRD：FR-12（另含同批小改 FR-13/FR-14）、ADR-0006（对外契约·env 前缀）、ADR-0008（集群/压测编排）　·　分支：master

## 1. 背景与目标

集群（FR-10）/ 压测（FR-11）把同一个桩 / 被测插件注入到 N 个后端，N 个后端共用同一份模板配置——**身份完全相同**。下游跨服一致性插件（按服划分归属 / 转服交接）需要「同组各服各有不同 `server-id`」才能正确运作；身份相同会让其归属判定串台（实测出现转服丢数据）。

mc-testkit 此前不告诉后端「它在这组里叫什么」，消费方无从在运行期区分自己是 s1 还是 s2。本 FR 让编排起每个后端时下发本后端的**声明名**，消费方据此 per-backend 派生身份。属 PRD §7 第三期「更多拓扑形态」配套能力，随集群一并打磨。

## 2. 需求（要什么）

- 范围内：
  - 新增冻结契约 env `MC_TESTKIT_E2E_BACKEND_NAME`，值 = 该后端在 DSL 里的声明名（如 `s1` / `s2`），与下发给 bot 的 `CLUSTER_BACKENDS`（有序后端名）**同源、有序对应**。
  - 编排起**每个**后端时注入该 env：集群（N 后端后台）、压测（N 后端后台）、单后端（前台）三条启动路径都注入；单后端时即该后端自己的声明名。
  - `template/harness` 演示消费方用法：读 `MC_TESTKIT_E2E_BACKEND_NAME` 并把它写进结果文件明细（smoke / cross-server 场景），既示范又便于实机复验。
- 不做（范围外）：
  - **不替消费方决定怎么用**这个名字（拼 `server-id` 后缀、做分片键……都是消费方的事）；编排只负责「告诉每个后端它是谁」。
  - 不引入 DSL 新字段——后端声明名复用既有 `backend("s1")` / `backends("s1","s2")` 的名字，不另立配置。
  - 不改 `CLUSTER_BACKENDS` 语义（那是给 bot 的切换目标清单；`BACKEND_NAME` 是给后端自己的身份）。

## 3. 设计（怎么做）

- **契约**（`contract/McTestkitEnv.kt`）：新增 `BACKEND_NAME = PREFIX + "BACKEND_NAME"`。属 ADR-0006 已预期的 env 全集补全（前缀 / 风格不变、纯增量），非破坏性变更，**不需新 ADR**。
- **编排**（`task/McTestkitTasks.kt`）：在后端启动 env 装配处加 `BACKEND_NAME to backend.name`——
  - `startBackendBackground`（集群 / 压测的后台后端）；
  - `runBackendForeground`（单后端前台）。
  二者均在已有的 `SCENARIO` / `RESULT_FILE` 同一处下发，取 `backend.name`（拓扑模型里的声明名），保证与 `CLUSTER_BACKENDS` 同源。
- **template**（`harness/HarnessConfig.kt` + `McTestkitE2eHarnessPlugin.kt`）：`HarnessConfig` 加 `backendName`（读同名 env，缺失为空串，单后端可不依赖）；smoke / cross-server 结果明细加 `backendName` 键，演示「取本后端声明名」并使实机结果可断言切到了哪台。

## 4. 任务拆分

- [x] 契约：`McTestkitEnv.BACKEND_NAME` + 契约冻结测试。
- [x] 编排：两条启动路径注入 `BACKEND_NAME`。
- [x] template：`HarnessConfig.backendName` + 结果明细演示。
- [x] 单元/TestKit + 根 `build` 绿（含 ktlint）。
- [x] 实机自举：smoke 结果含 `backendName=s1`（R2 端到端）+ doc-sync（PRD/API/CHANGELOG）+ 中文提交。

## 5. 验收标准

- **契约**：`McTestkitEnv.BACKEND_NAME == "MC_TESTKIT_E2E_BACKEND_NAME"` 且以冻结前缀打头（单测）。
- **编排**：集群 / 压测 / 单后端三条启动路径的后端 env 均含 `BACKEND_NAME`，值 = 各自后端声明名（代码 + 实机日志）。
- **实机**（PRD §6 实机维度，需用户在备齐环境确认）：跑通 smoke → 结果文件 `smoke.properties` 含 `backendName=s1`；跑通集群跨服 → 到达服结果含 `backendName=<到达服名>`。下游据此给同组各服派生不同 `server-id`，转服交接不丢数据（由消费方桩查共享 DB 自断言）。
- **回归**：既有单后端 / 集群 / 压测行为不变（新增 env 为纯增量，旧消费方不读即无感）。

## 6. 风险 / 待定

- `BACKEND_NAME` 与 `CLUSTER_BACKENDS` 必须同源：二者都取拓扑模型的后端声明名，避免「bot 切换目标」与「后端自报身份」对不上。已在同一拓扑数据上取值规避。
- 消费方若把 `backendName` 直接当 `server-id` 用，需保证 DSL 后端名在该组内唯一——本就由配置期重名校验保证（FR-03）。

## 7. 同批小改（FR-13 / FR-14，均为小改、免独立规格）

- **FR-13 默认 peaceful 难度**：`prepareRunDirectory` 写最小 `server.properties` 时，仅在消费方模板**未设** `difficulty` 时补 `difficulty=peaceful`（用 `ServerProperties.load(runDir).containsKey(DIFFICULTY)` 判定），保护测试玩家不被怪物 / 环境杀；模板已设则保留。验收：单测覆盖「缺省判定」预测语，实机 `run/server.properties` 含 `difficulty=peaceful`。
- **FR-14 跳过 Kotlin 元数据版本校验**：`template/harness/build.gradle.kts` 的 `kotlin { compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") } }`，使桩能 `compileOnly` 引用「元数据版本高于本工程编译器可读上限」的被测插件类（仅编译期跳过，运行期字节码兼容）。验收：`template/harness` 加该参数后仍正常编译。
