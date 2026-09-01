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
  - `McTestkitExtension`（DSL 扩展）：声明拓扑（代理/后端/路由）、场景、依赖注入项，以及 backend/proxy 的节点环境、模板、代理专属插件、JVM 参数与 Java Agent；只忠实记录声明，不含行为。
  - **内部包接缝**（让第一期各 FR 各占一包、文件隔离并行；入口与共享契约只在 FR-01 落地一次）：
    - `contract/`（FR-01 冻结，全包共享）：对外契约常量——插件 id/扩展名/默认值、环境变量名集（前缀 `MC_TESTKIT_E2E_`）、任务命名约定、机器人↔桩控制协议、结果文件约定。
    - `dsl/`（FR-01 冻结形态 → FR-22 加法扩展）：平台枚举（仅 Paper/Folia + 三代理）+ DslMarker、Backend/Proxy/Scenario/Bot/Dependencies Spec；proxy 可声明独立版本与 Java 主版本，backend/proxy 可声明 JVM 参数与 javaagent。
    - `model/`（FR-03）：拓扑模型（`Topology`/`ResolvedBackend`/`ResolvedProxy`）与纯函数解析、端口推导（基数+序号）、配置期校验（节点名唯一且后端/代理不相撞、路由目标存在、端口不冲突、场景引用存在）。解析模型同时携带 FR-20 的节点环境、模板声明与代理专属插件声明，以及 FR-22 的运行时/JVM 声明，并在配置期拦截非法环境变量名及大小写不敏感的 `MC_TESTKIT_E2E_` 保留前缀。任务图无环属 `task/`（FR-04）。
    - `provision/`（FR-02/FR-22）：内置服务端/代理下载与运行——Paper/Folia/Velocity/Waterfall 经 PaperMC 下载 API、BungeeCord 经 SpigotMC Jenkins，带本地缓存与校验；按节点平台+版本解析 jar，并由 `JavaRuntimeSelector` 选择节点 JVM。`ServerLauncher` 按构件形态选路：自包含 jar 与 paperclip 引导件走 `java -jar`，运行目录带 `libraries/` 的 thin jar 改为经只含清单的启动器 jar 传完整 classpath（`Class-Path` 条目按 UTF-8 百分号编码，规避空格 / 中文被截断）。**自实现、保持精简**，不搬运插件市场下载等非必需能力。
    - `serverconfig/`（FR-05）：环境契约固化（纯函数，仅依赖 `contract/` 与 JDK）——`ServerProperties`（`server.properties` 读改写回、保留未涉及键）、`BackendBungeeCordConfig`（BungeeCord 三件套：`server.properties online-mode=false` + `spigot.yml settings.bungeecord` + `config/paper-global.yml proxies.bungee-cord.online-mode`）、`ProxyProtocolVersion`（经代理时机器人协议版本=后端版本的固定规则）、`DependencyInjections`（依赖注入缺失的**通用**中文报错机制，不写死具体依赖名）。不注册任务（由 `task/` 接入）。
    - `bot/` + `verify/`（FR-06）：机器人子进程启动与 pid 收尾、结果文件读取判 PASS/FAIL。`bot/` 含 `nodeCommand()`（跨平台 node 可执行）、`BotConnection`（按 `MC_TESTKIT_E2E_` 名构建机器人环境变量的纯函数）、`BotLauncher`（后台启 mineflayer、写 pid、场景 action 经 env `BOT_ACTION` 传给机器人内核）、`stopProcessByPidFile`（ProcessHandle 温和退出→超时强杀，缺失/已退出安全 no-op）；`verify/` 含 `ResultReader`（读 `<scenario>.properties`，缺失或 status≠PASS 抛中文错误）。仅依赖 `contract/` 与 JDK，不注册任务（由 `task/` 接入）。
    - `task/`（FR-04，整合器）：在 `McTestkitPlugin.apply()` 的 `afterEvaluate` 里，经 `TopologyResolver.resolve` 复用配置期校验后，按 `mcTestkit { }` 声明**数据驱动**注册任务。`NodeRuntimeInjection` 统一完成 FR-20 资源预检、`envOrPath` 解析、backend/proxy staging 与环境合并：一次任务先解析全部参与节点资源，成功后后端按“清理并保留运行库缓存 → 铺模板 → 写权威配置 → 注入 `dependencies { }`”准备；代理按“整目录清理 → 铺模板 → 写权威配置 → 平台准备 → 注入代理专属插件”准备。普通直连/经代理、集群、压测、单 serve、集群 serve 的全部既有启动入口都复用该入口，最终仍只由 `ServerLauncher` 启动 JVM 子进程。路径推导（`RunLayout`）、依赖解析（`resolveDependencyJars`）、代理配置生成与多 bot 展开保持原职责；任务副作用全在 `doLast`，后台进程继续以 pid + `finalizedBy`/`try-finally` 双保险收尾。只调上述各包公开 API，不反依赖消费项目 / `template/`。
- **`template/`**（脚手架，纯拷贝物，不被插件代码依赖；FR-07）
  - `harness/`：服务端桩插件骨架（Kotlin，框架无关的 Bukkit/Paper 插件，**独立 Gradle 子工程**，自带 `settings.gradle.kts` / `build.gradle.kts`、`paper-api` compileOnly，不入 root settings）：配置加载、入服派发场景、结果写出、与机器人的控制协议、内置 `smoke` + `example-bot` 两个场景。
  - `bot/`：mineflayer 机器人内核（Node ≥18）：端口探测 + 连接/重试、控制消息等待、文本归一、按 action 分发场景、`example-bot` 示例；eslint + prettier。窗口/背包辅助、压测循环刻意不预置（避免把业务玩法固化进骨架），消费方按真实场景自补。
  - 复制接线说明（`template/README.md`）。
  - 协议消息名 / 结果文件键 / env 名以**字面量**对齐 `contract/`（docs/API.md §3.3/3.4/3.5），不 import 插件包——保持 template 与插件零编译期耦合（双向都不依赖）。

**依赖方向（单向）**：编排插件（含内置下载/运行模块）← 消费项目 → 编排插件（+ 自带桩/机器人/场景）。插件不外挂第三方下载库。`template/` 不参与插件运行期依赖。

## 3. 数据模型

无持久化数据库。核心"数据"为：

- **拓扑模型**（DSL → 内存）：`Topology { backends[], proxies[], routes }`——后端集合（平台/版本/端口/节点环境/模板声明）、代理集合（平台/监听端口/专属插件声明/节点环境/模板声明）、代理→后端路由。任务层只消费已解析模型，不回读可变 DSL 对象。
- **结果文件**（properties）：桩写出的 `status`(PASS/FAIL) / `message` / 场景特定字段（如 `rewardCount`/`costLeft`/`txId`）。verify 任务读取判定。
- **控制协议消息**（机器人↔桩，聊天/插件消息通道）：如 `E2E_READY:<scenario>`、`E2E_STRESS_RESULT:...`、`E2E_DISCONNECT_NOW:<...>`、`E2E_UI_TOKEN:<uuid>`。

## 4. 接口

对外接口 = ① Gradle `mcTestkit { }` DSL；② 自动生成的任务（命名约定）；③ 环境变量约定（jar 路径/超时/规模覆盖）；④ 机器人↔桩控制协议。详细契约见 [`API.md`](API.md)，此处只给定位。

## 5. 关键机制

- **进程编排**：前台 `runServer`（被测桩跑完自停，回收 Gradle 线程）+ 后台代理/集群后端 JVM + 后台机器人进程；后台进程用 pid 文件 + `ProcessHandle` 在 finalizer/finally 收尾，保证不残留。**端口就绪门**：集群/压测起 bot 前先轮询等全部后端 + 代理端口可 TCP 连接（Paper 在启动末尾才绑监听端口≈服务端就绪、桩已起）再放 bot——确定性等进程就位再连，取代「bot 盲目重试去赛进程启动」的时序竞态，慢 CI（多服顺序起服、CPU 紧张）上稳而非靠拉长超时碰运气（超时仅作失败兜底）。
- **节点运行时注入（FR-20）**：新增 `envOrPath` 对节点模板和代理插件按“非空环境变量值优先，否则声明路径”解析，相对路径以应用插件的 `Project.projectDir` 为基准；任一参与节点资源缺失、类型错误或代理插件目标文件名冲突时，在清理首个运行目录或启动首个进程前中文失败。后端节点模板优先，未声明才回退旧全局 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR`；旧 env 的相对值保持 v0.4.2 `File(raw)` 语义，按 JVM / Gradle 当前工作目录而非应用子工程目录解析。`dependencies { }` 始终只注入后端。代理运行目录每次整目录重建，模板不能覆盖随后写入的框架端口、路由与 forwarding 配置，显式代理插件可覆盖模板中的同名 jar。子进程环境按“宿主 < 节点 < 框架”合并，backend/proxy 节点环境互相隔离，框架变量最终覆盖且启动日志不输出环境值。
- **节点 Java 与诊断参数（FR-22）**：代理可声明独立软件版本和 Java 主版本；backend/proxy 都可追加 JVM 参数与 javaagent。Java 按 `MC_TESTKIT_JAVA_HOME_<主版本>` 优先选择，javaagent 在执行期按 env-or-path 解析；所有 e2e/cluster/stress/serve 路径复用同一 `ServerLauncher` 参数构造器，不泄漏机器路径到公共契约。
- **集群编排**（FR-10）：声明 `backends(...)` 的场景把 N 个后端**全部后台**起（各自运行目录 + BungeeCord 后端模式），代理写**单 listener + N 具名 server**（server 名 = 后端名，供 bot 经代理 `/server <name>` 切换；listener `priorities` 列**全部后端**——首个为默认服 + `force_default_server`，其余作 fallback：默认后端宕机时 bot 重连经代理回退到下一个存活后端，支撑「崩溃接管」类 E2E，正常 `/server` 切换不受影响，见 FR-15），以**结果文件**为权威完成信号轮询；正常/失败/中断三路径都 `finalizedBy` 停集群任务 + try/finally 双保险收尾全部后端与代理（端口干净）。见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- **压测编排**（FR-11）：声明 `stress {}` 的场景把 N 个后端全部后台起，代理写**N listener 一端口对一后端**（`priority` 钉服，bot 连某端口钉死在该后端、不切服）或直连；每服起 `botsPerServer` 个 bot 进程钉本服持续随机施压，各服桩收集本服各 bot `E2E_STRESS_RESULT`、到 duration 末聚合写**本服**结果文件；框架读**全部 per-server 结果文件**聚合判定（任一缺失/FAIL 即失败并报哪服）。业务不变量（不超卖等）由消费方桩查共享 DB 自行判，框架只收集 + 聚合（守结果文件唯一权威）。三路径都 `finalizedBy` + try/finally 双保险收尾全部后端 + 代理 + bot。见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- **单场景多 bot**（FR-16）：一个场景可声明多个 `bot`——`BotProcessPlanner`（`task/`，**纯函数**）把场景 bot 列表 `expand` 为「每进程一项」的计划（`count = N` 同质复制成 N 份各唯一 username/key、`BOT_INDEX` 1..N；多个具名 `bot("角色")` 各成一支），并由 `extraEnvironments` 装配每进程追加 env（进程数 >1 强制下发唯一 `BOT_USERNAME` 盖过消费方单值 override、同质复制下发 `BOT_INDEX`、合入共享 `CLUSTER_BACKENDS`）；编排（`launchScenarioBots`）据此起多个 bot 进程，集群下每个 bot 都收到 `CLUSTER_BACKENDS` 以各自 `/server` 切换。单后端直连 / 经代理 / 集群三路径均起全部 bot，并在 verify 的 try/finally（直连）、任务体 try/finally（经代理/集群）+ `stop<Key>Cluster` 按 pid 收尾**全部** bot（单 bot 路径保持安全 no-op，向后兼容）。复用既有 env / 任务名、零新增；与压测「同质钉服」划清边界（压测场景禁 `count`/多 bot）。配置期校验 `count>=1`、多 bot 须各有唯一 `role`。见 [ADR-0009](adr/0009-multi-bot-per-scenario.md)。
- **环境契约固化**（一处修、处处生效，源自首个接入项目的实证）：经代理时固定机器人 mineflayer 协议版本为后端 MC 版本；按代理平台二选一——经 BungeeCord 系写后端三件套（`spigot.yml settings.bungeecord` + `config/paper-global.yml proxies.bungee-cord.online-mode`），经 **Velocity 写 modern forwarding 两件套**（代理 `velocity.toml` + 后端 `config/paper-global.yml proxies.velocity`，二者共享同一 forwarding secret，见 [ADR-0010](adr/0010-velocity-modern-forwarding.md)）；依赖插件（数据源/Redis 等）注入与缺失校验。
- **缓存**：内置下载模块按 平台/版本/构建号 缓存已下载的服务端/代理 jar（hash 校验复用）+ 持久运行库缓存，避免反复下载；运行目录可清理但保留运行库。
- **判定**：桩写 properties 结果文件 → verify 任务读取 → 转成 Gradle 任务成功/失败（CI 退出码）。
- **持久手测（serve，FR-17）**：与自动化 E2E **并存**的另一种生命周期——`serve<Key>` 前台起后端（声明 `via` 则先后台起代理）、注入被测 + 依赖插件、经 `MC_TESTKIT_E2E_SCENARIO` 下发**保留哨兵** `__mc_testkit_serve__` 使桩**空闲不关服**，就绪后打印连接信息并把后端日志流到控制台，**前台阻塞挂住**供真人客户端连入手测，到用户手动停。Ctrl+C 触发 JVM shutdown hook、任务体 `finally`、与 `stop<Key>Serve`（按 pid）**三重兜底**收尾后端 + 代理（端口不漏，跨平台 pid 收尾）。serve **不判 PASS/FAIL**（不绕过结果文件自判，架构不变量 §3）；不做世界跨次持久化（每次起全新运行目录、保留下载缓存）。声明 `backends(...)` 即**集群 serve**（FR-18）：复用 FR-10 集群编排把 N 后端 + 代理整套后台起（各桩哨兵空闲）、阻塞等代理进程挂住，真人经代理 `/server` 切服手测，停时三重收尾全部后端 + 代理。可选 `bot { }` 即**人机混场**（FR-19）：serve 就绪后起声明的 bot（复用场景 / serve 共用的 `launchBots`）把环境驱到某状态但不据结果文件收尾，挂住让真人同时连入，三重收尾含 bot。见 [ADR-0011](adr/0011-persistent-serve-mode.md)。

## 6. 部署

不部署到服务器。本身作为 Gradle 插件发布到 **maven.wcpe.top**，消费方在 `plugins { }` 应用。运行环境需：JDK（运行服务端/代理所需版本）、Node ≥ 18（机器人）、首次运行需网络下载服务端/代理（或预置缓存 / 环境变量覆盖）、被测插件所需的依赖服务（如 MySQL/Redis 容器，由消费方提供）。

## 7. 关键裁决与不做项

- 技术栈与形态（Kotlin + Gradle 插件）+ 下载/运行自实现（不外挂第三方下载库）：见 [ADR-0001](adr/0001-gradle-plugin-and-self-provisioning.md)。
- 复用策略（本期只做插件 + 模板，不发布共享桩/机器人库）：见 [ADR-0002](adr/0002-plugin-and-template-only.md)。
- 平台范围（Paper/Folia + 三代理；不含 Spigot/Bukkit/Sponge）：见 [ADR-0003](adr/0003-p1-platform-scope.md)。
- 进程编排模型（后台代理/集群 + 前台 runServer + pid 收尾 + 环境契约固化）：见 [ADR-0004](adr/0004-orchestration-model.md)。
- Kotlin 语言/API 版本锁 1.9（KTS 构建、纯 Kotlin、兼容 K1/K2 与 Gradle 版本范围）：见 [ADR-0005](adr/0005-kotlin-language-version.md)。
- 集群/压测 DSL 与编排（场景块加法新增 `backends(...)`，不增顶层块；集群 = N 后端全后台 + 单 listener 代理 + 轮询结果文件；补充 ADR-0004/0006）：见 [ADR-0008](adr/0008-cluster-and-stress-dsl.md)。
- 单场景多 bot（扩 scenario 的 bot 声明加 `count` + `bot("角色")` 重载，复用既有 env/任务名，与压测划清边界；补充 ADR-0008）：见 [ADR-0009](adr/0009-multi-bot-per-scenario.md)。
- Velocity modern forwarding（代理 `velocity.toml` + 后端 `paper-global proxies.velocity` + 共享 secret，Velocity 自有版本号；单端口故**不支持压测钉服**、`stress + via=velocity` 配置期报错；补充 ADR-0004/0008）：见 [ADR-0010](adr/0010-velocity-modern-forwarding.md)。
- 持久手测 serve 模式（非自停挂起生命周期 + 新增 `serve` 顶层块 + 桩空闲保留哨兵场景 id，补充 ADR-0004）：见 [ADR-0011](adr/0011-persistent-serve-mode.md)。
- 代理版本、独立 Java 运行时与 Java Agent 注入（FR-22，加法 DSL，补充 ADR-0010）：见 [ADR-0012](adr/0012-proxy-runtime-and-javaagent.md)。
- **当前不做**：真实游戏客户端驱动；Spigot/Bukkit/Sponge 后端；共享桩 / 机器人发布物（留待第 2 个消费者验证后）。
