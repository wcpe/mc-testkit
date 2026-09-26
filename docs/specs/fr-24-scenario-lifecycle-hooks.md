# 功能规格：场景生命周期钩子（before / ready / after 三时序点）

> 状态：已交付@v0.14.0　·　关联 PRD：FR-24　·　关联决策：[ADR-0020](../adr/0020-scenario-lifecycle-hooks.md)
>
> 说明：本文件是**交付后回填的历史记录**——功能经 PR #5 落地时只同步了 PRD / ADR / API / ARCHITECTURE / CHANGELOG，未留下工作规格；此处按 ADR-0020 与最终实现补记，内容忠于现状，不改决策正文。

## 1. 背景与目标

mc-testkit 的编排覆盖「下载 → 起服 → 跑 bot → 判定 → 收尾」，对**自包含**被测系统足够。但有一类被测系统**依赖外部控制面**：被测插件内的 agent 要连上一个独立进程（如 Lodestone 的 Beacon，native 可执行文件，非本框架下载的 MC 平台产物），由它下发配置、审批身份、观测拓扑。

框架当时**没有接缝**容纳这类动作：

- 就绪门只有「TCP 端口可连」与「结果文件轮询」两种，应用级 HTTP 就绪无法表达；
- 没有「场景前 / 场景后」钩子——`finalizedBy` 只由框架自己的 `stop*` 任务使用，消费方无法挂载收尾；
- 唯一扩展点 `backendRunDirectory` 解决「注入配置」，解决不了「时序敏感的动作」。

**目标**：在**不改变既有编排模型**（ADR-0004：前台被测后端 + 后台代理/集群 + pid 收尾 + 环境契约固化）的前提下，让消费方在场景的三个时序点插入自己的动作，且收尾同样享有失败路径保障。

**范围内**：`scenario` 块新增三个钩子声明 + 五个内置钩子实现 + 钩子任务的注册与接线（四条场景路径统一）。
**范围外**：把外部依赖建模为 `backend`、容器编排跑外部依赖、状态机 / 插件扩展点机制（见 ADR-0020「备选方案」）。

## 2. 需求（要什么）

- **三个时序点**（而非一个）：`beforeScenario`（运行目录就绪后、服务端启动前）/ `readyScenario`（全部节点端口就绪后、bot 启动前）/ `afterScenario`（场景判定之后）。
- **钩子以任务承载**：`before<Key>Scenario` / `after<Key>Scenario` 独立注册，时序由构建图（`dependsOn` / `finalizedBy`）保证；`readyScenario` 因需要「节点已就绪」时刻而不注册任务，由集群任务体内调用。
- **失败语义刻意不对称**：前置钩子失败即抛出（判场景失败）；收尾钩子失败只记 warn 并继续（不阻断其余清理、不掩盖场景判定失败）。
- **`readyScenario` 仅集群场景**：其他形态在**配置期**抛中文异常——不静默忽略。
- **钩子须可序列化**：消费方会在任务动作 / `BuildService` 参数中捕获它们（配置缓存要求）；内置钩子用具名类而非 lambda。
- 既有 DSL / 任务名 / env / 控制协议 / 结果文件格式不变（加法变更）。

## 3. 设计（怎么做）

决策正文见 [ADR-0020](../adr/0020-scenario-lifecycle-hooks.md)，此处只记落地面。

**DSL**（`dsl/Specs.kt`）：`ScenarioSpec` 新增收集器 `beforeScenario(hook)` / `readyScenario(hook)` / `afterScenario(hook)`，底层为三个列表（`beforeHooks` / `readyHooks` / `afterHooks`）。`ScenarioHook` 接口 + `HookContext`（场景名、结果目录、后端名集合、代理名、`info` 日志出口）。

**任务命名**（`contract/McTestkitTaskNames`）：`before<Key>Scenario` / `after<Key>Scenario`（`<Key>` 沿用 PascalCase 折叠约定）。仅当声明了对应钩子时才注册；`before<Key>Scenario` **不自行接线**（早期硬接 `prepareE2e<Key>` 是错的——集群场景不生成该任务，会报 `Task with path 'prepareE2e…' not found`），由各场景路径接自己的前置任务；`after<Key>Scenario` 由各场景任务 `finalizedBy`（正常 / 失败 / 中断三路径都执行），场景任务体内另有 `try/finally` 双保险。

**配置期校验**（`task/McTestkitTasks.validateScenarioHooks`）：`beforeScenario` / `afterScenario` 在**四条路径**（直连 / 经代理 / 集群 / 压测）都支持；`readyScenario` 仅集群（同时声明 `backends(...)` 且非压测），其他形态抛中文 `GradleException` 并给出替代建议。

**内置钩子**（`dsl/ScenarioHooks.kt`，均 `ScenarioHook, Serializable`；运行期能力在 `dsl/ScenarioHookRuntime.kt`）：

| 实现 | 关键参数 | 作用 |
|---|---|---|
| `ExecHook` | `command` / `env` / `workingDirectory` / `logFileName` / `pidFileName` / `readyPort` / `readyLogPattern` / `readyTimeoutMs` | 起后台进程；就绪门支持「TCP 端口可连」与「日志行匹配」双门；pid 默认落结果目录供 `StopPidHook` 收尾 |
| `StopPidHook` | `pidFileName` | 按 pid 收尾（进程已退出静默跳过），复用框架既有收尾语义 |
| `HttpHook` | `method` / `url` / `headers` / `body` / `expectStatus` / `timeoutMs` | 调控制面 admin API（登录 / 审批 / 下发配置）；用 `HttpURLConnection`（Java 8 起可用，不抬高最低运行 JDK） |
| `SleepHook` | `millis` / `reason` | 沉降等待（等异步模块就绪） |
| `HookChain` | `hooks` | 按序串联（起进程 → 等就绪 → 登录 → 审批这类多步），任一步失败即中断 |

**钩子上下文**：`HookContext` 携带场景名、结果目录（pid 文件等落这里）、本次场景的后端名集合（集群用声明的全部后端，其余用单后端）与代理名，使钩子不必自己拼运行目录、也不写死路径。

**不变量遵守**：不反依赖消费项目 / `template/`；不绕过结果文件自判 PASS/FAIL；不写死本机绝对路径（结果目录由 `RunLayout` 推导）；中文分级日志。

## 4. 任务拆分

- [x] ADR-0020 落地（三时序点 + 任务承载 + 失败路径差异化）
- [x] DSL：`ScenarioHook` / `HookContext` / 三个收集器 + `ScenarioSpec` 列表
- [x] 契约：`McTestkitTaskNames.beforeScenario` / `afterScenario`（+ 单测）
- [x] 内置钩子五个（`ScenarioHooks.kt`）+ 运行期（`ScenarioHookRuntime.kt`）
- [x] 注册与接线：四条场景路径统一注册；`after<Key>Scenario` 挂 `finalizedBy`；`before<Key>Scenario` 由各路径前置任务接；集群任务体内在端口就绪门后、bot 之前调 `readyScenario`
- [x] 配置期校验：`readyScenario` 非集群形态中文报错（不静默忽略）
- [x] 测试：`ScenarioHooksTest`（单元：DSL / 校验 / 序列化 / 运行时行为）、`ScenarioHookFunctionalTest`（Gradle TestKit：任务注册、任务图、失败路径仍收尾）
- [x] 文档同步：PRD FR-24 行、API §3.5、ARCHITECTURE（机制）、CHANGELOG、ADR 索引
- [x] 交付状态：随 PR #5 合入、随 v0.14.0 发布（该 PR 未附实机验收记录；本仓库无实机维度证据）

## 5. 验收标准

- **[自动]** 声明三个钩子后：`before<Key>Scenario` / `after<Key>Scenario` 被注册、任务图无环；未声明时不注册空任务。
- **[自动]** 非集群形态声明 `readyScenario` → 配置期中文 `GradleException`（TestKit 断言报错文案）。
- **[自动]** 前置钩子抛出 → 场景判失败，且 `after<Key>Scenario` **仍执行**（失败路径不被跳过）。
- **[自动]** 收尾钩子抛出 → 只记 warn，不阻断其余清理、不掩盖场景判定失败。
- **[自动]** 内置钩子可序列化往返（配置缓存可存储）；`ExecHook` 就绪门（端口 / 日志行）与 `StopPidHook` 的「已退出静默跳过」有单元覆盖。
- **[需消费方实机]** 依赖外部控制面的真实场景端到端：外部进程在服务端启动前起来、就绪门等到应用级就绪、审批在节点就绪后完成、场景结束（含失败路径）后进程被收尾。**该维度不在本仓库自动化范围内**（需真实的控制面进程与消费者项目）。

## 6. 风险 / 待定

- **钩子内的副作用由消费方负责**：起进程、写文件、发 HTTP 的幂等性与清理归消费方；框架只保证「按声明执行」与「失败路径也收尾」。
- **可序列化是硬约束**：自定义钩子若捕获不可序列化对象（如 lambda），会在消费方启用配置缓存时**存储期**失败而非立即报错——内置实现因此一律用具名类。
- **`readyScenario` 的可用形态窄**：直连 / 经代理场景不存在「全部节点已就绪」的时刻（后端前台自停），故仅集群场景提供；需要该时序的场景得改造为集群形态。
- **`afterScenario` 与 `stop*` 同层**：两者都挂 `finalizedBy`，执行顺序不由框架保证（互不依赖时应视为无序）。
