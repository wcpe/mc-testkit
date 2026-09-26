# 接口契约：mc-testkit

> 对外接口的单一真源。始终原地更新到当前契约。
>
> **现状**：FR-01 已落地，**对外契约的形态与命名已冻结**（插件 id / `mcTestkit { }` DSL 形态 / 任务命名约定 / 环境变量前缀 / 控制协议 / 结果文件），见 ADR-0006。标注「随实现补全」者为 FR-02+ 会扩充的**细节**（如 env 全集、下载/运行细节），但其前缀 / 命名风格不再变。破坏性变更按 §1 升 major。

## 1. 通用约定

- 接口形态有四类：① Gradle DSL `mcTestkit { }`（Kotlin）；② 自动生成的 Gradle 任务；③ 环境变量约定；④ 机器人↔桩控制协议（聊天/插件消息）。
- 版本：随插件 SemVer；破坏性 DSL/任务名/env 前缀/协议/结果键变更按 SemVer 升 major 并在 CHANGELOG 写迁移。
- 编码：配置文件 UTF-8；YAML 字段遵循 `.claude/rules/config-files.md`（kebab-case + 中文注释）。
- 插件 id：`top.wcpe.mc-testkit`；DSL 扩展名：`mcTestkit`；实现类：`top.wcpe.mc.testkit.McTestkitPlugin`。

## 2. 错误约定

- **配置期**（Gradle 配置/校验）：必填项缺失、节点环境变量名非法、路由目标后端不存在 → 抛 Gradle 异常，**中文**说明缺什么、怎么补。
- **启动前资源预检**：任务涉及的依赖 jar、节点模板和代理插件任一缺失、类型错误或目标文件名冲突时，在清理首个运行目录或启动首个节点前中文失败。
- **执行期**（场景运行）：场景失败 → verify 任务抛错使构建失败（CI 非零退出）；失败原因写入结果文件 `message` 字段与对应日志（服务端 `logs/`、机器人 `*-bot-*.log`、代理 `proxy.log`）。

## 3. 接口 / 方法

### 3.1 DSL：`mcTestkit { }`（形态已冻结）

声明测试拓扑、场景与依赖注入。FR-01 冻结四个顶层块的形态；行为（拓扑解析、端口推导、校验、任务编排）由 FR-03/04 实现。平台便捷量（`paper`/`folia`/`spigot`/`velocity`/`waterfall`/`bungeecord`）让消费方无需 import 枚举。

```kotlin
mcTestkit {
    // 后端节点：platform 默认 paper；version 默认 1.20.1（也支持 26.2 等新版号）；port 省略则由拓扑解析按端口基数推导
    backend("s1") {
        platform = paper          // paper | folia | spigot（ADR-0003/0013）
        version = "1.20.1"        // 或 "26.2"（MC 新版号方案，无 1. 前缀）
        port = 25565              // Int?，可省
        env("MYPLUGIN_NODE", "s1")
        templateDirectory("MC_TESTKIT_E2E_S1_TEMPLATE_DIR")
        mavenServer("io.papermc.paper:paper:1.12.2") // 可省；服务端 jar 来自 Maven 坐标（优先级：*_JAR > 本坐标 > 内置下载）
    }
    // 代理节点与路由
    proxy("wf") {
        platform = waterfall      // velocity | waterfall | bungeecord
        version = "3.1.1"         // 可省；Velocity 使用自身版本号
        javaVersion = 17          // 可省；按 MC_TESTKIT_JAVA_HOME_17 选择运行时
        port = 25577              // Int?，可省
        routesTo("s1")            // 转发到的后端名（路由目标存在性配置期校验）
        plugin("MC_TESTKIT_E2E_PROXY_PLUGIN_JAR")
        jvmArg("-Dexample.flag=true")
        javaAgent("SERVERPROBE_AGENT_JAR")
        env("MYPLUGIN_PROXY_NODE", "wf")
        templateDirectory("MC_TESTKIT_E2E_PROXY_TEMPLATE_DIR")
    }
    // 端到端场景
    scenario("smoke")             // 无机器人：仅 prepare + verify
    scenario("buySuccess") {
        backend = "s1"            // 运行于哪个后端（null=默认/单后端）
        via = "wf"               // 经哪个代理（null=直连）
        bot {                     // 有机器人驱动
            username = "BuyBot"
            action = "buy-success" // 控制动作 / 场景 id（与桩、机器人侧一致）
            env("MYPLUGIN_SHOP_TITLE", "E2E Shop") // 业务 env 原样透传给机器人（消费方自定名，不进框架契约）
        }
    }
    // 集群场景（FR-10，ADR-0008）：在 scenario 块内加 backends(...) 即多后端，bot 经代理 /server 切换
    scenario("cross-server") {
        backends("s1", "s2")      // 多后端引用（有序）；非空即集群场景，与单后端 backend= 互斥
        via = "wf"               // 集群必经代理（bot 经它在后端间 /server 切换）
        bot { username = "Switcher"; action = "cross-server" }
    }
    // 压测场景（FR-11，ADR-0008）：scenario 块内加 stress {} 即压测，每服 botsPerServer 个 bot 钉服持续施压
    scenario("continuous-stress") {
        backends("s1", "s2")      // N 服（钉服压测；与单后端 backend= 互斥）
        via = "wf"               // 可选：经代理 N-listener 钉服；省略则 bot 直连后端端口
        stress { botsPerServer = 100; durationSeconds = 300 } // botsPerServer/durationSeconds 必填 >0
        bot { username = "Stress"; action = "continuous-stress" }
    }
    // 单场景多 bot（FR-16，ADR-0009）·异质双角色（单后端直连）：多个具名 bot("角色")，各自 username/action/env
    scenario("gui-edit") {
        backend = "s1"
        bot("admin") { username = "Admin"; action = "gui-admin" }   // OP，经 GUI 菜单编辑 target
        bot("target") { username = "Target"; action = "gui-target" } // 被编辑对象
    }
    // 单场景多 bot（FR-16）·同质批量（集群 N 个切服 bot）：count 复制 N 份，各唯一 username、经 BOT_INDEX 区分
    scenario("g16") {
        backends("s1", "s2"); via = "wf"
        bot { username = "P"; action = "cross-server"; count = 8 }   // 复制 8 份：username P1..P8、BOT_INDEX 1..8，各自经代理 /server 切
    }
    // 持久手测（serve，FR-17，ADR-0011）·**新增第 5 个顶层块**：起拓扑挂住供真人客户端手测，不跑 bot、不判定
    serve("dev") {
        backend = "s1"            // 起哪个后端（null=默认/单后端）
        via = "wf"               // 经哪个代理（null=直连）；设了须 routesTo 该后端
    }
    // 集群 serve（FR-18）：声明 backends(...) 即多后端整套挂起，真人经代理 /server 切服手测
    serve("cluster") {
        backends("s1", "s2")      // 多后端（须配 via 代理，与单后端 backend 互斥）
        via = "wf"
    }
    // 注入到后端运行目录的待测/依赖插件 jar；不注入代理
    dependencies {
        pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" // 环境变量名或路径，运行期解析
        plugin("SampleLib")                                     // 环境变量名或路径，运行期解析
        mavenPlugin("com.example:foo-plugin:1.2.0")             // Maven 坐标，按 Gradle 原生依赖解析拉取
    }
    // 多版本矩阵（versionMatrix，第 6 个顶层块）：声明 N 个 MC 版本，自动展开 backend + scenario
    // 并注册串行聚合任务 e2eMatrix<Key> / e2eMatrix<Key>SmokeOnly（mustRunAfter 链）
    versionMatrix("nms") {
        platform = paper          // 默认 paper；也支持 folia / spigot
        portBase = 25600          // 默认 25600；条目端口 = portBase + 序号
        botUsernamePrefix = "Tb"  // bot-full 用户名前缀 → Tb1206
        botAction = "taboolib-full" // bot 场景 action（与机器人分发表一致）
        entry("1.20.1")                 // key 自动推导为 1201，smoke
        entry("1.20.6") { bot = true }  // full-1206 + bot
        entry("26.2", key = "262")      // 显式 key
        // versions("1.21.1", "1.21.5")  // 批量 smoke
    }
}
```

**版本矩阵展开规则**：每个条目生成
- 后端 `backendNamePrefix + key`（默认 `v` + key，如 `v1201`）
- `bot=false` → 场景 `smoke-<key>`（ACTIVE 即跑，无机器人）
- `bot=true` → 场景 `full-<key>` + bot（`e2eFull<Key>WithBot`）
- 聚合 `e2eMatrix<Key>` dependsOn 全部场景任务，并用 `mustRunAfter` 串行，避免并行起多套 Paper
- 矩阵名/条目 key 与既有 backend/scenario 撞名时配置期中文报错

**节点运行时注入（FR-20/22）**：`BackendSpec` 提供 `env(name, value)`、`templateDirectory(envOrPath)`、`jvmArg(value)`、`javaAgent(envOrPath)` 与 `mavenServer(coordinate)`；`ProxySpec` 额外提供独立 `version`、`javaVersion`、`plugin(envOrPath)`、`env(name, value)`、`templateDirectory(envOrPath)`、`jvmArg(value)`、`javaAgent(envOrPath)` 与 `mavenServer(coordinate)`。`javaAgent` 在执行期优先按环境变量取值，否则按路径解析；同一节点重复声明同名 `env` 时后值覆盖前值，不同节点互不共享。`dependencies { }` 的语义不变，仍只把待测与依赖插件注入后端；代理插件只能经对应代理节点的 `plugin(...)` 声明。

新增 `BackendSpec` / `ProxySpec` 的 `envOrPath` 先按环境变量名读取：非空环境值优先且不再回退；未设置或空值时把声明本身作为路径。绝对路径原样使用，相对路径相对应用插件的 `Project.projectDir` 解析；代理插件必须是存在的普通 `.jar` 文件，模板必须是存在的目录，否则在启动任务涉及的任一节点前中文失败。后端未声明节点模板时继续兼容旧全局 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR`，声明后只使用节点模板。

旧全局 `MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR` **不改成新增 `envOrPath` 语义**：其非空值保持 v0.4.2 的 `File(raw)` 解析方式，绝对路径原样使用，相对路径按 JVM / Gradle 当前工作目录解析；在多工程中应用插件于子工程时，也不会改按该子工程的 `Project.projectDir`。因此旧 CI 若从根工程 / 既有 Gradle 工作目录提供相对模板路径，无需迁移。

节点 `env(...)` 的名称不能为空、不得含等号或空字符，也不得以 `MC_TESTKIT_E2E_` 开头（大小写不敏感）；该保留前缀限制不影响把同前缀字符串用于 `plugin(...)` / `templateDirectory(...)` 的资源定位。子进程环境优先级固定为**宿主环境 < 节点环境 < 框架权威环境**；后端节点环境只进入对应后端，代理节点环境只进入对应代理，`ServerLauncher` 启动日志不输出环境值。

> 平台枚举只含后端 Paper/Folia/Spigot、代理 Velocity/Waterfall/BungeeCord，不含 Bukkit/Sponge（不列入计划，见 ADR-0013）。DSL 字段细节可能随 FR-03/04 在 `dsl/` 包内微调，但四个顶层块形态已冻结。集群（FR-10）经 scenario 块**加法新增** `backends(...)` 声明、压测（FR-11）加 `stress {}` 维度，均为加法扩展，不新增顶层块、不改既有字段语义（ADR-0008）。单场景多 bot（FR-16）经 **bot 声明加法扩展**：一个场景可声明多个 bot——具名 `bot("角色") { }`（异质，各自 `username`/`action`/`env`）与 `bot { count = N }`（同质复制 N 份），复用既有 env / 任务名、不新增顶层块；同场景声明 ≥2 个 bot 时每个须有唯一角色名，`count` 须 >0，压测场景禁用 `count`/多 bot（规模用 `botsPerServer`），违反配置期中文报错（ADR-0009）。

> **持久手测 serve（FR-17，ADR-0011）经新增第 5 个顶层块** `serve("name") { backend = …; via = … }` 引入：声明「把后端（+ 可选经代理）拉起并**挂住**供真人客户端手测」。这是 DSL 由「四块」演进为「五块」的**加法、非破坏**变更（既有声明不受影响，按 SemVer minor；不改既有四块语义）。与 `scenario` 区别：serve **不跑 bot、不判 PASS/FAIL**，只起服并阻塞到手动停。`backend` 省略=默认/单后端，`via` 省略=直连（设了 `via` 则该代理须 `routesTo` 目标后端，配置期校验）。声明 `backends(...)`（与 `backend` 互斥、须配 `via`）即**集群 serve**（FR-18）：N 后端 + 代理整套挂起，真人经代理 `/server` 切服手测（复用 FR-10 集群编排）。可选 `bot { }` / `bot("角色") { }`（FR-19，多 bot 规则同 FR-16）：serve 起声明的 bot 把环境驱到某状态、**不判定**，挂住「人机混场」让真人同时连入——bot 应是**自驱** action（serve 桩空闲、不发 `E2E_READY`，勿复用等桩 ready 的场景 action）。

> 经 **Velocity 代理**走 modern forwarding（代理 `velocity.toml` + 后端 `paper-global proxies.velocity`，共享 forwarding secret，见 ADR-0010）：支持单后端经代理 / 集群 `/server` 切换 / 崩溃接管 fallback；**不支持压测钉服**（Velocity 单端口无「一端口对一后端」，`stress + via=velocity` 配置期中文报错）。Velocity 用自有版本号（env `…VELOCITY_VERSION` 缺省 `3.5.1`，即受控的最新 3.x，非后端 MC 版本）；Waterfall/BungeeCord 经代理写 `config.yml`、Velocity 写 `velocity.toml` + `forwarding.secret`。

**机器人目录定位（Gradle 属性，非 DSL 块）**：消费方照抄 `template/bot` 到其项目；编排经 Gradle 属性 `mcTestkit.botDir` 定位（缺省相对**根工程**的 `e2e-bot`，入口脚本固定 `<botDir>/src/connectAndWait.js`）。目录命名不同的用 `-PmcTestkit.botDir=<目录>` 覆盖（相对路径相对根工程解析，绝对路径直接采用），保证可移植、不写死本机绝对路径。这是 FR-04 唯一新增可配项，刻意走 Gradle 属性而非新增 DSL 顶层块（保持 §3.1 冻结形态不变）。

**运行目录注入点（公开访问器，非 DSL 块）**：`mcTestkit.backendRunDirectory("<后端名>")` 返回该后端节点运行目录的 `Provider<Directory>`（`<buildDir>/mc-testkit/run-<后端名>`）。目录命名由 `McTestkitRunDirectories` 单点定义、与任务侧路径推导同源，消费方**不要自己拼目录路径**：布局属插件内部实现，自行拼写会在布局调整后静默失配（历史事故：消费方按旧布局写配置，配置落在无人使用的目录，服务端以默认配置启动、场景静默失败）。用法：注入任务以 `dependsOn("prepareE2e<场景>")` 保证顺序，动作内 `provider.get().asFile` 取目录；不要把扩展对象本身捕获进任务动作（配置缓存不友好）。

**依赖插件按 Maven 坐标解析（`dependencies { mavenPlugin(…) }`，加法扩展）**：`DependenciesSpec` 新增 `mavenPlugin(coordinate)`，声明一个依赖插件注入，坐标为 `group:artifact:version`。它解决的是「制品不随公开仓库分发」的场景——此前只能本机放文件 + 设环境变量，CI 里没有那个文件就跑不了；现在直接写坐标即可。

- **解析交给 Gradle 原生依赖解析**（见 ADR-0015）：框架不自己解析 POM、不做传递依赖、不管仓库与鉴权，用消费方**当前生效**的仓库即可。
- **只拉声明的那个制品**（`isTransitive = false`）：插件运行期依赖该进**服务端的库目录**而非 `plugins/`，自动投放会放错位置；消费方如需运行库仍自行管理。
- **注入位置与命名**：落后端运行目录的 `plugins/<制品名>.jar`（如 `foo-plugin-1.2.0.jar`），与 `plugin(...)` 的路径 / 环境变量来源自然共存；顺序为「被测插件 → `plugin(...)` 声明 → `mavenPlugin(…)` 坐标」，按各自声明顺序。
- **配置期校验**：必须恰为 `group:artifact:version` 三段非空；拒绝动态版本与版本区间（`+` / `latest.*` / `[1.0,2.0)`），因为它们会让同一份声明在不同时刻拉到不同制品。`-SNAPSHOT` 后缀**允许**（测试框架有正当用途），但快照制品不可复现，不适用需要严格重现的回归。
- **缺失报错**：坐标在仓库里找不到时，`prepareE2e*` 抛中文错误并归因（坐标拼写 / 该坐标的仓库未在本构建声明 / 拉取凭据缺失），不会留下半铺的运行目录。
- **目标文件名冲突**：多份声明（含坐标与路径 / 环境变量混用）落到同一 `plugins/<文件名>` 时，在铺运行目录前中文报错并列出冲突声明，不再静默覆盖（§1 的启动前资源预检契约；被测插件的目标名固定为 `plugin-under-test.jar`，故依赖插件不得与它同名）。
- **消费方若用 `RepositoriesMode.PREFER_SETTINGS`**（如 ServerProbe）：**项目级** repositories 会被忽略、只有 **settings 级**生效，请把仓库写在 `settings.gradle.kts` 里；框架不会替你在项目级补仓库。
- **配置缓存的边界**（ADR-0015/0016）：Gradle 原生解析无法真正推迟到任务动作内，故本实现**限定捕获范围**——坐标只被真正需要的任务捕获：起服类任务（`e2e*` / `serve*` 等）捕获依赖插件 + 全部服务端/代理坐标（故其调度时会解析全部已声明坐标）；`prepareE2e*` 只注入插件、不起服务端，只捕获依赖插件坐标；`stop<Key>Serve` / `syncE2eRuntimeCache` / `npmInstallBot` 等一概不解析坐标、不访问仓库。需要坐标的任务在配置缓存下照常存储 / 重用。

v1 **不做** `pluginUnderTest` 的坐标形式（解析机制可复用，待真实需求再加）；`pluginUnderTest` / `plugin(...)` 的语义与优先级完全不变。

**服务端 / 代理 jar 按 Maven 坐标解析（`backend/proxy { mavenServer(…) }`，加法扩展）**：`BackendSpec` 与 `ProxySpec` 各新增 `mavenServer(coordinate)`，声明该节点的服务端 / 代理软件 jar 来自 Maven 坐标 `group:artifact:version`。用于制品不随公开仓库分发、或需要固定 / 自建构建的场景（ADR-0016）。

- **解析优先级**：`①` env `*_JAR` 覆盖（最高，且**不会求值坐标**）→ `②` `mavenServer(坐标)` → `③` 内置下载。镜像命中不改变该顺序：同时设了 `*_JAR` 仍走覆盖。
- **解析交给 Gradle 原生依赖解析**（`isTransitive = false`，只取声明的那个制品）；仓库用你**当前生效**的仓库，凭据由你自己的 Gradle 配置提供。
- **镜像缓存**：解析出的 jar 会被拷贝进 `<gradleUserHome>/caches/mc-testkit-jars/maven/<group 路径>/<artifact>/<version>/<artifact>-<version>.jar`（沿用 Maven 仓库层级，**只由坐标推导，与 `version` 字段无关**）。**注册期先查该镜像**：命中则不创建任何解析配置 → 该任务被调度时不触仓库、可离线；镜像被删时执行期以中文错误拦下（提示重跑），绝不静默用错文件。
- **`version` 字段不受影响**：`version` 仍驱动服务端配置生成与 Java 运行时选择；`mavenServer` 只决定「jar 从哪来」。框架**不校验**两者一致（本就是不同维度）。
- **配置期校验**：与 `mavenPlugin` 同规则（三段式、拒动态版本与区间、放行 `-SNAPSHOT`）。
- **CI**：`~/.gradle/caches/mc-testkit-jars/maven` 在 `e2e.yml` 里有独立 `actions/cache` 条目；同时它也被 Gradle `modules-2` 与父目录条目覆盖，命中任一路径即可复用。

### 3.2 生成的任务（命名约定已冻结）

`<Key>` = 场景名折成的 PascalCase 中缀（`buy-success` / `buySuccess` → `BuySuccess`）；`<Proxy>` = 代理名同折。

| 任务名 | 用途 |
|---|---|
| `prepareE2e<Key>` | 准备某场景运行目录（注入插件、写配置） |
| `e2e<Key>` | 跑某场景（直连后端），读结果判 PASS/FAIL |
| `e2e<Key>Via<Proxy>` | 经对应代理跑某场景 |
| `launch<Key>Bot` | 启动该场景的 mineflayer 机器人 |
| `e2e<Key>WithBot` | 一键「启动机器人 + 验证」 |
| `npmInstallE2eBot` | 安装机器人 mineflayer 依赖（固定名） |
| `syncE2eRuntimeCache` | 运行库/下载缓存回写到持久缓存（固定名） |
| `purgeE2eRuntimeCache` | 清空持久缓存（固定名） |
| `stopProxy<Proxy>` | 停止某代理（按 pid 收尾）；由 `e2e<Key>Via<Proxy>` 经 `finalizedBy` 触发，亦可单独调用 |
| `e2e<Key>Cluster` | 集群跑某场景：N 后端全后台 + 代理（单 listener + N 具名 server）+ bot 经代理 `/server` 切换，读结果判 PASS/FAIL（FR-10） |
| `stop<Key>Cluster` | 停止某集群场景的全部后端与代理（按 pid 收尾）；由 `e2e<Key>Cluster` 经 `finalizedBy` 触发，亦可单独调用 |
| `e2e<Key>Stress` | 压测跑某场景：N 服 × M bot 钉服持续施压（代理 N-listener 钉服或直连）+ 各服结果聚合判 PASS/FAIL（FR-11） |
| `stop<Key>Stress` | 停止某压测场景的全部后端、代理与机器人（按 pid 收尾）；由 `e2e<Key>Stress` 经 `finalizedBy` 触发，亦可单独调用 |
| `serve<Key>` | 持久起服挂住供真人手测（FR-17，ADR-0011）：起后端（声明 `via` 则先起代理）、注入插件、桩空闲不判定，**前台阻塞**到手动停（Ctrl+C / `stop<Key>Serve`）。挂住期间**终端输入即服务端控制台命令**：直连转发到后端、集群 serve 转发到代理（见下条）。声明 `backends(...)` 即集群 serve（N 后端 + 代理整套挂起、`/server` 切服，FR-18）；可选 `bot { }` 起 bot 人机混场（FR-19）。`<Key>` = serve 名折 PascalCase |
| `stop<Key>Serve` | 停止某 serve 的全部后端 + 代理 + 机器人（按 pid 收尾）；供「另一终端停」或「Ctrl+C 没清干净」时兜底。**注意**：在 `serve<Key>` 所在终端直接敲 `stop` 即可停后端（见控制台命令），本任务用于另一终端兜底 |
| `e2eMatrix<Key>` | 版本矩阵串行聚合：dependsOn 矩阵全部场景任务 + mustRunAfter 链（versionMatrix） |
| `e2eMatrix<Key>SmokeOnly` | 版本矩阵仅 smoke 子集串行聚合 |

> 集群任务（`e2e<Key>Cluster` / `stop<Key>Cluster`）由场景声明 `backends(...)`、压测任务（`e2e<Key>Stress` / `stop<Key>Stress`）由场景声明 `stress {}` 触发（FR-10/11，ADR-0008）。任务名一旦发布即视为契约，保持稳定。
> **单场景多 bot 不新增任务名**（FR-16，ADR-0009）：场景声明多个 bot 时，既有 `launch<Key>Bot` / `e2e<Key>` / `e2e<Key>WithBot` / `e2e<Key>Cluster` **起多个 bot 进程**（per-bot 唯一 `BOT_USERNAME` / 各自 `BOT_ACTION` / 同质复制下发 `BOT_INDEX`），并随场景结束按 pid 全部收尾（集群多 bot pid 收尾并入 `stop<Key>Cluster`）。
> `<Key>` 缺省后端：场景未写 `backend =` 时取首个声明的后端（单后端无需显式指定）。一个声明了 `via` 的场景同时生成直连 `e2e<Key>` 与经代理 `e2e<Key>Via<Proxy>` 两个任务。
>
> **Gradle 双缓存兼容**：消费方可同时启用 `--configuration-cache` 与 `--build-cache`。任务动作捕获图不含 `Project`（配置缓存可存储 / 复用）；全部本插件任务为副作用生命周期任务，注册时声明永不 UP-TO-DATE，类型非 `@CacheableTask`，开启构建缓存时仍真实执行（不得 `FROM-CACHE` 假跳过）。此处「构建缓存」与 FR-02 的 jar 下载缓存（`JarCache` / 持久运行库）无关。

#### 3.2.1 serve 的服务端控制台命令（FR-17）

`serve<Key>` 挂住期间，**在该终端输入的内容会作为服务端 / 代理的控制台命令逐行下发**（回车即发）：

| serve 形态 | 命令投递目标 | 典型命令 |
|---|---|---|
| 直连单后端 | 后端进程的控制台 | `stop`（停服）、`say <文本>`、`op <玩家>`、`whitelist add <玩家>` |
| 经代理单后端 / 集群 serve | **代理**进程的控制台（serve 阻塞在代理上，真人的入口也是代理） | `end`（停代理）、`glist`、`send <玩家> <服>`；切服在游戏内用 `/server` |

要点：

- **只 `serve<Key>` 接线**：`e2e*` 等自动化任务**不接收**终端输入——自动化跑的结果不应因人工输入而变（保持确定性与可复现）。
- **命令即服务端控制台语义**：写的是服务端自己认的命令（Bukkit/Paper 与各家代理的控制台命令），框架不解析、不拦截、不代答。
- **结束路径安静**：无终端（CI、`< /dev/null`、重定向）时读到 EOF 即停止转发；目标进程退出后不再投递；均不报错、不刷屏。仅当目标仍存活却写不进去时才中文告警。
- 收尾仍以「进程退出」为准（`stop` / `end` 发完生效后任务自然返回），`Ctrl+C` 与 `stop<Key>Serve` 仍是兜底路径。
- 用公开 API 自行编排时，`ServerLauncher.launch` 返回的 `Process` 同样保持 stdin 可写（见 §5），可自行向控制台注入命令。

### 3.3 环境变量约定（前缀已冻结：`MC_TESTKIT_E2E_`）

用于覆盖默认值、提供 jar / 模板路径、调节规模与超时（须可移植、不写死本机绝对路径）。前缀固定 `MC_TESTKIT_E2E_`（ADR-0006，本期不做 DSL 可配）。已冻结的核心名（**全集随 FR-02/04/06 补全，前缀与风格不变**）：

- 服务端/模板：`…MINECRAFT_VERSION`、`…SERVER_TEMPLATE_DIR`、`…PLUGIN_UNDER_TEST_JAR`、`…PAPER_JAR`/`…FOLIA_JAR`/`…SPIGOT_JAR`（后端 jar 覆盖，离线/CI 逃生口）。其中旧全局 `…SERVER_TEMPLATE_DIR` 的相对值按 JVM / Gradle 当前工作目录解析；新增节点 `templateDirectory(envOrPath)` 的相对值才按应用项目 `Project.projectDir` 解析。
- 桩↔编排交接（编排起后端时下发，桩据此判定）：`…SCENARIO`（本次场景 id = **DSL 场景名原样下发**，桩据此选场景；故 DSL 场景名须与桩 `ScenarioName` id、机器人 `action` 用**同一个 kebab-case id**、三处一致，否则桩无法匹配场景而判失败）、`…RESULT_FILE`（结果文件**绝对路径** = verify 读取处，桩写到这里二者对齐）、`…BACKEND_NAME`（**本后端的声明名**，编排起**每个**后端时下发，与 `…CLUSTER_BACKENDS` 同源、有序对应；集群/压测下各服各不相同，消费方据此 per-backend 派生身份——典型用法是拼不同 `server-id` 后缀。编排只「告诉每个后端它是谁」，不规定怎么用，见 FR-12）。
- **持久手测保留场景 id**（FR-17，ADR-0011）：serve 起后端时经 `…SCENARIO` 下发保留 id `__mc_testkit_serve__`（契约常量 `McTestkitContract.SERVE_SCENARIO_ID`），告诉桩**空闲**（不驱动 / 不判定 / 不关服）；`template/harness` 据此空闲（未同步新模板的老桩遇此未知 id 在 `onEnable` 抛错被禁用、服务端照常挂起，对任何桩都安全）。serve **不下发** `…RESULT_FILE`（不判定）。此 id 用双下划线前后缀与消费方 kebab-case 业务场景名划清边界。
- 代理（jar/版本/端口）：`…WATERFALL_JAR`/`…WATERFALL_VERSION`、`…VELOCITY_JAR`/`…VELOCITY_VERSION`、`…BUNGEECORD_JAR`/`…BUNGEECORD_VERSION`、`…PROXY_PORT`、`…PROXY_BASE_PORT`。
- 节点 Java（FR-22）：`MC_TESTKIT_JAVA_HOME_<主版本>`，如 `MC_TESTKIT_JAVA_HOME_25`。显式声明的 proxy `javaVersion` 缺失时启动前中文失败；backend 继续沿用 MC 版本段选择并可通过 `jvmArg`/`javaAgent` 注入诊断参数。
- 机器人：`…BOT_ACTION`（场景 action / 场景 id，机器人内核据此分发）、`…BOT_HOST`/`…BOT_PORT`/`…BOT_USERNAME`/`…BOT_AUTH`/`…BOT_VERSION`、`…BOT_CONNECT_TIMEOUT_MS`/`…BOT_RETRY_DELAY_MS`/`…BOT_READY_TIMEOUT_MS`、`…BOT_RECEIPT_FILE`（机器人**就绪回执文件**的绝对路径，位于结果目录 `bot-<key>.receipt.jsonl`；机器人就绪/超时状态落此文件，供编排判机器人是否入服，属框架 ↔ 机器人契约，消费方一般不需改动）。
- 集群（FR-10）：`…CLUSTER_BACKENDS`（集群场景的**有序后端名**，逗号分隔；编排起 bot 时下发，bot 据此经代理 `/server <name>` 逐个切换到后续后端）。
- 压测（FR-11）：`…STRESS_DURATION_SECONDS`（施压秒数，编排→bot 与桩）、`…BOT_INDEX`（每 bot 进程序号；FR-16 同质批量复制亦复用）、`…STRESS_RANDOM_SEED`（共享随机种子；bot 用 `seed xor botIndex` 播种 RNG 使各 bot 可复现且互异）。规模（服数 / 每服 bot 数）由 DSL `backends(...)` + `stress { botsPerServer }` 表达，不走 env。经代理时机器人协议版本仍由编排固定为后端版本（环境契约，FR-05）。
- 单场景多 bot（FR-16）：**不新增 env**，复用 `…BOT_USERNAME`（多进程时编排强制下发**唯一**名，盖过消费方单值 override）、`…BOT_ACTION`（各 bot 各自的分发动作）、`…BOT_INDEX`（同质 `count = N` 复制时下发 1..N，桩据此按 index 聚合）；集群下每个 bot 仍各自收到 `…CLUSTER_BACKENDS` 以经代理 `/server` 切换。规模（份数 / 角色）由 DSL `bot { count }` + 多个 `bot("角色")` 表达，不走 env（ADR-0009）。
- 机器人协议版本（`…BOT_VERSION`）经代理时由编排自动固定为后端版本（环境契约，FR-05）。
- 代理下载版本缺省取**后端版本**（与 `…BOT_VERSION` 同源）；Waterfall 在 PaperMC 仅按 major.minor 发布，故其缺省与 `…WATERFALL_VERSION` 覆盖均解析为 major.minor（后端 `1.20.1` → Waterfall `1.20`），传完整补丁号版本会 404。

发布由 CI 完成（打 `vX.Y.Z` tag 触发 `release.yml`，见 `docs/CONTRIBUTING.md` §8 / `docs/OPERATIONS.md` §1.2）；发布凭据仅存 GitHub 仓库的 `release` environment secrets，不入库。本地仅在需要手工试验 `./gradlew publish` 时才提供同名的 `WCPE_MAVEN_USERNAME` / `WCPE_MAVEN_PASSWORD`（Gradle 属性或环境变量）。

### 3.4 机器人↔桩控制协议（已冻结）

机器人与桩之间通过聊天/插件消息通道约定消息，载荷走 `:` 后缀：

- `E2E_READY:<scenario>`：桩通知机器人「已装备就绪，可开始驱动」。
- `E2E_STRESS_RESULT:ok=<n>,err=<n>,…`：压测机器人向桩上报汇总。
- `E2E_DISCONNECT_NOW:<…>`：触发机器人在购买中主动断线（中断恢复场景）。
- `E2E_UI_TOKEN:<uuid>`：经插件消息 UI 通道驱动时下发的会话 token。

> 以上 4 条为**冻结的框架核心协议**。集群 / 崩溃接管等场景的桩↔bot 还可约定**场景特定标记**——如 `template/harness` 里 bot 到达 / 落到目标后端后发的「到达确认」标记 `E2E_CLUSTER_ARRIVED`，及崩溃接管（FR-15）示例里 bot 触发默认后端模拟宕机的 `E2E_TRIGGER_CRASH`（桩收到即 `Runtime.halt`）——均属 template / 消费方约定、**不进**冻结协议；真实跨服一致性 / 接管判定由消费方桩按业务替换。

### 3.5 结果文件（测试结论真源，已冻结）

桩写出 `<scenario>.properties`，编排 verify 任务**只认此文件**判定（不靠日志猜测，架构不变量真源）：

- `status`：`PASS` / `FAIL`。
- `message`：结论说明（失败原因写这里）。
- 其余键：场景特定字段（如 `rewardCount` / `costLeft` / `txId`），由消费方桩与场景自定。

> **桩怎么知道写哪**：编排起后端时经 `MC_TESTKIT_E2E_SCENARIO` / `MC_TESTKIT_E2E_RESULT_FILE`（§3.3）下发场景与结果文件绝对路径；桩优先读这两个 env（覆盖自身配置默认），把结果写到 `RESULT_FILE` = verify 读取处。这样通用编排无需知道各消费方桩的配置格式即可对齐结果位置。`template/harness` 已按此实现。
>
> **须原子落盘**：桩写结果文件必须**原子**完成（写同目录临时文件 + 原子 rename 替换），避免编排 verify 读到「写了一半」的结果文件而误判。单后端前台路径靠「后端进程退出后才 verify」时序天然隔离，但集群/压测轮询结果文件、以及消费方桩异步写时存在并发窗口，故约定为契约。共享库 `harness-core` 的 `McTestkitResultWriter` 已按此实现；`template/harness` 继承 `McTestkitHarnessPlugin` 即获得该保证（消费方自写桩须照此保证，见 §4）。

## 4. 共享构件（FR-09，ADR-0014）

桩与机器人的协议胶水以可发布构件提供，消费方依赖构件而非照抄 / 手写（见 docs/specs/fr-09-shared-harness-bot.md）：

- **`harness-core`**：`top.wcpe.mc:harness-core:0.1.1`（Maven，maven.wcpe.top）。纯 Java、零 Kotlin 依赖、**Java 8 字节码**（兼容 Paper 1.7.10–1.21 多版本烟雾）、paper-api 仅 compileOnly。提供 `McTestkitEnv`（契约 env 常量 / 读取 / serve 空闲判断）、`McTestkitProtocol`（冻结控制协议常量）、`McTestkitResultWriter`（结果文件原子写出）、`McTestkitHarnessPlugin`（Bukkit 抽象基类：场景/结果文件解析、serve 空闲短路、判定收尾、E2E_READY、Paper/Folia 兼容调度）。模板 `plugin.yml` **不声明 `api-version`**（声明 1.20 会拒载旧版，声明 1.8 会被 1.20+ 拒绝）。
- **`@wcpe/mc-testkit-bot`**：`@wcpe/mc-testkit-bot@0.1.0`（npm）。mineflayer 公共内核 `runBot({ scenarios })`（端口探测 / 重试 / action 分发 / 断线重连 / 优雅收尾），子路径 `@wcpe/mc-testkit-bot/lib/{messages,random,normalize,env}`。

**消费方接线**：桩插件 `implementation("top.wcpe.mc:harness-core:0.1.1")`（打进插件 jar），继承 `McTestkitHarnessPlugin` 写业务场景；机器人 `npm i @wcpe/mc-testkit-bot`，入口登记 action → 场景驱动表。`template/` 是这两个构件的示例消费者。

## 5. 编程 API：复用下载/运行基建（FR-23，ADR-0017）

除 DSL 与自动任务外，插件还**公开**了内部的下载与运行能力。适用场景：消费方有自己的编排模型（如驱动真实游戏客户端的验收编排、长期持有进程的 `BuildService`），用不上整体编排，但想复用下载 / 起服 / 缓存 / 校验。

```kotlin
import top.wcpe.mc.testkit.provision.*
import top.wcpe.mc.testkit.config.ServerProperties

// 平台 + 版本 → jar（含 `*_JAR` 覆盖 / Maven 坐标 / 内置下载三级裁决；缓存复用）
val provisioner = ServerJarProvisioner.create(cacheRoot) { System.getenv(it) }
val paper = provisioner.resolve("paper", "1.20.1") { logger.lifecycle(it) }

// Fill v3 客户端：解析任意 PaperMC 项目的构建与下载项（含 `module:*`）
val api = PaperDownloadsApi()
val build = api.latestBuild("waterfall", "1.20")
val modules = api.downloads("waterfall", "1.20", build)

// Waterfall `module:*` 预置（保 `/server` 切服可用）
WaterfallModuleProvisioner().provision("1.20", proxyRunDir)

// 起服（返回 Process；stdin 保持打开，可向控制台注入命令）
val process = ServerLauncher.launch(paper, runDir, "s1", jvmArgs = listOf("-Xmx1G"))

// 其它：Java 运行时选择 / 服务端属性 / 完整性校验
JavaRuntimeSelector.executable("1.20.1") { System.getenv(it) }
ServerProperties.edit(runDir, mapOf("server-port" to "25565"))
val digest = paper.sha256()
```

**外部制品源**（平台枚举之外的第三方制品，如 Hangar 固定版本、API 动态解析的最新版地址）：

```kotlin
val external = ExternalArtifactProvisioner(cacheRoot)

// ① 固定 URL
val fixed = ExternalArtifactSource.FixedUrl(
    id = "placeholderapi",
    fileName = "PlaceholderAPI-2.12.2.jar",
    url = "https://hangar.papermc.io/api/v1/projects/PlaceholderAPI/versions/2.12.2/PAPER/download",
)

// ② API 动态解析：先取 apiUrl 响应文本，再交纯函数 resolver 解析地址
val resolved = ExternalArtifactSource.ApiResolved(
    id = "papi-expansion-player",
    fileName = "PAPI-Expansion-Player.jar",
    apiUrl = "https://ecloud.placeholderapi.com/api/v3/?platform=bukkit",
    // 取最后一个匹配（版本数组按升序，末项即最新；避免硬编码带内容哈希的地址）
    resolver = ArtifactUrlResolver.lastUrlMatch(""""url"\s*:\s*"([^"]*PAPI-Expansion-Player[^"]*\.jar)""""),
)

val jar = external.provision(resolved) { logger.lifecycle(it) }   // 命中缓存不发网络
val path = external.cacheFile(fixed)                              // 纯函数推导，不下载
```

**公开面**：`ServerJarProvisioner`、`ServerLauncher`、`JavaRuntimeSelector`、`PaperDownloadsApi` / `PaperDownload`、`Downloader`、`DownloadProgress`、`WaterfallModuleProvisioner`、`ExternalArtifactProvisioner` / `ExternalArtifactSource` / `ArtifactUrlResolver`、`File.sha256()`。

**HTTP 代理（`Downloader` 自动生效）**：下载与取文本会**自动走你为 Gradle 配的代理**——读系统属性 `http.proxyHost/Port` 与 `https.proxyHost/Port`（即 `gradle.properties` 里 `systemProp.http.proxyHost=…` 的落点；Gradle 会把它施加为守护进程的真实系统属性，而插件正运行其中），未配置该组时回退环境变量 `HTTP_PROXY` / `HTTPS_PROXY`（两种大小写拼法都认，JVM 原生不读它，容器 / CI 常只提供这个）。绕过清单 `http.nonProxyHosts`（Java 语义：`|` 分隔、子域须显式 `*.`）与 `NO_PROXY`（curl 语义：`,` 分隔、裸域名**同时命中其子域**、支持 `*`）都会被遵守。**不接管代理认证**——带 userinfo 的地址只取 host:port；需要认证请用系统属性形式交由 JVM 的 `Authenticator` 处理。未配置任何代理时行为与本功能引入前完全一致（直连）。

**下载进度**：`Downloader.download` 的第四参 `progress`（默认不回报）可拿到字节级进度，用于替代「十几 MB 的 jar 下载期间完全无输出」：

```kotlin
// 自定义：只要进度条 / 计数
Downloader.download(url, target, logger) { written, total ->
    println("$written / ${if (total > 0) total else "?"}")
}
// 或直接用框架的按百分比节流日志（推荐：逐块回调约 240 次，不节流会刷屏）
Downloader.download(url, target, logger, DownloadProgress.logging(logger, "paper-1.20.1.jar"))
```

框架自身的下载路径（`ServerJarProvisioner` 起服任务、Waterfall 模块预置、外部制品源）**默认已开启**该进度日志，无需消费方额外接线。`total` 为 `-1` 表示总长未知（分块传输 / 无 `Content-Length`），此时 `logging` 退化为按 MB 节流。

**契约与约束**：

- 公开面**即对外契约**：签名变更按 SemVer 升 major；新增为加法演进。
- **须可序列化**：消费方会把这些对象捕获进任务动作 / `BuildService` 参数，配置缓存拒不可序列化者。故实现自定义解析器时**不要用 Kotlin lambda**（SAM 转换结果不是 `Serializable`）——用具名类或 object 表达式，或直接用 `ArtifactUrlResolver.lastUrlMatch(...)`。
- **不是市场客户端**：外部源刻意不做市场 API / 搜索 / 版本列表 / 鉴权管理，也不做依赖传递解析——只拉你声明的那个制品。需要鉴权的源请自行保证 URL 可达。
- 未公开（`internal`）：`ProvisionPlatform`（对外平台用 `dsl/Platforms`）、`JarProvisionService` / `JarCache`（其能力已由 `ServerJarProvisioner` 以 `String` 平台完整覆盖）。
