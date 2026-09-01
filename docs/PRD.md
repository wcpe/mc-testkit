# 产品需求文档（PRD）：mc-testkit

> 需求的单一真源（WHAT / WHY），也是产品的**需求登记册 + 路线图**。每个需求在 §4 加一行 FR（带优先级/期 + 状态），交付即标版本。单功能详细规格放 `docs/specs/`，PRD 只保留"一行 FR + 期 + 状态"的索引级。

## 1. 背景与目标

一个团队常同时维护多个 Bukkit 系插件，其中需要端到端验证的，各自手搓了一套 E2E：起真实服务端、拉机器人入服、驱动场景、判定结果——做法五花八门、大量重复、难统一维护，踩过的坑（代理下机器人协议版本、paper-global 代理在线模式、BungeeCord 后端配置、数据源/Redis 注入）在每个项目里重复踩。

mc-testkit 提供**统一的「全平台 E2E 编排」Gradle 插件 + 配套脚手架模板**：让任意插件用声明式 DSL 拉起真实「代理 + 后端」拓扑、用机器人驱动端到端场景、判定结果并干净收尾。**一句话价值**：把各插件重复的 E2E 编排沉淀成一处可复用、可维护、契约固化的工具。

### 非目标

- 不替消费项目编写具体业务场景与断言（场景逻辑天然项目特定，框架只提供骨架与编排）。
- 已发布共享协议胶水构件：`harness-core`（Maven，桩侧）+ `@wcpe/mc-testkit-bot`（npm，机器人侧，见 ADR-0014 取代 ADR-0002）。共享的是**协议胶水**；消费方业务场景 / 断言仍留在各项目，不入库。
- 不取代单元测试框架（JUnit / MockBukkit 等仍是单元/集成测试的职责）。
- 不驱动真实游戏客户端（Fabric/Forge mod 客户端）；客户端行为由 mineflayer 机器人模拟。
- 不支持 Bukkit/Sponge 后端（不列入计划，见 ADR-0003；Spigot 已纳入，见 ADR-0013）。

## 2. 角色

- **插件开发者（消费方）**：在自己插件仓库应用本插件，声明拓扑与场景，跑 E2E。
- **CI**：以一条命令跑 E2E，用退出码反映 PASS/FAIL。
- **框架维护者**：维护编排插件与模板，修一处环境契约让所有消费项目受益。

## 3. 用户故事

- 作为插件开发者，我希望用一段 DSL 声明「代理 + 后端」拓扑并 `./gradlew` 跑通一个购买 E2E，以便不必每个项目重搓编排。
- 作为插件开发者，我希望照抄 `template/` 就有桩插件 + 机器人骨架，以便快速加自己的场景。
- 作为 CI，我希望一条 Gradle 命令跑完 E2E 并以退出码反映 PASS/FAIL，失败有可定位的日志与结果文件。
- 作为框架维护者，我希望环境契约（机器人协议版本、代理在线模式、BungeeCord 后端配置、数据源/Redis 注入）固化在一处，改一次全项目生效。

## 4. 功能需求（FR）

| 编号 | 需求 | 优先级 | 状态 |
|---|---|---|---|
| FR-01 | Gradle 插件骨架：`top.wcpe.mc-testkit`（java-gradle-plugin + kotlin-dsl）、`mcTestkit { }` DSL 扩展、发布到 maven.wcpe.top | P1 | 已交付@v0.1.0 |
| FR-02 | 内置下载与运行：插件自实现下载并运行 Paper/Folia/Spigot 后端与 Velocity/Waterfall/BungeeCord 代理（Spigot 走受控公共构件源 + 多源回退 + 溯源，见 ADR-0013）；启动按构件形态选路（自包含 jar / paperclip 引导件走 `java -jar`，运行目录带 `libraries/` 的 thin jar 经启动器 jar 传 classpath）（内置自实现，不外挂第三方下载库，见 ADR-0001）| P1 | 已交付@v0.1.0 |
| FR-03 | 声明式拓扑 DSL：声明「单后端」或「代理 + N 后端」节点、端口与路由 | P1 | 已交付@v0.1.0 |
| FR-04 | 任务自动编排：prepare / 启动机器人 / runServer / proxy / cluster / verify / 缓存回写 | P1 | 已交付@v0.1.0 |
| FR-05 | 固化环境契约：经代理固定机器人协议版本、paper-global 代理在线模式、BungeeCord 后端配置、依赖数据源/Redis 注入校验 | P1 | 已交付@v0.1.0 |
| FR-06 | 机器人驱动 + 结果判定：启动机器人进程、机器人↔桩控制协议、读结果文件判 PASS/FAIL | P1 | 已交付@v0.1.0 |
| FR-07 | `template/` 脚手架：桩插件骨架 + mineflayer 机器人内核 + 一个示例场景 + 复制说明 | P1 | 已交付@v0.1.0 |
| FR-08 | 以首个接入的真实插件项目作消费者验证：迁移其编排到本插件，跑通 smoke 与「经 Waterfall 代理购买」 | P1 | 已交付@v0.1.0 |
| FR-09 | 抽出可发布的共享桩基类库 / 机器人包：`harness-core`（Maven，桩协议胶水：契约 env / 结果原子写出 / serve 空闲 / 桩基类）+ `@wcpe/mc-testkit-bot`（npm，机器人内核：端口探测 / 重连 / action 分发 / 收尾），`template/` 改为依赖构件（第 2 个消费者验证后，见 ADR-0014 取代 ADR-0002）| P2 | 开发中 |
| FR-10 | 多后端集群编排：声明「N 后端（同 data-group）+ 代理」拓扑，机器人经代理在后端间 `/server` 切换，桩跨服判定一致性（扩展 scenario 块 `backends(...)`，见 ADR-0008）| P3 | 已交付@v0.1.0 |
| FR-11 | 压测编排：N 服 × M bot 钉服持续随机动作（N-listener 代理或直连），每服桩收集各 bot `E2E_STRESS_RESULT` 聚合判定；业务不变量（不超卖）由消费方桩查共享 DB 断言（扩展 scenario 块 `stress{}` + 规模 env，见 ADR-0008）| P3 | 已交付@v0.1.0 |
| FR-12 | 每后端身份注入：编排起每个后端时下发本后端声明名 env `MC_TESTKIT_E2E_BACKEND_NAME`（与下发给 bot 的 `CLUSTER_BACKENDS` 同源、有序对应），消费方据此 per-backend 派生身份（如各服不同 `server-id`）；编排只负责告诉每个后端「它是谁」，不规定怎么用（见 docs/specs/fr-12-per-backend-identity.md）| P1 | 已交付@v0.2.0 |
| FR-13 | 测试环境默认 peaceful 难度：最小 `server.properties` 默认写 `difficulty=peaceful` 保护测试玩家不被怪物/环境杀；消费方服务端模板已设 `difficulty` 则保留其值（不覆盖）| P2 | 已交付@v0.2.0 |
| FR-14 | 桩兼容更新版 Kotlin 编译的被测插件 API：`template/harness` 加 `-Xskip-metadata-version-check`，使桩能 compileOnly 引用「元数据版本高于本工程编译器可读上限」的被测插件类（仅编译期跳过，运行期字节码仍兼容）| P2 | 已交付@v0.2.0 |
| FR-15 | 集群代理崩溃接管 fallback：集群代理 listener `priorities` 改为**全部后端**有序列表（首个仍为默认服 + `force_default_server`，其余作 fallback），默认后端宕机时 bot 重连经代理回退到下一个存活后端，支撑「崩溃接管」类 E2E（某后端崩溃 → bot 落存活后端、由其在归属租约 TTL 过期后接管上线）；正常 `/server` 切换与 `force_default_server` 落默认服不受影响（集群编排加法增强，见 ADR-0008）| P3 | 已交付@v0.2.1 |
| FR-16 | 单场景多 bot：一个 `scenario { }` 可驱动多个 bot——**异质**（多个具名 `bot("角色") { }` 各有 `username`/`action`/`env`）与**同质批量**（`bot { count = N }` 复制 N 份、各唯一 username、经 `BOT_INDEX`（1..N）区分）；用于集群（多 bot 各自经代理 `/server` 切）与单后端（多 bot 直连，可分角色）。复用既有 env（`BOT_USERNAME`/`BOT_ACTION`/`BOT_INDEX`/`CLUSTER_BACKENDS`）与任务名（声明多 bot 时 `e2e<Key>`/`Cluster`/`launch<Key>Bot` 起多个进程），与压测 FR-11「同质钉服」划清边界、随场景结束全部回收；结果仍由桩按 username/index 聚合（扩展 scenario 的 bot 声明，见 ADR-0009）| P3 | 已交付@v0.2.2 |
| FR-17 | 持久开启·单后端手测：新增 `serve { }` DSL 块声明持久目标，复用拓扑起后端（+可选经代理）、注入被测/依赖插件并挂住等真人客户端连入手测；不下发场景、不判 PASS/FAIL，手动停（Ctrl+C / `stop` 任务）按 pid 干净收尾（见 docs/specs/fr-17-persistent-serve.md，ADR-0011）| P3 | 已交付@v0.4.0 |
| FR-18 | 持久开启·集群拓扑手测：`serve { }` 支持多后端 + 代理整套挂起，真人经代理 `/server` 跨服手测、人眼复现跨服 bug；停后全部后端 + 代理收尾干净、端口不漏（见 docs/specs/fr-18-cluster-serve.md，ADR-0011）| P3 | 已交付@v0.4.0 |
| FR-19 | 持久模式可选并起 bot（人机混场）：`serve { }` 可选起场景 bot 把环境驱到某状态但**不**按结果文件收尾，挂住让真人同时连入同测；停时 bot + 后端 + 代理全收尾（见 docs/specs/fr-19-bot-mixed-serve.md，ADR-0011）| P3 | 已交付@v0.4.0 |
| FR-20 | 节点运行时注入：保持 `dependencies { }` 仅注入后端，为 backend / proxy 增加每节点 env 与模板目录、为 proxy 增加专属插件注入，并覆盖 v0.4.2 全部 E2E / serve 启动路径；以真实 BungeeCord 下游消费、旧 DSL 兼容且不引入 `provide` 为交付门禁（见 docs/specs/fr-20-node-runtime-injection.md）| P1 | 已交付@v0.5.0 |
| FR-21 | 多版本服务端拉起适配：增强 mc-testkit 支持 8 个代表版本（1.7.10 / 1.8.8 / 1.12.2 / 1.16.5 / 1.17.1 / 1.19.4 / 1.20.1 / 1.21.1）的 Paper 服务端拉起 + bot 协议支持——`ServerProperties.versionAwareOverrides`（版本感知键过滤 + level-type 转换）、`PaperConfigAdapter`（按版本生成 paper.yml / paper-global.yml / 跳过）、`JavaRuntimeSelector`（`MC_TESTKIT_JAVA_HOME_<版本段>` 覆盖 + `JAVA_HOME` 回退）、`MinecraftVersionGroup`（版本段分组查询）、bot 版本范围校验（1.7.10 跳过 bot + 告警，1.8.8+ 正常） | P2 | 已交付@v0.6.0 |
| FR-22 | 多版本代理与诊断型 JVM 编排：代理 DSL 增加独立 `version` 与 Java 运行时/JVM 参数声明，Velocity 支持 3.1.1、最新 3.x、4.1.0（含 Java 25）矩阵；后端/代理均可注入 `-javaagent`；提供消费方协议流量客户端与诊断 fixture 接线能力，支撑 ServerProbe 全平台网络取证和 Arthas MCP 真机验收，同时保持旧 DSL/env/任务向后兼容 | P1 | 已交付@v0.7.0 |

> 状态取值：计划 / 开发中 / 已交付@vX.Y.Z。优先级：P1(MVP 或阻断真实消费者接入的关键项) / P2 / P3。
> 标 `已交付` 有门：该 FR 的 §6 / spec 验收标准全部满足、对应测试 / 实机验收通过后，才由 `sdd-release-version` 在发版时统一标 `已交付@vX.Y.Z`，过程中不得自行预标。

## 5. 非功能需求（NFR）

- **可移植**：不写死本机绝对路径；服务端/代理 jar 经内置下载模块下载或环境变量提供；他人/CI 拉下即可用。
- **幂等可重跑**：运行目录可清理重建；下载产物缓存复用，避免反复下载。
- **收尾干净**：后台进程（代理、集群后端、机器人）必被收尾杀掉，不残留占端口。
- **可观测**：中文分级日志；结果文件 + 各服/各机器人日志可定位失败原因。
- **离线/CI 友好**：关键 jar 可缓存或经环境变量覆盖，支持无网/弱网环境。
- **正确性优先**：编排不得让"未真正完成"的测试报成功（结果以桩写出的结果文件为权威）。
- **消费端兼容性**：插件构件可被 K1（Gradle 8.x / Kotlin 1.9）与 K2（Gradle 9.x / Kotlin 2.x）的项目消费——Kotlin 语言/API 版本锁 1.9，构建脚本 KTS、实现纯 Kotlin（见 ADR-0005）。

## 6. 验收标准

- [x] 首个接入项目接入本插件后，`e2eSmoke` 与「经 Waterfall 代理购买」场景均 PASS（2026-08-31 自举实机验收：`e2eSmoke` 与 `e2eExampleBotViaPx`（经 Waterfall 代理 + bot 驱动场景）实测 PASS，Paper 1.20.1 + Waterfall 1.20；「购买」为消费方业务断言，其经代理链路已由自举等价场景验证，业务层断言待真实下游确认）。
- [x] 插件应用后配置期任务图无环、任务名稳定、缺依赖时报中文明确错误（由 TestKit 集成测试全绿覆盖：`NodeRuntimeInjectionFunctionalTest` / `McTestkitContractTest` 等，完整构建 312 测试通过；本次消费者工程应用期任务注册与运行正常）。
- [x] 一个全新项目按 `template/` 照抄，能在较短时间内（手动验收）跑通一个最小场景（2026-08-31 自举实机：消费者工程即由 `template/harness` 桩 + `template/bot` 机器人照抄组装，`e2eSmoke` / `e2eExampleBotWithBot` 实测 PASS）。
- [x] 后台进程在任务结束/失败后均被收尾，端口释放、无残留（2026-08-31 自举实机：5 个场景（smoke / example-bot 直连 / 经代理 / 集群跨服 / 多 bot）跑完后，运行目录全部 pid 文件对应进程已灭、5 个 mineflayer bot 全灭、端口 25665/25666/25677 全部释放）。
- [x] 集群/压测下各后端经 `MC_TESTKIT_E2E_BACKEND_NAME` 收到各自声明名，消费方据此派生**不同** `server-id`（FR-12）；smoke 结果含 `backendName=s1`、集群到达服结果含其服名（2026-08-31 自举实机：`smoke.properties` 含 `backendName=s1`，`cross-server.properties` 含 `backendName=s2` + `arrivedServer=Paper`，桩确认 bot 经代理到达本服）；下游跨服一致性 / 转服不丢数据断言由消费方桩查共享 DB 自证——自举桩用自带判定（非共享 DB），该部分待真实下游闭环（与 fr-16 实机项同挂）。
- [x] FR-20 以真实 BungeeCord 消费验证为交付门禁：下游代理插件经代理节点专属声明成功加载，backend / proxy 每节点 env 与模板分别生效；旧 DSL 与 `dependencies { }` 仅后端注入语义不回归；公共 DSL / 任务不引入 `provide`（**Beacon 真实消费与完整构建已确认**）。
- [x] FR-22：同一消费项目可分别拉起 Velocity 3.1.1、最新 3.x（固定为 3.5.1）、4.1.0 并选择匹配 Java（4.1.0 使用 Java 25）；backend/proxy 节点 JVM 参数能传入 `-javaagent` 且不泄露本机路径到公共契约；真实协议流量与诊断 fixture 结果仍由 harness 结果文件判定；旧 DSL、任务名及 `MC_TESTKIT_E2E_` 冻结契约全部回归通过。
- [x] FR-02 thin jar 启动：运行目录带 `libraries/` 的构件按 `-cp <启动器 jar> <Main-Class>` 拉起，`Class-Path` 条目按 UTF-8 百分号编码（含空格 / 中文路径不被截断）；自包含 jar、paperclip 主入口、读不出 `Main-Class` 三种情况退回 `-jar`；既有启动路径与全部单测回归通过（**单测维度**；thin jar 平台的真实拉起待下次实机维度一并确认）。

## 7. 分期（路线）

各期只描述**主题 / 目标**；具体哪个 FR 属于哪期，以 §4 FR 表的优先级列为唯一来源。

- **第一期（MVP）**：把核心立起来——Gradle 编排插件（Paper/Folia/Spigot 后端 + Velocity/Waterfall/BungeeCord 代理）+ 脚手架模板，以首个接入项目跑通。
- **第二期**：在第 2 个真实消费者验证后抽出可发布的共享桩 / 机器人库。
- **第三期**：完善——更多拓扑形态、并发压测沉淀、持久手测/沙盒模式（serve）、文档与示例丰富。

> 期是粗粒度路线图横轴，数量很少；一期含很多 FR、跨很多版本。某期是否完成看 §4 表里该期 FR 状态是否都 `已交付`。

## 8. 术语表

- **后端（backend）**：承载游戏逻辑的服务端（Paper/Folia/Spigot，见 ADR-0013）。
- **代理（proxy）**：玩家入口、转发到后端的代理（Velocity/Waterfall/BungeeCord）。
- **拓扑（topology）**：一次测试里代理与后端的组合与路由关系。
- **桩插件（harness）**：装进被测后端、装备玩家/驱动场景/判定结果的测试用服务端插件。
- **机器人（bot）**：基于 mineflayer 的 Node 程序，模拟真实玩家入服驱动场景。
- **场景（scenario）**：一次端到端用例（如「购买成功」「购买中退出」「持续压测」）。
- **控制协议**：机器人与桩之间的约定消息（如 `E2E_READY` / `E2E_STRESS_RESULT`）。
