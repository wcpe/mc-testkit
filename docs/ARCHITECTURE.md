# 架构设计：mc-testkit

> 系统当前真貌（HOW）。始终原地更新到现状；结构 / 机制变了就改它。

## 1. 定位与边界

- **是什么**：一个 Gradle 插件（`top.wcpe.mc-testkit`）+ 配套脚手架模板，用于编排 Minecraft 插件的端到端测试——下载并运行真实「代理 + 后端」拓扑、用机器人驱动场景、判定结果、收尾。
- **不是什么**：不是运行期服务端插件；不是单元测试框架；不替消费项目编写业务场景与断言；不驱动真实游戏客户端。
- **边界**：
  - 服务端/代理的下载与运行由**本插件内置模块**完成（PaperMC / SpigotMC Jenkins 下载 + 缓存 + 子进程启动；自实现，见 ADR-0001），**不外挂第三方下载库**。
  - 向上依赖**消费项目**提供：被测插件 jar、服务端桩插件、机器人场景（均由 `template/` 给骨架）。
  - 运行在开发者本机 / CI 的可信边界内，不面向生产或玩家。

## 2. 模块与依赖

- **编排插件**（`src/main/kotlin/top/wcpe/mc/testkit/`，本仓库核心；纯 Kotlin 实现、KTS 构建）
  - `McTestkitPlugin`：插件入口。创建 `mcTestkit` 扩展；内置下载/运行模块（FR-02）、按 DSL 注册任务（FR-04）在此接缝接入。
  - `McTestkitExtension`（DSL 扩展）：声明拓扑（代理/后端/路由）、场景、依赖注入项；只忠实记录声明，不含行为。
  - **内部包接缝**（让第一期各 FR 各占一包、文件隔离并行；入口与共享契约只在 FR-01 落地一次）：
    - `contract/`（FR-01 冻结，全包共享）：对外契约常量——插件 id/扩展名/默认值、环境变量名集（前缀 `MC_TESTKIT_E2E_`）、任务命名约定、机器人↔桩控制协议、结果文件约定。
    - `dsl/`（FR-01 冻结形态 → FR-03 续写）：平台枚举（仅 Paper/Folia + 三代理）+ DslMarker、Backend/Proxy/Scenario/Bot/Dependencies Spec。
    - `model/`（FR-03）：拓扑模型（`Topology`/`ResolvedBackend`/`ResolvedProxy`）与纯函数解析、端口推导（基数+序号）、配置期校验（节点名唯一且后端/代理不相撞、路由目标存在、端口不冲突、场景引用存在）。任务图无环属 `task/`（FR-04）。
    - `provision/`（FR-02）：内置服务端/代理下载与运行——Paper/Folia/Velocity/Waterfall 经 PaperMC 下载 API、BungeeCord 经 SpigotMC Jenkins，带本地缓存与校验；按平台+版本解析 jar（`MC_TESTKIT_E2E_*_JAR`/`*_VERSION` 可覆盖）并以子进程启动。**自实现、保持精简**，不搬运插件市场下载等非必需能力。
    - `serverconfig/`（FR-05）：环境契约固化（纯函数，仅依赖 `contract/` 与 JDK）——`ServerProperties`（`server.properties` 读改写回、保留未涉及键）、`BackendBungeeCordConfig`（BungeeCord 三件套：`server.properties online-mode=false` + `spigot.yml settings.bungeecord` + `config/paper-global.yml proxies.bungee-cord.online-mode`）、`ProxyProtocolVersion`（经代理时机器人协议版本=后端版本的固定规则）、`DependencyInjections`（依赖注入缺失的**通用**中文报错机制，不写死具体依赖名）。不注册任务（由 `task/` 接入）。
    - `bot/` + `verify/`（FR-06）：机器人子进程启动与 pid 收尾、结果文件读取判 PASS/FAIL。`bot/` 含 `nodeCommand()`（跨平台 node 可执行）、`BotConnection`（按 `MC_TESTKIT_E2E_` 名构建机器人环境变量的纯函数）、`BotLauncher`（后台启 mineflayer、写 pid、场景 action 经 env `BOT_ACTION` 传给机器人内核）、`stopProcessByPidFile`（ProcessHandle 温和退出→超时强杀，缺失/已退出安全 no-op）；`verify/` 含 `ResultReader`（读 `<scenario>.properties`，缺失或 status≠PASS 抛中文错误）。仅依赖 `contract/` 与 JDK，不注册任务（由 `task/` 接入）。
    - `task/`（FR-04，整合器）：在 `McTestkitPlugin.apply()` 的 `afterEvaluate` 里，经 `TopologyResolver.resolve` 复用 FR-03 配置期校验后，按 `mcTestkit { }` 声明**数据驱动**注册任务（命名严格按 `contract/McTestkitTaskNames`）：每场景生成 `prepareE2e<Key>`（清运行目录保留运行库缓存、写 eula 与最小 `server.properties`、按 `dependencies{}` 注入待测/依赖插件 jar）、`e2e<Key>`（前台起后端自停 `waitFor` → `ResultReader` 判定）；有 bot 时加 `launch<Key>Bot`（`BotLauncher` + `BotConnection`）/ `e2e<Key>WithBot`；经代理（`via`）时加 `e2e<Key>Via<Proxy>`（BungeeCord 模式后端 `BackendBungeeCordConfig.apply` + 写代理 `config.yml` + `ProvisionPlatform`/`ServerLauncher` 后台起代理 + bot 经代理端口且 `ProxyProtocolVersion.forBackend` 固定协议版本 + `finalizedBy` 停代理任务收尾）；固定名 `npmInstallE2eBot`/`syncE2eRuntimeCache`/`purgeE2eRuntimeCache`。路径推导（`RunLayout`）、注入解析（`resolveDependencyJars`）、代理配置生成（`bungeeProxyConfigYml`）下沉为纯函数；任务副作用全在 `doLast`，配置期只注册校验不下载/不起进程。后台代理/机器人按 pid（`stopProcessByPidFile`）在 `finalizedBy` + `try/finally` 双保险收尾，保证不残留占端口。只调上述各包公开 API，不反依赖消费项目 / `template/`。
- **`template/`**（脚手架，纯拷贝物，不被插件代码依赖；FR-07）
  - `harness/`：服务端桩插件骨架（Kotlin，框架无关的 Bukkit/Paper 插件，**独立 Gradle 子工程**，自带 `settings.gradle.kts` / `build.gradle.kts`、`paper-api` compileOnly，不入 root settings）：配置加载、入服派发场景、结果写出、与机器人的控制协议、内置 `smoke` + `example-bot` 两个场景。
  - `bot/`：mineflayer 机器人内核（Node ≥18）：端口探测 + 连接/重试、控制消息等待、文本归一、按 action 分发场景、`example-bot` 示例；eslint + prettier。窗口/背包辅助、压测循环刻意不预置（避免把业务玩法固化进骨架），消费方按真实场景自补。
  - 复制接线说明（`template/README.md`）。
  - 协议消息名 / 结果文件键 / env 名以**字面量**对齐 `contract/`（docs/API.md §3.3/3.4/3.5），不 import 插件包——保持 template 与插件零编译期耦合（双向都不依赖）。

**依赖方向（单向）**：编排插件（含内置下载/运行模块）← 消费项目 → 编排插件（+ 自带桩/机器人/场景）。插件不外挂第三方下载库。`template/` 不参与插件运行期依赖。

## 3. 数据模型

无持久化数据库。核心"数据"为：

- **拓扑模型**（DSL → 内存）：`Topology { backends[], proxies[], routes }`——后端集合（平台/版本/端口）、代理集合（平台/监听端口）、代理→后端路由。
- **结果文件**（properties）：桩写出的 `status`(PASS/FAIL) / `message` / 场景特定字段（如 `rewardCount`/`costLeft`/`txId`）。verify 任务读取判定。
- **控制协议消息**（机器人↔桩，聊天/插件消息通道）：如 `E2E_READY:<scenario>`、`E2E_STRESS_RESULT:...`、`E2E_DISCONNECT_NOW:<...>`、`E2E_UI_TOKEN:<uuid>`。

## 4. 接口

对外接口 = ① Gradle `mcTestkit { }` DSL；② 自动生成的任务（命名约定）；③ 环境变量约定（jar 路径/超时/规模覆盖）；④ 机器人↔桩控制协议。详细契约见 [`API.md`](API.md)，此处只给定位。

## 5. 关键机制

- **进程编排**：前台 `runServer`（被测桩跑完自停，回收 Gradle 线程）+ 后台代理/集群后端 JVM + 后台机器人进程；后台进程用 pid 文件 + `ProcessHandle` 在 finalizer/finally 收尾，保证不残留。
- **集群编排**（FR-10）：声明 `backends(...)` 的场景把 N 个后端**全部后台**起（各自运行目录 + BungeeCord 后端模式），代理写**单 listener + N 具名 server**（server 名 = 后端名，供 bot 经代理 `/server <name>` 切换），以**结果文件**为权威完成信号轮询；正常/失败/中断三路径都 `finalizedBy` 停集群任务 + try/finally 双保险收尾全部后端与代理（端口干净）。见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- **压测编排**（FR-11）：声明 `stress {}` 的场景把 N 个后端全部后台起，代理写**N listener 一端口对一后端**（`priority` 钉服，bot 连某端口钉死在该后端、不切服）或直连；每服起 `botsPerServer` 个 bot 进程钉本服持续随机施压，各服桩收集本服各 bot `E2E_STRESS_RESULT`、到 duration 末聚合写**本服**结果文件；框架读**全部 per-server 结果文件**聚合判定（任一缺失/FAIL 即失败并报哪服）。业务不变量（不超卖等）由消费方桩查共享 DB 自行判，框架只收集 + 聚合（守结果文件唯一权威）。三路径都 `finalizedBy` + try/finally 双保险收尾全部后端 + 代理 + bot。见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- **环境契约固化**（一处修、处处生效，源自首个接入项目的实证）：经代理时固定机器人 mineflayer 协议版本为后端 MC 版本；后端 `spigot.yml settings.bungeecord` 与 `config/paper-global.yml proxies.bungee-cord.online-mode`；依赖插件（数据源/Redis 等）注入与缺失校验。
- **缓存**：内置下载模块按 平台/版本/构建号 缓存已下载的服务端/代理 jar（hash 校验复用）+ 持久运行库缓存，避免反复下载；运行目录可清理但保留运行库。
- **判定**：桩写 properties 结果文件 → verify 任务读取 → 转成 Gradle 任务成功/失败（CI 退出码）。

## 6. 部署

不部署到服务器。本身作为 Gradle 插件发布到 **maven.wcpe.top**，消费方在 `plugins { }` 应用。运行环境需：JDK（运行服务端/代理所需版本）、Node ≥ 18（机器人）、首次运行需网络下载服务端/代理（或预置缓存 / 环境变量覆盖）、被测插件所需的依赖服务（如 MySQL/Redis 容器，由消费方提供）。

## 7. 关键裁决与不做项

- 技术栈与形态（Kotlin + Gradle 插件）+ 下载/运行自实现（不外挂第三方下载库）：见 [ADR-0001](adr/0001-gradle-plugin-and-self-provisioning.md)。
- 复用策略（本期只做插件 + 模板，不发布共享桩/机器人库）：见 [ADR-0002](adr/0002-plugin-and-template-only.md)。
- 平台范围（Paper/Folia + 三代理；不含 Spigot/Bukkit/Sponge）：见 [ADR-0003](adr/0003-p1-platform-scope.md)。
- 进程编排模型（后台代理/集群 + 前台 runServer + pid 收尾 + 环境契约固化）：见 [ADR-0004](adr/0004-orchestration-model.md)。
- Kotlin 语言/API 版本锁 1.9（KTS 构建、纯 Kotlin、兼容 K1/K2 与 Gradle 版本范围）：见 [ADR-0005](adr/0005-kotlin-language-version.md)。
- 集群/压测 DSL 与编排（场景块加法新增 `backends(...)`，不增顶层块；集群 = N 后端全后台 + 单 listener 代理 + 轮询结果文件；补充 ADR-0004/0006）：见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- **当前不做**：真实游戏客户端驱动；Spigot/Bukkit/Sponge 后端；共享桩 / 机器人发布物（留待第 2 个消费者验证后）。
