# 功能规格：任务自动编排

> 状态：开发中　·　关联 PRD：FR-04　·　分支：feature/fr-04-orchestration

## 1. 背景与目标

FR-04 是第一期（MVP）的**整合器**：把已落地的各包（`contract/` 契约、`dsl/` + `McTestkitExtension` 声明、`model/` 拓扑解析、`provision/` 下载与子进程启动、`serverconfig/` 环境契约、`bot/` 机器人进程与收尾、`verify/` 结果判定）在 `McTestkitPlugin.apply()` 这条接缝上**装配成真正可跑的 e2e 任务**。此前各 FR 只交付库代码、不注册任务；本 FR 让消费方一句 `mcTestkit { }` 声明即数据驱动地生成 `prepareE2e<Key>` / `e2e<Key>` / `e2e<Key>Via<Proxy>` / `launch<Key>Bot` / `e2e<Key>WithBot` 等任务（命名严格按 ADR-0006 / API.md §3.2），并在配置期对非法拓扑报中文错误、在执行期前台起后端 + 后台起代理/机器人 + 按结果文件判定 + pid 收尾。

编排模型沿用 ADR-0004（前台被测后端自停 + 后台代理/机器人 + pid 收尾 + 环境契约固化），下载/运行底座沿用 ADR-0001（内置 `provision/` 自实现下载/运行，不外挂第三方下载库）。本规格不引入新架构决策。

## 2. 需求（要什么）

- 范围内（package `top.wcpe.mc.testkit.task` + 修改 `McTestkitPlugin` 接线）：
  - **配置期校验**：`apply()` 创建扩展后，经 `TopologyResolver.resolve(extension)` 复用 FR-03 的无环/命名/路由/端口/场景引用校验；任一不一致 → 抛**中文** `GradleException`（说明缺什么/撞哪个/怎么补）。任务图本身无环（依赖关系不形成环）、任务名稳定（纯约定派生）。
  - **数据驱动注册任务**（命名严格按 `McTestkitTaskNames`）：遍历 `extension.declaredScenarios`，每个场景生成：
    - `prepareE2e<Key>`：准备运行目录——清理（保留运行库缓存子目录）、写 `eula.txt`、`ServerProperties` 写最小可启动配置（端口/`online-mode=false`/`enforce-secure-profile=false`）、按 `dependencies { }` 声明注入待测插件 jar 与依赖插件 jar（值为环境变量名或路径，运行期解析）。
    - `e2e<Key>`：依赖 `prepareE2e<Key>` → 前台起后端（`provision` 解析+下载 jar、`ServerLauncher` 起子进程、桩跑完自停 → `waitFor`）→ `ResultReader` 读 `<scenario>.properties` 判 PASS/FAIL（**只认结果文件**）。
    - 有 bot 的场景额外：`launch<Key>Bot`（`BotLauncher.launch`，env 由 `BotConnection.toEnvironment` 建）、`e2e<Key>WithBot`（依赖 launch + verify 一键跑）。
    - 经代理（`via` 非空）的场景：`e2e<Key>Via<Proxy>`——prepare 后按代理平台决定是否 `BackendBungeeCordConfig.apply`、`provision` 后台起代理（写 pid）、bot 经代理端口进服且用 `ProxyProtocolVersion.forBackend` 固定协议版本、前台起后端、verify、`finalizedBy` 停代理。
  - **固定名任务**：`npmInstallE2eBot`（`Exec`：bot 目录 `npm install`）、`syncE2eRuntimeCache`（运行库缓存回写持久缓存）、`purgeE2eRuntimeCache`（清空持久缓存）。
  - **进程收尾**（高风险区）：后台代理/机器人在任务结束/失败/中断都被 pid 收尾杀掉、端口释放、无残留——后台代理用 `finalizedBy` 停代理任务 + `try/finally` 双保险，统一走 `stopProcessByPidFile`。
  - **bot 目录定位**：消费方照抄 `template/bot` 到其项目；用 Gradle 属性 `mcTestkit.botDir`（项目属性）定位，缺省相对路径 `e2e-bot`，可移植、不写死绝对路径。入口脚本固定 `src/connectAndWait.js`（template 既定）。
- 不做（范围外）：
  - 不碰其他包的内部实现（只调其公开 API）；不改 `contract/` / `dsl/` / `model/` / `provision/` / `serverconfig/` / `bot/` / `verify/` 源（可改 `McTestkitPlugin` 接线）。
  - 不实现集群/压测多服编排的完整链路（FR-03 拓扑已支持多后端，但本期消费者验证以单后端/单代理为主；集群任务名留待真实多服需求，沿用同前缀风格，不预置空壳）。
  - **不在 TestKit / CI 真跑**：不真实下载、不起进程、不连服——真实起服/起代理/起 bot 判定属 FR-08 实机维度。注册任务时配置期不触发下载/起进程（全部放 `doLast`/lazy）。
  - 不实现 Spigot/Bukkit/Sponge（不在项目计划内）；不发布共享桩/机器人库。
  - 不改 env 前缀为 DSL 可配（ADR-0006）。

## 3. 设计（怎么做）

落在新包 `top.wcpe.mc.testkit.task`（ARCHITECTURE §2 的 `task/` 条），由 `McTestkitPlugin.apply()` 调用一处装配入口。

- **运行目录与缓存路径**（依赖 Gradle `project.layout`，留在 task 包工厂方法/插件侧解析，不写死绝对路径）：
  - 运行目录 `build/mc-testkit/run`、结果目录 `build/mc-testkit/results`、代理运行目录 `build/mc-testkit/run-proxy`、jar 下载缓存根 `<gradleUserHome>/caches/mc-testkit-jars`、持久运行库缓存 `<rootProject>/.gradle/mc-testkit/server-base`。
  - clean 时保留运行库子目录集合（`libraries`/`cache`/`assets`/`versions`），避免连续重跑反复下载（NFR 幂等可重跑）。
- **装配入口** `McTestkitTasks.register(project)`：
  1. `TopologyResolver.resolve(extension)` 得 `Topology`（同时完成配置期校验，失败抛中文 `GradleException`）。
  2. 注册固定名任务（`npmInstallE2eBot` / `syncE2eRuntimeCache` / `purgeE2eRuntimeCache`）。
  3. 遍历场景：解析其 `backend`（缺省取首个后端）与 `via`，注册 `prepareE2e<Key>` / `e2e<Key>`（+ bot 时 `launch<Key>Bot` / `e2e<Key>WithBot`；+ via 时 `e2e<Key>Via<Proxy>`）。
- **纯函数尽量下沉、可单测**：把"运行目录注入计划""env 覆盖取值器"等无副作用部分做成纯函数（如 `RunLayout` 路径推导、注入项解析），任务 `doLast` 只做 IO 编排。任务体内 `provision`/`ServerLauncher`/`BotLauncher`/`ResultReader`/`serverconfig` 全走各包既有公开 API。
- **env 取值**：任务侧用 `project.providers.environmentVariable(name).orNull` 作 `readEnv`/`override` 取值器喂给 `ServerJarProvisioner.create` 与 `BotConnection.toEnvironment`，保持各包纯函数边界。
- **依赖注入校验**：`dependencies { pluginUnderTest / plugin(...) }` 声明的项在 prepare `doLast` 里解析（环境变量名→值 或 直接路径）；解析不到对应 jar → 经 `DependencyInjections.requireAll` 抛中文错误（缺什么/怎么补）。
- **bot 目录**：`project.findProperty("mcTestkit.botDir")?.toString() ?: "e2e-bot"`，相对 `rootProject` 解析为 `BotProcessContext.botDir`，脚本 `botDir/src/connectAndWait.js`，结果目录作 `resultsDir`。

依赖方向：`task/` 单向依赖本仓库各内部包与 Gradle API，不反依赖消费项目 / `template/`（架构不变量）。沿用 ADR-0004 / ADR-0001 既定决策，无需新 ADR。

## 4. 任务拆分

- [ ] 测试先行（TestKit）：消费者 build 配置 `mcTestkit { backend/proxy/scenario(含 bot)/dependencies }` → 断言任务按命名约定注册（`prepareE2e<Key>` / `e2e<Key>` / `e2e<Key>Via<Proxy>` / `launch<Key>Bot` / `e2e<Key>WithBot` / `npmInstallE2eBot` / `syncE2eRuntimeCache` / `purgeE2eRuntimeCache`），`help` / `tasks` 成功、任务依赖关系存在。
- [ ] 测试先行（配置期）：非法拓扑（路由目标不存在 / 重名 / 缺必填 / 路径不存在 / 场景引用缺失）→ 配置期 build 失败且含**中文**错误。
- [ ] 实现 `task/` 包（路径推导纯函数 + 数据驱动任务注册 + 进程收尾）。
- [ ] `McTestkitPlugin.apply()` 接线调用装配入口。
- [ ] 文档同步：PRD 状态、ARCHITECTURE（`task/` 条精化）、API.md（§3.1 botDir 属性、§3.2 任务名核对）、CHANGELOG。

## 5. 验收标准

- 新增 TestKit / 配置期测试红 → 绿；`./gradlew build` 全绿（validatePlugins + 全部测试不回归，基线 93）。
- 消费者应用插件 + `mcTestkit { }` 声明后：`help` / `tasks` 成功；`tasks` 列出按命名约定生成的 e2e 任务（含 `prepareE2e<Key>` / `e2e<Key>` / `e2e<Key>WithBot` / `e2e<Key>Via<Proxy>` / 固定名任务）；任务依赖关系存在（`e2e<Key>` 依赖 `prepareE2e<Key>`）。
- 非法拓扑在**配置期**抛**中文** `GradleException`（TestKit 断言 build 失败且输出含中文错误）。
- 任务图无环、任务名稳定（同一声明多次注册得同名任务）。
- 注册任务时配置期不触发下载 / 起进程（TestKit 不联网、不起服仍能跑过任务注册与 `help`）。
- **实机维度（需用户在 FR-08 备齐服务端 / 代理 / 依赖 / Node 环境确认）**：真实 `e2e<Key>` 起后端跑通并判 PASS、`e2e<Key>Via<Proxy>` 经代理跑通、后台代理/机器人在结束/失败/中断后被收尾且端口释放无残留——单测不替代，标「待 FR-08 实机验」。

## 6. 风险 / 待定

- **TestKit 不真跑进程**：本 FR 的执行期行为（真实起服/起代理/起 bot/收尾）无法在 CI 自动验，只能在 FR-08 实机维度由用户确认。为此把任务体的副作用全放 `doLast`，配置期只做注册与校验，确保 `help`/`tasks`/任务注册测试零网络零进程可过。
- **进程收尾的极端残留**：异常强杀仍可能在极端情况留残留（ADR-0004 已记），排障清理指引归运维文档；本 FR 用 `finalizedBy` + `try/finally` 双保险尽量兜住正常/失败/中断三路径。
- **bot 目录约定**：缺省 `e2e-bot` 相对 `rootProject`；消费方目录命名不同的用 `-PmcTestkit.botDir=...` 覆盖，可移植不写死绝对路径。这是唯一新增可配项，最小化，不动冻结的 DSL 顶层块。
