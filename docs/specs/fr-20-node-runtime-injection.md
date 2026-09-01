# 功能规格：节点运行时注入（代理插件、每节点环境变量与模板目录）

> 状态：已交付@v0.5.0　·　关联 PRD：FR-20　·　相关决策：[ADR-0004](../adr/0004-orchestration-model.md)、[ADR-0006](../adr/0006-public-contract-conventions.md)、[ADR-0011](../adr/0011-persistent-serve-mode.md)

## 1. 背景与目标

v0.4.2 已能经 `dependencies { }` 向后端注入被测 / 依赖插件，并能经全局 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR` 给后端铺模板；但节点声明本身不能携带运行期环境，代理也没有专属插件注入与模板入口。下游 Beacon 需要在真实 BungeeCord 代理中加载其代理侧插件，并让不同 backend / proxy 收到各自配置，当前契约无法表达。

FR-20 在不改变既有 `dependencies { }` 语义的前提下，为 backend / proxy 增加**每节点环境变量与模板目录**，并只为 proxy 增加**代理节点专属插件注入**。所有 v0.4.2 已存在的自动化 E2E 与 serve 启动路径必须接入同一套准备和环境合并规则。该能力阻断下游真实消费者接入，优先级为 P1。

## 2. 需求（要什么）

### 2.1 范围内

- 保持 `dependencies { }` **仅注入后端**的现有语义：
  - `pluginUnderTest` 与 `plugin(...)` 继续进入目标后端的 `plugins/`。
  - 不把 `dependencies { }` 中任何 jar 注入代理。
  - 不改变现有声明、解析顺序、目标文件名与缺失报错语义。
- `BackendSpec` 加法新增：
  - `env(name, value)`：向**该后端节点进程**注入一个字面量环境变量。
  - `templateDirectory(envOrPath)`：声明该后端节点的模板目录。
- `ProxySpec` 加法新增：
  - `plugin(envOrPath)`：向**该代理节点**的 `plugins/` 注入一个代理插件 jar；可重复调用以声明多个插件。
  - `env(name, value)`：向**该代理节点进程**注入一个字面量环境变量。
  - `templateDirectory(envOrPath)`：声明该代理节点的模板目录。
- 新增 `BackendSpec` / `ProxySpec` 的 `envOrPath` 统一解析规则：
  1. 先把声明值当环境变量名读取；读到**非空值**时，以该值作为路径，不再回退声明值本身。
  2. 环境变量未设置或值为空时，把声明值本身作为路径。
  3. 绝对路径原样使用；相对路径相对应用 mc-testkit 插件的 `Project.projectDir` 解析。
  4. `plugin(...)` 最终必须解析到存在的普通 `.jar` 文件；`templateDirectory(...)` 最终必须解析到存在的目录，否则执行期在启动任何相关节点前报错。
- 后端模板兼容规则：
  - 声明了 `BackendSpec.templateDirectory(...)` 时，仅使用该节点模板，并按上述新增 `envOrPath` 规则解析。
  - 未声明时，继续回退既有 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR`，保证旧 DSL / 旧 CI 环境不变。
  - 旧全局环境变量不改成新增 `envOrPath` 语义：其非空值仍直接交给 `File(raw)`；绝对路径原样使用，相对路径按 JVM / Gradle 当前工作目录解析，不改按应用子工程 `Project.projectDir`。
  - 不同时叠加全局模板与节点模板，避免同一文件来源顺序含糊。
- 每次代理启动必须按 §3.3 的确定顺序：**清理可重建运行目录 → 铺节点模板 → 写框架权威配置 → 注入代理插件 → 启动代理**。
- backend / proxy 环境必须按 §3.4 合并；消费方不得经节点 `env(...)` 声明 `MC_TESTKIT_E2E_` 保留前缀，框架变量始终优先。
- v0.4.2 实际存在的全部启动路径均须接线，详见 §3.5。

### 2.2 DSL 契约

```kotlin
mcTestkit {
    backend("lobby") {
        platform = paper
        version = "1.20.1"
        env("BEACON_NODE_NAME", "lobby")
        templateDirectory("MC_TESTKIT_E2E_LOBBY_TEMPLATE_DIR")
    }

    proxy("bc") {
        platform = bungeecord
        routesTo("lobby")
        plugin("MC_TESTKIT_E2E_BEACON_PROXY_JAR")
        env("BEACON_NODE_NAME", "bc")
        templateDirectory("MC_TESTKIT_E2E_BUNGEECORD_TEMPLATE_DIR")
    }

    dependencies {
        // 语义不变：仍只注入后端，不注入 bc 代理。
        pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        plugin("MC_TESTKIT_E2E_BACKEND_DEPENDENCY_JAR")
    }
}
```

契约细则：

- `env(name, value)` 的 `value` 是字面量，不做环境变量或路径二次解析。
- 同一节点对同一个环境变量名重复调用 `env` 时，后声明值覆盖先声明值；不同节点互不共享。
- 环境变量名不能为空，且不得以 `MC_TESTKIT_E2E_` 开头。为保证 Windows 环境下也无法绕过，前缀判断不区分大小写。
- 上述保留前缀限制只约束 `env(name, value)` 的 **name**；`plugin("MC_TESTKIT_E2E_..._JAR")` / `templateDirectory("MC_TESTKIT_E2E_..._DIR")` 把字符串用作 `envOrPath` 间接定位资源，不属于覆盖框架环境变量，允许使用该前缀。
- `ProxySpec.plugin(...)` 按声明顺序解析并复制，目标名使用源 jar 文件名；它只作用于声明所在的代理。若模板已有同名 jar，显式 `plugin(...)` 注入覆盖模板文件；多个显式声明解析为同一目标文件名时，配置视为含糊并报错，不静默覆盖。
- 本 FR 不新增顶层 DSL 块、不改任务名、不改场景 / serve / bot 语义，属于 minor 版本的加法契约。

### 2.3 不做（范围外）

- 不新增或移植本地 master 上的 `provide` 能力、`provide { }` DSL、相关任务、模型或兼容层；v0.5.0 本 FR 验收必须确认公共契约中**不存在 provide**。
- 不给 `BackendSpec` 增加节点专属 `plugin(...)`；后端插件仍统一经 `dependencies { }` 注入。
- 不让代理消费 `dependencies { }`，不把代理插件复制到后端。
- 不给 bot 增加节点环境变量；`BotSpec.env(...)` 保持现状。
- 不改变平台范围、下载来源、任务命名、结果文件、控制协议、serve 生命周期或进程收尾模型。
- 不把模板做成跨次可写持久目录；模板始终是每次运行只读铺设源，运行目录仍可清理重建。

## 3. 设计（怎么做）

本 FR 是对现有 DSL / topology / task 编排的加法扩展，沿用 ADR-0004 的进程模型、ADR-0006 的保留前缀与 ADR-0011 的 serve 生命周期；不产生新的架构裁决，暂不新增 ADR。

### 3.1 声明与解析模型

- `dsl/Specs.kt`：
  - `BackendSpec` 保存有序节点环境映射与可选模板声明。
  - `ProxySpec` 保存有序代理插件声明、节点环境映射与可选模板声明。
- `topology/Topology.kt` / `TopologyResolver`：把上述字段传入 `ResolvedBackend` / `ResolvedProxy`，使任务层只消费已校验的节点声明，不回读可变 DSL 对象。
- 配置期校验：
  - `env` 名不能为空、不得含平台不接受的非法形式、不得命中保留前缀。
  - 现有节点名、路由、端口、scenario / serve 引用校验保持不变。
- 文件系统与环境变量读取仍放执行期；`help` / `tasks` 不因本机未提供 jar / 模板而失败。

### 3.2 资源解析与启动前预检

新增一个供 backend / proxy 节点声明共用的 `envOrPath` 纯函数解析器，并保持现有 `dependencies { }` 行为不变。旧全局 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR` 不进入该解析器，其相对路径继续按 v0.4.2 的 `File(raw)` / JVM·Gradle 当前工作目录语义解析。对一次任务实际会启动的全部节点，先完成资源预检，再做目录清理或启动进程：

- 单后端任务：预检目标 backend 模板。
- 经代理任务：预检目标 backend 模板、目标 proxy 模板与全部 proxy 插件。
- 集群 / 压测 / 集群 serve：预检全部参与 backend 的模板，以及参与 proxy 的模板 / 插件。
- 任一资源缺失、类型错误或插件目标文件名冲突时，整项任务在启动第一个进程前失败，避免半套拓扑已启动。

错误必须使用中文并包含：节点类型与节点名、原始声明、实际解析路径、期望类型（jar / 目录）及修复提示。若环境变量已设置但指向错误路径，错误应明确指出采用了该环境变量值，不得静默回退声明字面路径。

### 3.3 运行目录与权威写入顺序

#### 后端节点

每个参与任务的后端按以下顺序准备：

1. 清理本轮可重建内容，沿用 `cleanRunDirPreservingRuntimeCaches` 保留 `libraries` / `cache` / `assets` / `versions`。
2. 铺模板：优先节点 `templateDirectory`（相对应用项目 `Project.projectDir`），否则使用既有 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR`（相对 JVM / Gradle 当前工作目录，保持 v0.4.2）；继续排除 `world/**`、`world_nether/**`、`world_the_end/**`、`logs/**`。
3. 写框架权威文件：`eula.txt`、端口 / 离线模式 / 安全档案 / 默认 peaceful 等 `server.properties`；经代理时再写 BungeeCord 或 Velocity 后端转发配置。模板中的同名权威键不得覆盖框架计算值。
4. 按原语义把 `dependencies { }` 解析出的 jar 注入该后端 `plugins/`。
5. 以 §3.4 的合并环境启动后端。

#### 代理节点

每次实际启动代理前按以下顺序准备：

1. 清理整个 `run-proxy` 可重建目录后重新创建；jar 下载缓存仍位于 Gradle 用户缓存，不受影响。若目录无法清理，中文报错并禁止启动。
2. 若声明 `ProxySpec.templateDirectory`，把模板铺入代理运行目录；排除 `logs/**` 与 pid 等明显运行产物，保留插件配置、模块配置及其它消费方基线文件。
3. 写框架权威代理配置：
   - Waterfall / BungeeCord：权威写 `config.yml`。
   - Velocity：权威写 `velocity.toml` 与 `forwarding.secret`。
   模板可提供其它配置，但不得覆盖框架计算的监听端口、路由与 forwarding 信息。
4. 完成平台运行前准备（如 Waterfall modules），再把该 `ProxySpec.plugin(...)` 的 jar 注入 `plugins/`；显式插件覆盖模板中同名 jar。
5. 以 §3.4 的合并环境启动代理。

该顺序必须由所有代理启动路径复用同一准备入口，禁止在各任务分支复制一套近似逻辑。

### 3.4 环境合并规则

`ServerLauncher` 仍继承宿主进程环境；传给某个节点的追加环境按以下优先级由低到高合并：

1. 宿主进程继承环境。
2. 该节点 DSL `env(name, value)`。
3. 框架为本次启动计算的环境变量。

因此框架变量最终覆盖同名宿主值与消费方值。配置期已禁止 DSL 使用 `MC_TESTKIT_E2E_` 前缀，执行期仍须采用“框架环境最后合入”作为第二道防线。

- backend 节点环境只进入对应 backend JVM；现有 `SCENARIO`、`RESULT_FILE`、`BACKEND_NAME`、serve 哨兵等框架环境保持原语义并最终覆盖。
- proxy 节点环境只进入对应 proxy JVM；不转发给 backend、bot 或其它 proxy。
- 单节点、集群、压测与 serve 中，同一节点声明得到一致环境，不因启动函数不同而漂移。

### 3.5 v0.4.2 启动路径接线矩阵

实现不得只覆盖普通场景；以下 v0.4.2 实际路径全部纳入验收：

| 节点 | v0.4.2 启动入口 | 覆盖的任务形态 | FR-20 接线要求 |
|---|---|---|---|
| backend | `runBackendForeground` | 单后端直连、单后端经代理 | 节点模板 + 节点 env；`dependencies { }` 仍仅后端 |
| backend | `startBackendBackground` | 集群场景、压测场景 | 每个后端独立模板 + 独立 env |
| backend | `startServeBackend` | 单后端 serve（直连 / 经代理） | 节点模板 + 节点 env，框架 serve 哨兵优先 |
| backend | `startServeClusterBackend` | 集群 serve | 每个后端独立模板 + 独立 env，框架 serve 哨兵优先 |
| proxy | `startProxyBackground` | 单后端经代理场景、单后端经代理 serve | 每次清理 / 铺模板 / 写权威配置 / 注入该代理插件 / 合并 env |
| proxy | `startClusterProxyBackground` | 集群场景、集群 serve | 同上，集群路由配置保持权威 |
| proxy | `startStressProxyBackground` | Waterfall / BungeeCord 压测代理 | 同上，N-listener 配置保持权威 |

`ServerLauncher.launch` 继续作为唯一 JVM 子进程启动原语；任务层不得另开绕过环境合并与准备顺序的新启动器。

### 3.6 文档同步策略

`docs/ARCHITECTURE.md` 与 `docs/API.md` 声明的是**当前真貌 / 当前契约**。本次 Q0A 只建立开发规格，不提前把未实现能力写成现状；实现与验收完成后再按任务拆分同步：

- `API.md`：补 backend / proxy DSL 方法、`envOrPath`、保留前缀、环境优先级、旧全局模板回退。
- `ARCHITECTURE.md`：补节点资源解析、统一 backend / proxy 准备入口及全部启动路径接线。
- `CHANGELOG.md`：只在实现完成与发版同步阶段更新，本规格阶段不改。

## 4. 任务拆分

- [x] DSL / topology：为 `BackendSpec`、`ProxySpec` 与 resolved 模型增加本规格字段；配置期校验 env 名与保留前缀。
- [x] 测试先行：`envOrPath` 环境变量优先、空值回退路径、相对路径解析、错误路径 / 类型中文报错。
- [x] 启动前预检：一次任务涉及的全部节点资源在任何目录清理 / 进程启动前完成解析与冲突校验。
- [x] 后端准备：节点模板优先、旧 `SERVER_TEMPLATE_DIR` 回退、框架权威配置顺序、`dependencies { }` 后端专属语义不变。
- [x] 代理准备：每次清理 `run-proxy`、铺模板、写平台权威配置、准备平台模块、注入代理专属插件。
- [x] 环境合并：宿主 < 节点 env < 框架 env；backend / proxy 隔离，保留前缀双重防护。
- [x] 全路径接线：覆盖 §3.5 的 4 条 backend 与 3 条 proxy 启动入口，不新增旁路启动器。
- [x] 单元测试：DSL 记录 / 覆盖、节点隔离、保留前缀、资源解析、模板与权威文件顺序、代理插件注入与冲突。
- [x] TestKit / 回归测试：旧 DSL 任务注册与执行计划兼容；多工程中插件应用于子工程时，旧全局模板 env 的相对路径仍按显式可控 Gradle 工作目录解析；直连 / 经代理 / 集群 / 压测 / serve 路径均使用节点声明；公共 DSL / 任务中不存在 `provide`。
- [x] 真实消费者验收：下游 Beacon 以发布候选 v0.5.0 接入真实 BungeeCord，加载代理专属插件并验证 backend / proxy 节点环境与模板生效；证据见 §5。
- [x] 实现完成后文档同步：API、ARCHITECTURE、PRD 交付状态、CHANGELOG；本规格阶段不提前更新当前真貌文档或 CHANGELOG。

## 5. 验收标准

- **[自动] DSL 契约**：`BackendSpec.env/templateDirectory` 与 `ProxySpec.plugin/env/templateDirectory` 可声明并进入 resolved 模型；旧 DSL 不加任何新字段仍可编译、注册原任务且既有测试全绿。
- **[自动] dependencies 兼容**：`dependencies { }` 的待测 / 依赖插件只出现在 backend `plugins/`，不出现在 proxy；既有后端目标命名与缺失错误不回归。
- **[自动] envOrPath / 旧路径兼容**：新增节点声明的环境变量非空值优先；未设 / 空值回退声明路径；环境变量已设但目标无效时不回退；新增声明的相对路径按应用项目 `Project.projectDir`，旧全局模板 env 的相对值仍按 JVM / Gradle 当前工作目录；缺失 / 类型错误在启动任何相关进程前给出含节点与路径的中文错误。
- **[自动] 环境优先级**：backend / proxy 各只收到本节点 env；框架 env 最后合入并覆盖；任意大小写形式的 `MC_TESTKIT_E2E_` 节点 env 声明均被拒绝。
- **[自动] 后端模板**：节点模板优先于旧全局模板回退；模板先铺、框架权威配置后写；单前台、集群 / 压测后台、单 serve、集群 serve 四条后端路径结果一致。
- **[自动] 代理准备**：三条代理路径每次均清理可重建目录，再铺模板、写权威配置、注入该节点插件后启动；模板同名配置不能改写框架端口 / 路由 / forwarding；代理 A 的插件 / env 不泄漏到代理 B。
- **[自动] 全路径与收尾回归**：直连、单代理、集群、压测、单 serve、集群 serve 的任务图无环、任务名不变，失败 / 中断仍沿用既有 pid 收尾且端口不残留。
- **[自动] 无 provide**：源码公共 DSL、生成任务、API 文档与测试夹具中不新增 `provide` 能力；本 FR 不从其它分支移植 provide 代码。
- [x] **[真实消费 / BungeeCord，已确认]**：Beacon 使用本地 `includeBuild` 加载 v0.5.0 发布候选，原生 BungeeCord 26.1 启动并加载 `BeaconAgentProxy` 与 `BeaconE2EProxy`。`directory` 验证 Paper/backend 与 Bungee/proxy 双 identity 均 active+online、动态 backend 注入、静态 backend 路由保留、`IMPLEMENTATION=BungeeCord`，控制面下线 12 秒后目录仍保留（PASS，134.789 秒）；`override` 两轮独立 `servePaper` 的 inert/filetree/ordering/fail-static 全绿，fail-static 连续 35 秒覆盖 30 秒长轮询（PASS，224.803 秒）。

## 6. 风险 / 待定

- **无 ADR 冲突**：本 FR 不改变 ADR-0004 的进程模型，只把节点准备与环境传入补齐；不改变 ADR-0006 的前缀，反而明确禁止消费方覆盖；serve 只复用 ADR-0011 生命周期。属于现有块内的加法 DSL，无需新 ADR。
- **模板与权威配置冲突**：必须坚持“模板先、框架权威配置后”，否则消费方模板可能悄悄改掉端口 / 路由 / forwarding，造成假阴性或串服。
- **跨平台环境变量大小写**：Windows 环境名大小写不敏感，保留前缀校验必须不区分大小写；合并测试需覆盖该差异。
- **代理目录清理**：代理 jar 缓存在运行目录外，`run-proxy` 可整目录重建；若未来出现需保留的代理运行库，应另行明确白名单，不在本 FR 预留抽象。
- **下游依赖**：Beacon 接入被本 FR 阻断，因此真实 BungeeCord 消费验收是发布 v0.5.0 的门禁，不能仅凭框架自测标记交付。
