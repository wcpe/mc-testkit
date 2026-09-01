# 功能规格：持久开启·集群拓扑手测（serve 集群）

> 状态：已交付@v0.4.0　·　关联 PRD：FR-18　·　决策：[ADR-0011](../adr/0011-persistent-serve-mode.md)　·　依赖：FR-17（serve 内核）

## 1. 背景与目标

FR-17 的 serve 只起**单后端**（+ 可选经代理）。集群一致性 / 转服 / 崩溃接管类问题常需**人眼**在真实「N 后端 + 代理」里 `/server` 切着看。FR-18 让 `serve { }` 复用既有**集群编排**（FR-10），把多后端 + 代理整套**挂起**供真人经代理手测。P3 / 第三期，serve 模式的集群增量（[ADR-0011](../adr/0011-persistent-serve-mode.md)，不另写 ADR）。

## 2. 需求（要什么）

- `serve("name") { backends("s1","s2"); via = "wf" }`——非空 `backends(...)` 即**集群 serve**（与单后端 `backend =` 互斥，须配 `via`），语义与集群场景 `scenario { backends(...) }` 对齐（FR-10）。
- `serve<Key>` 集群分支：N 后端**全部后台**起（各自运行目录 + 代理模式配置 + 哨兵场景使桩空闲）+ 后台起**集群代理**（单 listener + N 具名 server），就绪后打印连接信息（连代理端口、可 `/server <名>` 切），**前台阻塞挂住**到手动停。
- `stop<Key>Serve` 集群分支：按 pid 收尾**全部**后端 + 代理。
- **范围内**：复用 `startBackendBackground` / `startClusterProxyBackground` / `awaitPortOpen`（FR-10 既有）+ FR-17 的 serve 挂起 / 三重收尾。
- **不做（范围外）**：起 bot（FR-19）；世界跨次持久化；压测形态（serve 非压测）。

## 3. 设计（怎么做）

- **DSL**（`dsl/Specs.kt`）：`ServeSpec` 加 `backends(vararg)` + `backendRefs`（仿 `ScenarioSpec`）。集群 serve = `backendRefs` 非空。
- **配置期校验**（`TopologyResolver.validateServeRefs` 扩展）：集群 serve 须有 `via`、`via` 路由覆盖全部 backends、各 backend 存在、不与单后端 `backend =` 并用、serve 名/key 唯一（纯函数、中文报错、可穷举单测）。
- **任务体**（`task/McTestkitTasks`）：`registerServeTasks` 按 `backendRefs` 空否分流——空走 FR-17 单后端 `serveForeground`；非空走新增 `serveClusterForeground`：① 每后端 `clusterBackendRunDir` + `prepareRunDirectory` + 代理模式配置（BungeeCord/Velocity）+ `startBackendBackground`（env 含哨兵 `SCENARIO=__mc_testkit_serve__` 使桩空闲、`BACKEND_NAME`，**不**下发 `RESULT_FILE`）；② `startClusterProxyBackground`；③ `awaitPortOpen` 全部后端 + 代理；④ 打印连接信息（代理端口 + `/server` 目标名）+ 代理日志可选 tail；⑤ 注册 JVM shutdown hook 收尾全部后端 + 代理；⑥ **阻塞** `proxyProcess.waitFor()`（代理是真人入口；某后端宕仍挂着便于看 fallback）；⑦ `finally` 双保险收尾全部后端 + 代理。
- **stop 任务**：集群分支按 pid 收尾全部 `clusterBackendPidFile` + `proxyPidFile`。
- 复用 FR-17 的哨兵桩空闲机制（每后端桩都收哨兵 → 空闲）；不判定（不绕过结果文件自判）。

## 4. 任务拆分

- [x] DSL：`ServeSpec.backends(...)` + `backendRefs`
- [x] 校验：集群 serve（须 via / 路由覆盖 / 互斥单后端 / 名唯一）扩 `validateServeRefs` + 单测
- [x] 任务：`registerServeTasks` 分流 + `serveClusterForeground` + stop 集群分支
- [x] 集成测试（TestKit）：集群 serve 注册 `serve<Key>` / `stop<Key>Serve`、配置期校验中文报错
- [x] 文档同步：PRD 状态、ARCHITECTURE（serve 机制补集群）、API.md（serve 块 backends + 任务表注）、CHANGELOG
- [x] 真机 / 手动验收（见 §5）

## 5. 验收标准

- **[自动]** 声明集群 `serve("dev"){ backends("s1","s2"); via="wf" }` → 注册 `serveDev` / `stopDevServe`；TestKit 验证任务存在、任务图无环、任务名稳定。
- **[自动]** 配置期校验：集群 serve 缺 via / via 不覆盖全部后端 / 与 `backend =` 并用 → 中文 `GradleException`（TestKit 断言）。
- **[自动·代验]** 集群 serve 起 N 后端 + 代理后各端口可 TCP 连接（就绪门）；`stop<Key>Serve` 按 pid 收尾全部。
- **[本机真机·自动]** 真实起 2 后端 + 代理整套挂起、各桩识别哨兵空闲不自停、挂住 ≥30s、`stop` 后全部后端 + 代理进程全灭、端口释放无残留（同 FR-17 自动真机维度，本机可代验）。
- **[手动 / 真机，需用户确认]** 真人 MC 客户端连代理端口入服、`/server` 在 N 后端间切换手测看到插件生效；停后无残留。**单元 / 集成全绿不替代此项**。

## 6. 风险 / 待定

- 阻塞点选 `proxyProcess.waitFor()`：某后端宕机时 serve 仍挂着（便于人眼看崩溃接管 fallback），代理宕则 serve 结束收尾——符合手测预期。
- Velocity 集群 serve 复用 FR-10 既有 Velocity 集群路径（modern forwarding）；无新增。
