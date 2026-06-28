# 功能规格：持久开启·单后端手测模式（serve）

> 状态：草拟　·　关联 PRD：FR-17　·　分支：feature/fr-17-persistent-serve　·　决策：[ADR-0011](../adr/0011-persistent-serve-mode.md)

## 1. 背景与目标

开发者要在真实「（可选代理 +）后端 + 被测插件 + 依赖 + 固化环境契约」拓扑里**手动连入测试**（手测、复现要人眼看的 bug、短期开发沙盒 / 演示），不必手搭服务端、直接复用已声明的 `mcTestkit { }` 拓扑。P3 / 第三期。

FR-17 落 **serve 模式核心**：单后端（+ 可选经代理）持久挂起。FR-18（集群多后端挂起）/ FR-19（持久并起 bot、人机混场）在其上**增量**，复用同一生命周期（[ADR-0011](../adr/0011-persistent-serve-mode.md)）。

## 2. 需求（要什么）

- 新增顶层 DSL 块 `serve("name") { backend = <后端名,可省=默认/单后端>; via = <代理名,可省=直连> }`。
- 据声明生成两个任务：
  - `serve<Key>`：前台起后端（声明 `via` 则先后台起代理），注入被测 + 依赖插件，**挂住**到用户手动停。
  - `stop<Key>Serve`：按 pid 收尾后端（+ 代理）的**兜底**任务（供「另一终端停」/「Ctrl+C 没清干净」时用）。
- serve **下发保留哨兵场景 id** `__mc_testkit_serve__`（经 `SCENARIO` env）使注入的桩**空闲、不关服**、**不判定**；被测 + 依赖插件正常加载，真人当「客户端」自己点（桩空闲机制见 §3）。
- 起后端就绪后**打印连接信息**（连 `localhost:<端口>`，直连=后端端口、经代理=代理端口）+ 后端日志路径；并把后端日志**流到 Gradle 控制台**（手测需可见启动与活动）。
- 手动停两条路：① 跑 serve 的终端 **Ctrl+C** → JVM shutdown hook 收尾后端 + 代理、删 pid；② 另跑 `stop<Key>Serve` 按 pid 收尾。
- **范围内**：单后端；可选经代理（Waterfall / BungeeCord / Velocity，复用既有后端代理模式配置 `BackendBungeeCordConfig` / `BackendVelocityConfig` + 代理配置生成）；契约加保留哨兵场景 id + `template/harness` 加空闲分支（FR-07 加法，使注入的桩在 serve 下空闲不关服）。
- **不做（范围外）**：
  - 集群多后端挂起（FR-18）、持久并起 bot / 人机混场（FR-19）。
  - 世界 / 数据**跨次启动持久化**（每次 serve 起全新运行目录，仅保留下载缓存）。
  - 交互式服务端控制台 **stdin 转发**（只读流日志到控制台；输入控制台命令为后续增强）。
  - 在线模式 / 正版鉴权——维持 `online-mode=false`（同 E2E）。

## 3. 设计（怎么做）

新模式总纲见 [ADR-0011](../adr/0011-persistent-serve-mode.md)，此处只列模块改动，不重复决策正文。

- **桩空闲（保留哨兵场景）**：契约加常量 `McTestkitContract.SERVE_SCENARIO_ID = "__mc_testkit_serve__"`；serve 起后端时经 `SCENARIO` env 下发它。`template/harness` 加法：`ScenarioName` 加 `SERVE("__mc_testkit_serve__")`（字面量对齐契约，不 import 插件包）、`onEnable`/`bootstrapScenario` 认出即**空闲**（不驱动、不挂超时、不关服）、`onPlayerJoin` 对 SERVE 直接 return（不动真人玩家背包）。老桩遇未知哨兵 `onEnable` 抛错被禁用、服务端照常起（robust）。
- **DSL**（`dsl/Specs.kt` + `dsl/McTestkitExtension.kt`）：新增 `ServeSpec(name)`，字段 `backend: String?` / `via: String?`；`McTestkitExtension` 加 `serve(name) { }` 收集器 + `declaredServes` 只读快照。
- **配置期校验**（`topology/TopologyResolver` 或注册前）：serve 引用的 `backend` / `via` 存在；声明 `via` 时该代理须 `routesTo` 该 backend（路由一致）；serve `name` 唯一、且其任务 `<Key>` 不与 scenario / 其它 serve 撞名（避免任务名冲突）。失败抛**中文** `GradleException`。这些是**纯函数**，可穷举单测。
- **任务命名**（`contract/McTestkitTaskNames`）：`serve(name) = "serve" + key`；`stopServe(name) = "stop" + key + "Serve"`。沿用 `toTaskKey()` PascalCase 折叠（ADR-0006 约定）。
- **pid 路径**（`task/RunLayout`）：serve 后端 pid 复用 `provisionPidFile(runDir, backend.name)`（ServerLauncher 默认落 runDir）；代理 pid 复用 `proxyPidFile(proxy.name)`（resultsDir）。stop 任务据此按 pid 收尾。
- **任务注册 + serve 任务体**（`task/McTestkitTasks`，新增 `registerServeTasks`）：
  1. `prepareRunDirectory`（复用：清运行目录保留缓存、写 eula / 最小 `server.properties`、注入被测 + 依赖插件，含桩）。
  2. 若 `via`：写后端代理模式配置（BungeeCord 三件套 / Velocity 两件套）→ 后台起代理（复用 `startProxyBackground` 同款）→ `awaitPortOpen` 代理端口。
  3. 前台起后端（`ServerLauncher.launch`，env 下发 `SCENARIO=__mc_testkit_serve__`（桩空闲）+ `BACKEND_NAME`，**不**下发 `RESULT_FILE`）→ `awaitPortOpen` 后端端口 → 打印「✅ 已就绪，请用客户端连 `localhost:<连接端口>`」+ 日志路径；起线程把后端日志 `tail` 到 `project.logger`。
  4. 注册 **JVM shutdown hook**：收尾后端 + 代理（destroy → 超时强杀、删 pid）。
  5. **阻塞** `process.waitFor()`（后端进程，直到服务端退出 / 任务被中断）。
  6. `finally`：双保险收尾后端 + 代理（删 pid）、移除 shutdown hook、停日志 tail 线程。
- **stop 任务体**：`stopProcessByPidFile` 收尾 serve 后端 pid（+ 若 via 的代理 pid）——单 / 已停为安全 no-op（向后兼容现有收尾原语）。
- **不变量遵守**：不反依赖消费项目 / `template/`；serve 不判 PASS/FAIL（架构不变量 §3）；不写死绝对路径（`RunLayout` 推导）；中文分级日志。

## 4. 任务拆分

- [ ] ADR-0011 落地（已写草案，待审核通过）
- [ ] 契约：`McTestkitContract.SERVE_SCENARIO_ID = "__mc_testkit_serve__"` 哨兵常量 + 契约测试
- [ ] `template/harness` 空闲分支：`ScenarioName.SERVE` + `bootstrapScenario` / `onPlayerJoin` 空闲（不驱动 / 不关服 / 不动真人玩家）
- [ ] DSL：`ServeSpec` + `McTestkitExtension.serve(){}` + `declaredServes`
- [ ] 配置期校验：serve 引用存在性 / 路由一致 / 名唯一不撞 scenario（中文报错）+ 纯函数单测
- [ ] 任务命名：`McTestkitTaskNames.serve` / `stopServe` + 单测
- [ ] 任务注册 + serve 任务体（prepare →(via) 起代理 → 前台起后端 → await port → 打印 + 日志 tail → shutdown hook → 阻塞 → finally 收尾）
- [ ] `stop<Key>Serve` 任务（按 pid 收尾后端 + 代理）
- [ ] 集成测试（Gradle TestKit）：声明 serve 注册 `serve<Key>` / `stop<Key>Serve`、配置期校验中文报错、任务图无环 / 任务名稳定
- [ ] 文档同步：PRD 状态、ARCHITECTURE（serve 持久机制 + 桩空闲哨兵 + 依赖）、API.md（§3.1 五块 + serve DSL + §3.2 serve 任务名 + §3.3 保留哨兵场景 id `__mc_testkit_serve__`）、template/README（serve 空闲场景）、CHANGELOG、OPERATIONS/README（serve 用法 + Ctrl+C/--no-daemon 行为提示）
- [ ] 真机 / 手动验收（见 §5）

## 5. 验收标准

- **[自动]** 应用插件声明 `serve("dev") { backend="s1" }` → 注册 `serveDev` + `stopDevServe`；TestKit 验证任务存在、任务图无环、任务名稳定。
- **[自动]** 配置期校验：serve 引用不存在的 backend/via、`via` 不路由到该 backend、serve 名与 scenario/其它 serve 撞 → **中文** `GradleException`（TestKit 断言报错文案）。
- **[自动]** 单元：`ServeSpec` 解析 / 校验纯函数、`serve` / `stopServe` 任务名派生穷举。
- **[自动·代验]** serve 起后端 / 代理后端口可 TCP 连接（就绪门）；`stopProcessByPidFile` 按 pid 收尾（收尾原语已有测试覆盖）。
- **[自动]** 桩空闲哨兵：`McTestkitContract.SERVE_SCENARIO_ID == "__mc_testkit_serve__"`（契约常量稳定）；`template/harness` 独立编译通过且 `ScenarioName.SERVE` 存在并映射该 id。
- **[手动 / 真机，需用户确认]** serve 起服后**桩空闲、服务端不自停、持续挂起**；真人 MC 客户端连入——**直连**（连后端端口）与**经代理**（连代理端口）各一次——能进服并看到被测插件生效；分别用 **Ctrl+C** 停 serve、以及单独 `stop<Key>Serve` 停，二者各自确认后端（+ 代理）**进程全灭、端口释放、无残留**（跨平台尽量覆盖 Windows + Linux）。**单元 / 集成全绿不替代此项**。

## 6. 风险 / 待定

- **DSL 契约 4 → 5 顶层块**（加法、minor）：需用户知悉、并更新 API.md「四块」表述。已在 ADR-0011 记录。
- **Ctrl+C 收尾可靠性**：Gradle（尤其 daemon / Windows）下 JVM shutdown hook 是否稳定触发——配 `stop<Key>Serve` 兜底，真机验收确认。建议文档提示 `--no-daemon` 跑 serve 以利 Ctrl+C 直达。
- **前台阻塞占住 Gradle 调用**：设计如此（serve 就是要挂住）；OPERATIONS/README 给使用说明（实现期补）。
- serve 经代理无 bot、无需固定协议版本；真人客户端版本须与后端 MC 版本匹配（文档提示）。
