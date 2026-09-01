# 功能规格：FR-03 声明式拓扑 DSL（内存拓扑模型 + 解析 + 端口推导 + 配置期校验）

> 状态：已交付@v0.1.0　·　关联 PRD：FR-03

## 1. 背景与目标

FR-01 已冻结 `mcTestkit { }` 的四个顶层块形态（`backend` / `proxy` / `scenario` / `dependencies`），但扩展只「忠实记录声明」，不含任何行为。FR-03 在这些冻结 spec 之上，把消费方的声明**解析成一个内存拓扑模型**：补齐每个节点的实际端口（显式优先、否则按端口基数推导），并在配置期对声明做一致性校验（重名、路由目标、端口冲突、场景引用等），缺陷以**中文** Gradle 异常报出。属第一期 P1。

本 FR **只产出模型 / 校验库**：不注册任务（FR-04）、不做下载/运行（FR-02）、不固化环境契约（FR-05）。解析器做成纯函数，便于穷举测试。

## 2. 需求（要什么）

- 范围内：
  - **拓扑模型**（`top.wcpe.mc.testkit.model`）：`ResolvedBackend(name, platform, version, port)`、`ResolvedProxy(name, platform, port, routes)`、`Topology(backends, proxies)`。端口已是确定的 `Int`（解析后不再有 null）。
  - **解析器**：读 `McTestkitExtension` 的 `declared*` → 构建 `Topology`。纯函数（输入 specs，输出 `Topology` 或抛校验异常），不读环境 / 文件系统 / 全局状态。
  - **端口推导**：节点显式 `port` 优先；为 null 时按「端口基数 + 序号」推导。基数常量定义在本 FR 自己的文件（`model/` 内），**不改 `contract/` 冻结常量文件**。
  - **配置期校验**（抛 `GradleException`，消息中文，说明缺什么 / 怎么补）：节点名非空且唯一、后端名与代理名不撞、`proxy.routesTo` 目标后端存在、代理至少路由到一个后端、解析后端口全局不冲突、`scenario.backend` / `scenario.via` 引用存在。
- 不做（范围外）：
  - 任务注册 / 任务图（FR-04）、下载/运行（FR-02）、`server.properties` / `paper-global.yml` / BungeeCord 三件套与数据源 / Redis 注入校验（FR-05）、机器人与判定（FR-06）。
  - 任何 Bukkit/Sponge 平台分支或空壳（不在项目计划内，见 ADR-0013，scope-discipline）。
  - 不为「未来多代理 / 多路由形态」预留抽象、配置项、字段（YAGNI）。

## 3. 设计（怎么做）

- 包 `top.wcpe.mc.testkit.model`，新增三个文件：
  - `Topology.kt`：三个不可变 data class（模型）。
  - `TopologyDefaults.kt`：端口基数常量（后端基数 / 代理基数）。值取自首个接入项目实证（后端 25565、代理 25577）。**这是本 FR 的常量，不进 `contract/`。**
  - `TopologyResolver.kt`：纯函数 `resolve(...)` —— 接收 `declaredBackends` / `declaredProxies` / `declaredScenarios`（或整个扩展），先校验、再推导端口、产出 `Topology`；任何不一致抛 `GradleException`（中文）。
- **端口推导规则**：对每类节点（后端、代理各一套），按**声明顺序**遍历——显式 `port` 直接采用；为 null 的节点取「基数 + 在该类节点中的 0 基序号」。即第 0 个待推导后端 → 25565，第 1 个 → 25566…代理同理以代理基数推导。显式与推导混用时，显式值原样保留，推导值可能与显式值撞——由「端口全局不冲突」校验兜底报错（提示消费方显式指定）。
- **校验**：纯函数内顺序检查，违例即抛中文 `GradleException`。平台 P1 范围由**冻结枚举类型**（`BackendPlatform`/`ProxyPlatform` 仅含 P1 项）在编译期保证，运行期不再写不可达的范围校验（避免空壳 / 死代码）。
- 错误约定遵循 `docs/API.md` §2（配置期 → Gradle 异常 + 中文）。不改 `McTestkitExtension` 公共签名、不改 `contract/`、不动 `build.gradle.kts`（无需新依赖；`GradleException` 来自 `kotlin-dsl` 已带的 Gradle API）。

## 4. 任务拆分

- [x] 写规格 + PRD §4 FR-03 行状态改「开发中」。
- [x] 测试先行：端口推导（多后端基数+序号、显式优先、显式与推导冲突报错）、各类校验失败的中文错误、happy path 构出正确 `Topology`。
- [x] 实现 `model/`：模型 + 端口基数常量 + 纯函数解析 / 校验。
- [x] 文档同步：ARCHITECTURE §2 `model` 条核对、CHANGELOG 未发布段追加一行。

## 5. 验收标准

- `./gradlew build` 全绿；新增 `model/` 测试红→绿覆盖：端口推导各路径、每类校验失败给出明确中文错误、happy path 拓扑正确（含单后端、代理+N 后端两种形态）。
- 解析器为纯函数：相同 specs 多次解析结果一致，不依赖环境 / 文件系统。
- 配置期校验消息为中文，明确指出「缺什么 / 撞哪个 / 怎么补」（对齐 API §2 与 testing-and-quality 高风险区「任务图与配置期校验」）。
- 本 FR 无实机维度（纯内存模型与纯函数，无真实服务端 / 代理 / 机器人）。

## 6. 风险 / 待定

- 端口基数取值（后端 25565 / 代理 25577）沿用首个接入项目惯例；若 FR-04 编排需要不同基数，再在 `model/` 内调整，不影响对外 DSL 契约。
- 「代理必须至少路由到一个后端」是本 FR 新增的配置期约束（无路由的代理无意义、几乎必是误配）；若后续出现「先声明代理后补路由」的合理用法，再放宽并记录。
- DSL spec 字段本 FR 未改动（沿用 FR-01 冻结形态）；如解析中发现需要微调 spec 字段，将在 `dsl/` 包内进行并在汇报中提出。
