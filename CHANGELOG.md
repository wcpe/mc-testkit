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
