# 变更日志

本项目所有重要变更记录于此。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 未发布版本

### 新增
- CI/CD：新增 GitHub Actions 工作流——`ci.yml`（每次 push / PR 跑插件构建 + 单元/TestKit 测试 + 模板 bot prettier/eslint/audit）、`e2e.yml`（手动或打版本 tag 触发，自举跑通 smoke + 集群跨服 + 持续压测三类实机 E2E）；README 加 CI / E2E 状态徽章。
- 代码风格门禁：接入 ktlint（`org.jlleitschuh.gradle.ktlint`，风格 `intellij_idea`，规则取舍集中在 `.editorconfig`）；`ktlintCheck` 挂到 `check` → `build`，`./gradlew build` 即跑风格检查；既有代码已用 `ktlintFormat` 规整。

### 变更
_（暂无）_

### 修复
_（暂无）_

### 移除
_（暂无）_

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
