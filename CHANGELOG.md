# 变更日志

本项目所有重要变更记录于此。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 未发布版本

### 新增
_（暂无）_

### 变更
_（暂无）_

### 修复
_（暂无）_

### 移除
_（暂无）_

## [0.4.1] - 2026-07-04

### 修复
- **PaperMC 下载服务迁移（`provision/`）**：将 Paper / Folia / Velocity / Waterfall 的下载解析从已 sunset 的 `api.papermc.io/v2` 迁移到 PaperMC Fill v3（`fill.papermc.io` / `fill-data.papermc.io`），改为读取 `server:default` 对象存储下载 URL 与 sha256，并补齐符合新服务要求的 User-Agent。修复旧端点在 2026-07-01 sunset 后返回 HTTP 410 导致内置 jar 下载不可用的问题；已实机下载校验 Paper、Folia、Velocity、Waterfall 与 BungeeCord jar。

## [0.4.0] - 2026-06-29

> 持久手测模式 serve（第三期·完善）：在自动化 E2E 之外新增一种**并存**的生命周期——复用 `mcTestkit { }` 声明的真实「（可选代理 +）后端 + 被测/依赖插件 + 固化环境契约」拓扑，**起起来挂住**供真人客户端连入手动测试（单后端 / 集群 `/server` 切服 / 可选并起 bot 人机混场），手动停时 Ctrl+C/finally/`stop<Key>Serve` 三重收尾、端口不漏。经**新增第 5 个顶层块** `serve { }` 引入（DSL 4→5 块、加法非破坏，ADR-0006 冻结项 env 前缀/任务命名/协议/结果文件全不动），向后兼容 0.3.0。本机真机覆盖全部 serve 路径（单后端直连/经代理、集群经代理、bot 人机混场、mineflayer 模拟真人客户端连入走动聊天）。见 [ADR-0011](docs/adr/0011-persistent-serve-mode.md)。

### 新增
- **持久手测模式 serve（FR-17，ADR-0011）**：在自动化 E2E 之外新增一种**并存**的生命周期——复用 `mcTestkit { }` 声明的真实拓扑，把「后端（+ 可选经代理）+ 被测/依赖插件 + 固化环境契约」拉起并**挂住**，供真人 MC 客户端连入**手动测试**，而非 bot 驱动 + 结果文件判定 + 自动收尾。经**新增第 5 个顶层块** `serve("name") { backend = …; via = … }` 声明（DSL 由四块演进为五块，加法非破坏、SemVer minor，不改既有块语义）；生成 `serve<Key>`（前台起服、注入插件、就绪后打印连接信息并把后端日志流到控制台、**前台阻塞挂住**到手动停）与 `stop<Key>Serve`（按 pid 兜底收尾）。Ctrl+C 触发 JVM shutdown hook、任务体 `finally`、`stop<Key>Serve` **三重兜底**收尾后端 + 代理（端口不漏、跨平台 pid 收尾）。serve **不判 PASS/FAIL**（不绕过结果文件自判，架构不变量 §3）。**桩空闲机制**：起后端时下发保留哨兵场景 id `__mc_testkit_serve__`（契约常量 `McTestkitContract.SERVE_SCENARIO_ID`）告诉桩进入空闲——`template/harness` 加 `ScenarioName.SERVE` 空闲分支（不驱动 / 不挂超时 / 不写结果 / 不关服、不动真人玩家）；未同步新模板的老桩遇此未知 id 在 `onEnable` 抛错被禁用、服务端照常挂起（对任何桩都安全）。**不做**（FR-17 范围）：世界跨次启动持久化、stdin 控制台转发、在线鉴权（维持 `online-mode=false`）。
- **持久手测·集群拓扑（FR-18，ADR-0011）**：`serve { backends("s1","s2"); via = "wf" }` 把 N 后端 + 代理整套挂起，真人经代理 `/server` 在后端间切换手测、人眼复现跨服 / 崩溃接管类问题。复用 FR-10 集群编排（N 后端全后台 + 单 listener 代理 + 各桩哨兵空闲）+ FR-17 serve 挂起；阻塞等代理进程（某后端宕仍挂着便于看 fallback），`stop<Key>Serve` / Ctrl+C(shutdown hook) / finally 三重收尾全部后端 + 代理。配置期校验集群 serve 须 via、via 路由覆盖全部后端、与单后端 `backend` 互斥（中文报错）。本机真机跑通——2 后端 + Waterfall 代理整套挂起、两桩均哨兵空闲不自停、挂住后 stopDevServe 按 pid 收尾全部、3 端口释放无残留。
- **持久手测·可选并起 bot 人机混场（FR-19，ADR-0011）**：`serve { bot { } }` 可选起场景 bot 把环境驱到某状态（造数据 / 模拟其他玩家），但**不据结果文件判定 / 收尾**——挂住让真人同时连入「人机混场」同测。`ServeSpec` 加 `bot()` / `bot("角色")`（复用 `BotSpec`，多 bot 规则同 FR-16）；起服就绪后起 bot（单后端直连 / 经代理固定协议版本；集群下发 `CLUSTER_BACKENDS` 可 `/server` 切），`stop<Key>Serve` / Ctrl+C / finally 三重收尾含 bot；起 bot 时 `serve<Key>` `dependsOn npmInstallE2eBot`。`launchBots` / `stopBots` 抽出供场景与 serve 共用（`launchScenarioBots` 委托之，行为不变）。配置期校验 serve bot count>=1 / 多 bot 唯一 role / 展开 key 唯一（中文报错）。**注意**：serve 桩空闲不发 `E2E_READY`，serve 的 bot 应用「自驱」action（勿复用等桩 ready 的场景 action）。本机真机跑通——serve 起 bot `Filler` 入服、服务端挂住、stopDevServe 按 pid 收尾后端 + bot、端口释放无残留。

## [0.3.0] - 2026-06-26

> 第三期推进：把自举实机 E2E 从「单服 / 集群 / 压测」三条扩成**全矩阵**——单服(±bot) / 经代理（Waterfall·BungeeCord·**Velocity**）/ 集群 / 压测 / 单场景多 bot（FR-16）/ 崩溃接管（FR-15）/ **Folia 后端**，平台经 `-P` 参数化跑遍「代理 × 后端」；并**实装 Velocity modern forwarding**（补齐 ADR-0003 三代理平台，消除「声明支持却跑不通」的漂移）。集群/压测起 bot 前加端口就绪门，让慢 CI（2 vCPU runner）也稳。全矩阵已本地实机 + CI 实机跑通。向后兼容 0.2.3（任务名 / env / DSL 均加法、无破坏）。

### 新增
- **`template/` 通用薄 `multi-bot` 示例场景（FR-16 自举覆盖）**：桩骨架新增 `ScenarioName.MULTI_BOT` + 多 bot 入服聚合分支——多个各唯一 username 的 bot 直连入服，桩按入服玩家名收集、settle 窗口（15s）末聚合写 PASS（details 含 `count` / `joinedBots`），不含业务玩法，与既有 `cross-server` / `continuous-stress` 薄示例同族；bot 内核加 `multi-bot` action 分发（`scenarios/multiBot.js`）。配合 `e2e.yml` 新增 `e2eMultiBotWithBot` 自举路径 + CI 唯一 username（P1/P2/P3）断言，真机验证 FR-16「多进程唯一身份注入 + 全回收」链路。补充 v0.2.2「不预置多 bot 示例到 template」决策：业务形态多 bot 仍由消费方自加，仅预置此通用薄示例供自举（见 docs/specs/fr-16）。
- **`template/` 桩骨架 Folia 调度兼容（Folia 后端自举覆盖）**：Folia（区域化线程）不支持 Bukkit 全局调度器（`server.scheduler.runTask*` 抛 `UnsupportedOperationException`）。桩改为运行期**反射探测** Folia（存在 `io.papermc.paper.threadedregions.RegionizedServer` 即 Folia）——是则经 `GlobalRegionScheduler.run/runDelayed` 调度，否则走原 Bukkit 调度器；编译期不依赖 Folia 专有 API（反射），对各版本 `paper-api` 均可编译，**Paper 行为不变**。并在 `plugin.yml` 声明 `folia-supported: true`——否则 Folia 直接拒绝加载本插件（`not marked as supporting Folia`），桩根本不会启动。使同一份桩可直接用于 Paper 或 Folia 后端（FR-02/03 支持的后端平台）。配合 `e2e.yml` 新增 `e2eSmoke -Pe2e.backend=folia` 自举路径（Folia 1.20.1，已本地实机跑通）。
- **`template/` 崩溃接管 `crash-takeover` 示例场景（FR-15 自举覆盖）**：桩骨架新增 `ScenarioName.CRASH_TAKEOVER`——默认后端收到 bot 发的 `E2E_TRIGGER_CRASH`（template 约定标记）即 `Runtime.halt` 模拟宕机（不写结果）；bot 内核加「场景级断线重连」（`reconnectOnDisconnect`，默认行为不变），断线后经代理 fallback 重连到存活后端，发 `E2E_CLUSTER_ARRIVED`，存活桩判 PASS；新增 `scenarios/crashTakeover.js`。验证 FR-15「默认后端宕机 → bot 经代理回退到存活后端」的**框架层** fallback 路由（业务层租约 TTL 接管仍由消费方桩在存活后端查共享 DB 改判）。配合 `e2e.yml` 新增 `e2eCrashTakeoverCluster` 自举路径（复用现成集群编排，插件零改）。
- **实现 Velocity 代理 modern forwarding（补齐三代理平台，ADR-0010）**：此前编排对 Velocity 仅 `project.logger.warn` 不生成配置（「声明支持却跑不通」，与 ADR-0003 平台承诺漂移）。现新增 `config/VelocityProxyConfig`（生成 `velocity.toml`：modern forwarding + `forwarding.secret` 文件 + `force-key-authentication=false`（放行无签名 key 的离线机器人，否则 1.19+ 离线 bot 入服/切服被踢）+ 显式空 `[forced-hosts]`（否则 Velocity 用默认示例 forced-hosts 引用不存在 server 拒绝启动）+ N 具名 server + `try` 落地/fallback 顺序）与 `config/BackendVelocityConfig`（后端 `paper-global proxies.velocity.{enabled,online-mode,secret}` 两件套 + 离线 `server.properties`，**不写** spigot.yml bungeecord），编排单后端经代理 / 集群两处接 Velocity；Velocity 用自有版本号（`McTestkitDefaults.VELOCITY_VERSION` 缺省 `3.3.0-SNAPSHOT`，env `…VELOCITY_VERSION` 可覆盖）。**支持**单后端经代理、集群 `/server` 切换与崩溃接管 fallback；**不支持压测钉服**——Velocity 单端口无法「一端口对一后端」，`stress + via=velocity` 配置期中文报错。YAML 深合并 helper 抽到 `config/YamlEditing.kt` 供 BungeeCord/Velocity 共用（去重）。配合 `e2e.yml` 经 `-Pe2e.proxy=velocity` 跑 `e2eExampleBotViaPx` / `e2eCrossServerCluster` 自举。

### 变更
- **集群/压测起 bot 前加端口就绪门（编排稳定性）**：起 bot 前先轮询等全部后端 + 代理端口可 TCP 连接再放 bot（Paper 启动末尾才绑监听端口≈服务端就绪、桩已起），**确定性**等进程就位再连——取代「bot 盲目重试去赛进程启动」的时序竞态。慢 CI（2 vCPU runner 上多服顺序起服、CPU 紧张、后端世界生成慢）上不再间歇性「等待玩家加入超时」；快环境几秒就绪、不受影响。端口迟迟不开（启动失败）则到就绪门上限（300s）报清晰中文错误，而非含糊的「等待玩家超时」。同时放宽 `wait-for-player` / bot `connectTimeout` / workflow `timeout-minutes` 作失败兜底。
- **自举实机 E2E 矩阵扩展（CI）**：`e2e.yml` 一次性消费者工程改为按 `-Pe2e.proxy`（waterfall/bungeecord/velocity）与 `-Pe2e.backend`（paper/folia）+ `-Pe2e.backendVersion` **参数化平台**，同一套场景/任务名跑遍「代理 × 后端」矩阵，避免为每个平台另起消费者工程。新增三条自举路径：单服 + bot 直连（`e2eExampleBotWithBot`）、经 Waterfall 代理单后端 + bot（`e2eExampleBotViaPx`，补 FR-08 金标准路径的自举覆盖）、经 BungeeCord 代理集群跨服（`e2eCrossServerCluster -Pe2e.proxy=bungeecord`）。Velocity 维度因单端口不支持压测钉服，消费者按 `-Pe2e.proxy` 自动跳过压测场景。纯 CI / 自举测试增强，不改插件与 `template/`。

## [0.2.3] - 2026-06-23

> 收口首批代码 review 发现的修复：FR-16 多 bot 配置期 key/username 唯一性校验、FR-02 下载缓存原子化与下载器加固、FR-06 结果文件原子落盘契约，以及若干健壮性 / 注释收口。均为对已交付能力的正确性 / 健壮性硬化，纯修复、无新功能、无破坏性变更，向后兼容 0.2.2。

### 修复
- **多 bot 展开 key / username 唯一性校验**（FR-16，修 review J1）：配置期此前只校验 `role` 唯一，但 `role` 不同的两个 bot 展开后仍可能撞 pid/log key——如 `bot("w"){count=2}`（派生 `w-1`/`w-2`）与 `bot("w-1"){}`（派生 `w-1`）——撞车会让 `bot-w-1.pid` 互相覆盖、收尾按 key 杀时漏掉一个进程而**残留占端口**（违反「多 bot 全回收」）。新增 `BotProcessPlanner.firstConflict` 以**展开真源**查 key/username 重复，配置期中文报错；补穷举与配置期复现测试。
- **下载缓存原子化，杜绝并发读到半成品 jar**（FR-02，修 review J2）：临时文件改在缓存目标**同目录**创建（与目标同卷），移入缓存用 `Files.move(ATOMIC_MOVE)` 原子替换——并发解析同一 jar 时读者只会看到「无文件」或「完整文件」，消除旧实现「系统 temp 跨卷 → 退化为非原子 `copyTo` → 覆盖期间被读到半成品」的缓存损坏；并修 `moveIntoCache` 旧 `check(...)` 吞掉 `copyTo` 失败的死代码（移动失败现抛中文错误）。
- **进程 pid 落盘失败即强杀，不留无法收尾的孤儿**（FR-02）：`ServerLauncher` 起进程后写 pid 文件失败时强制结束刚启动的进程并抛中文错误（避免「进程在跑但无 pid 可收尾」残留占端口）。
- **下载器加固**（FR-02）：`Downloader` 拒绝 `https → http` 的不安全重定向降级、`fetchText` 加 16 MiB 响应上限（挡异常/被劫持的超大响应吃满内存）、连接/读取超时提为具名常量；`JsonLite` 畸形数字字面量（如 `1.2.3`）改抛中文错误而非英文 `NumberFormatException`，`\f` 转义分支用 `''` 字面量替代源码中的裸控制字符。
- **结果文件须原子落盘**（FR-06，修 review J3）：`docs/API.md §3.5` 明确约定桩写结果文件须原子完成（写同目录临时文件 + 原子 rename），避免编排 verify 读到「写了一半」的结果而误判（集群/压测轮询结果文件、消费方桩异步写时存在并发窗口）；`template/harness` 的 `ScenarioResultWriter` 改为原子写出作示范。同时澄清 `ServerProperties` 的「保留」仅就**键值**而言——底层 `Properties.store` 不保留注释/键序、且带时间戳，写回非逐字节幂等（对 E2E 用途无影响）。

## [0.2.2] - 2026-06-23

> 单场景多 bot（FR-16）：一个 `scenario { }` 可驱动多个 bot——异质具名角色（如管理 GUI 的 `admin` + `target`）+ 同质批量复制（`bot { count = N }`，各唯一 username、经 `BOT_INDEX` 区分），用于集群多 bot 各自经代理切服、单后端多 bot 直连分角色。复用既有 env / 任务名、零新增，与压测「同质钉服」划清边界。纯增量，向后兼容 0.2.1；交付以解锁下游 AllinInventorySync「N 玩家切服不回档」与「管理 GUI 双角色」两个 E2E（实机由下游接 g16 / gui-edit 闭环验收）。

### 新增
- **单场景多 bot**（FR-16）：一个 `scenario { }` 可驱动多个 bot——**异质**（多个具名 `bot("角色") { }`，各自 `username` / `action` / `env`，如管理 GUI 的 `admin` + `target` 双角色）与**同质批量**（`bot { count = N }` 复制 N 份，各唯一 `username`、经 `BOT_INDEX`（1..N）区分，如集群 N 个并发切服玩家）。用于集群（多 bot 各自经代理 `/server` 切）与单后端（多 bot 直连，可分角色）。**零新增 env / 任务名**：复用 `BOT_USERNAME`（多进程强制唯一）/ `BOT_ACTION` / `BOT_INDEX` / `CLUSTER_BACKENDS`，声明多 bot 时既有 `launch<Key>Bot` / `e2e<Key>` / `e2e<Key>WithBot` / `e2e<Key>Cluster` **起多个 bot 进程**并随场景结束按 pid 全部收尾（集群多 bot 收尾并入 `stop<Key>Cluster`）。与压测 FR-11「同质钉服」划清边界（压测场景禁用 `count` / 多 bot）。结果仍由桩按 username/index 聚合（结果文件唯一权威）。新增纯函数 `BotProcessPlanner`（`expand` 场景 bot 列表 → 每进程身份；`extraEnvironments` → 每进程追加 env：唯一名/序号/共享 `CLUSTER_BACKENDS`，均穷举单测）与配置期校验（`count>=1`、多 bot 须各有唯一 `role`、压测禁 `count`/多 bot，中文报错）。下游 AllinInventorySync 据此迁移「N 玩家切服不回档」与「管理 GUI admin/target 双角色」两个 E2E。见 docs/specs/fr-16-multi-bot-per-scenario.md、ADR-0009。向后兼容：现有单 `bot { }` 行为不变。

## [0.2.1] - 2026-06-23

> 集群代理崩溃接管 fallback（FR-15）：listener `priorities` 含全部后端，默认后端宕机时 bot 重连经代理回退到存活后端，支撑「崩溃接管」类 E2E（消费方 AllinInventorySync G15 实机 PASS）。纯增量，向后兼容 0.2.0。

### 新增
- **集群代理 listener `priorities` 改为全部后端有序列表**（FR-15，首个仍为默认服 + 其余作 fallback）：默认后端宕机时 bot 重连经代理**回退到下一个存活后端**，支撑「崩溃接管」类 E2E（某后端崩溃 → bot 落到存活后端、由其在归属租约 TTL 过期后接管上线）。正常 `/server` fast-transfer 切换与 `force_default_server` 落默认服行为不受影响。消费方 AllinInventorySync 据此迁移 G15 崩溃接管 E2E（实机 PASS）。`ClusterProxyConfigTest` 增 priorities 含全部后端的断言。

### 修复
- CI / 构建：`gradlew` 与 `template/harness/gradlew` 之前以非可执行（100644）入库（Windows 提交丢失 Unix 可执行位），导致 Linux runner / Unix 环境 `./gradlew` 报 `Permission denied`、CI 构建失败；改为可执行（100755）入库，修复 CI 与「消费方照抄 `template/` 后在 Unix 上直接跑 `./gradlew`」。

## [0.2.0] - 2026-06-23

> 面向下游跨服一致性 E2E 接入的增量版本：编排为每个后端下发声明名，使消费方能给同组各服派生不同 `server-id`（跨服归属 / 转服交接所需）；附测试环境默认 peaceful 难度、桩兼容更新版 Kotlin 编译的被测插件 API，以及 CI（GitHub Actions）与 ktlint 风格门禁的工程化。纯增量，向后兼容 0.1.0。

### 新增
- **每后端身份注入**（FR-12）：编排起**每个**后端（集群 / 压测后台、单后端前台三条路径）时下发新 env `MC_TESTKIT_E2E_BACKEND_NAME` = 该后端 DSL 声明名（与下发给 bot 的 `CLUSTER_BACKENDS` 同源、有序对应）；消费方据此 per-backend 派生身份（如同组各服不同 `server-id`，跨服归属 / 转服交接所需）。`template/harness` 演示读取并写入结果明细。编排只「告诉每个后端它是谁」，不规定怎么用。见 docs/specs/fr-12-per-backend-identity.md。
- **测试环境默认 peaceful 难度**（FR-13）：最小 `server.properties` 默认写 `difficulty=peaceful`，保护测试玩家不被怪物 / 环境杀；**仅在消费方服务端模板未设 `difficulty` 时才默认**，模板已设则保留其值。
- **桩兼容更新版 Kotlin 编译的被测插件 API**（FR-14）：`template/harness` 加 `-Xskip-metadata-version-check`，使桩能 `compileOnly` 引用「元数据版本高于本工程编译器可读上限」的被测插件类（仅编译期跳过，运行期字节码仍兼容）。
- CI/CD：新增 GitHub Actions 工作流——`ci.yml`（每次 push / PR 跑插件构建 + 单元/TestKit 测试 + 模板 bot prettier/eslint/audit）、`e2e.yml`（手动或打版本 tag 触发，自举跑通 smoke + 集群跨服 + 持续压测三类实机 E2E）；README 加 CI / E2E 状态徽章。
- 代码风格门禁：接入 ktlint（`org.jlleitschuh.gradle.ktlint`，风格 `intellij_idea`，规则取舍集中在 `.editorconfig`）；`ktlintCheck` 挂到 `check` → `build`，`./gradlew build` 即跑风格检查；既有代码已用 `ktlintFormat` 规整。

## [0.1.0] - 2026-06-22

> 首个正式版本：面向 Minecraft 插件的「全平台 E2E 编排」Gradle 插件 + 配套脚手架模板。内置自实现的服务端/代理下载与运行，声明式拓扑 DSL，覆盖单后端、经代理、多后端集群跨服切换、多后端持续压测；以首个接入项目为消费者实机跑通 smoke、经 Waterfall 代理购买、跨服集群与持续压测。平台范围 = Paper/Folia 后端 + Velocity/Waterfall/BungeeCord 代理（不含 Spigot/Bukkit/Sponge，ADR-0003）。

### 新增
- **Gradle 插件骨架**（`top.wcpe.mc-testkit`）：`java-gradle-plugin` + `kotlin-dsl`，Kotlin 语言/API 版本锁 1.9（同时兼容 K1/K2 消费方），发布到 maven.wcpe.top（凭据走 Gradle 属性 / 同名环境变量，不入库）。
- **内置下载与运行**（`provision/`）：插件自实现下载——Paper/Folia/Velocity/Waterfall 经 PaperMC 下载 API、BungeeCord 经 SpigotMC Jenkins，按 平台/版本/构建号 缓存并 hash 校验复用；`MC_TESTKIT_E2E_*_JAR` 覆盖优先且跳过下载、`*_VERSION` 可覆盖；精简子进程启动助手（`-jar` 起服务端/代理、pid 落盘）。**不外挂第三方下载库**。代理下载版本缺省取后端版本，Waterfall 在 PaperMC 按 major.minor 归一（避免传补丁号版本 404）。
- **声明式拓扑 DSL**（`mcTestkit { }`）：声明后端 / 代理 / 路由 / 场景 / 依赖注入；端口按基数 + 序号推导（显式优先）；配置期校验节点重名、路由目标、端口冲突、场景引用缺失等并报**中文**错误。
- **任务自动编排**（`task/` 整合器）：按声明数据驱动注册 `prepareE2e<Key>` / `e2e<Key>` / `e2e<Key>Via<Proxy>` / `launch<Key>Bot` / `e2e<Key>WithBot` 及固定名 `npmInstallE2eBot` / `syncE2eRuntimeCache` / `purgeE2eRuntimeCache`；前台后端自停 + 后台代理 / 机器人按 pid 在 `finalizedBy` + try/finally 双保险收尾（不残留占端口）；判定**只认结果文件**。机器人目录经 Gradle 属性 `mcTestkit.botDir` 定位，可移植、不写死绝对路径。
- **多后端集群编排**（FR-10）：场景声明 `backends("s1","s2")` 即「集群场景」——N 后端全后台 + 代理「单 listener + N 具名 server」，机器人经代理 `/server <name>` 跨服切换，桩跨服判定；任务 `e2e<Key>Cluster` / `stop<Key>Cluster`；env `MC_TESTKIT_E2E_CLUSTER_BACKENDS`。见 ADR-0008。
- **多后端持续压测编排**（FR-11）：场景声明 `stress { botsPerServer; durationSeconds }` 即「压测场景」——N 服 + 代理「N listener 一端口对一后端」钉服（或直连），每服 M 个 bot 进程钉本服持续随机施压，各服桩收集本服各 bot `E2E_STRESS_RESULT` 聚合写本服结果，框架读全部 per-server 结果聚合判定；业务不变量（不超卖等）由消费方桩查共享 DB 自断言（框架只收集 + 聚合）；任务 `e2e<Key>Stress` / `stop<Key>Stress`；env `BOT_INDEX` / `STRESS_RANDOM_SEED` / `STRESS_DURATION_SECONDS`。见 ADR-0008。
- **固化环境契约**（`config/`，一处固化、消费方默认生效）：`server.properties` 真实读改写回（保留未涉及键）；BungeeCord 后端三件套（`online-mode=false` + `spigot.yml settings.bungeecord` + `paper-global.yml proxies.bungee-cord.online-mode`）与代理 `config.yml`（单后端 / 集群 / 压测三种拓扑）**均用真实 YAML 对象读写 / 深合并**（snakeyaml）生成，不做字符串 / 正则替换；经代理机器人协议版本固定为后端版本；依赖（数据源 / Redis）注入缺失的通用中文报错（不写死具体依赖名）。
- **机器人驱动 + 结果判定**（`bot/` + `verify/`）：mineflayer 机器人后台进程启动、pid 收尾（跨平台）、按 `MC_TESTKIT_E2E_` 名构建连接 / 超时 / 协议环境变量；结果文件读取判 PASS/FAIL（只认结果文件、缺失或失败抛中文错误）。
- **`template/` 脚手架**（纯拷贝物，不入插件构建）：`harness/` Kotlin Bukkit/Paper 桩骨架（独立 Gradle 子工程、paper-api compileOnly、内置 smoke / example-bot / cross-server / continuous-stress 场景、结果写出、控制协议）+ `bot/` mineflayer 机器人内核（端口探测 / 重试、控制消息等待、按 action 分发、示例 / 跨服 / 压测场景、eslint + prettier）+ 复制接线说明。
- **冻结对外契约**：DSL 四个顶层块、任务命名约定、环境变量前缀 `MC_TESTKIT_E2E_`、机器人↔桩控制协议、结果文件约定（见 `docs/API.md`、ADR-0006）。

> 发版时把"未发布版本"段切成 `## [X.Y.Z] - YYYY-MM-DD`，再新建空的"未发布版本"段。
