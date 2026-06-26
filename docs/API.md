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

- **配置期**（Gradle 配置/校验）：必填项缺失、路径不存在、依赖 jar 未提供、路由目标后端不存在 → 抛 Gradle 异常，**中文**说明缺什么、怎么补。
- **执行期**（场景运行）：场景失败 → verify 任务抛错使构建失败（CI 非零退出）；失败原因写入结果文件 `message` 字段与对应日志（服务端 `logs/`、机器人 `*-bot-*.log`、代理 `proxy.log`）。

## 3. 接口 / 方法

### 3.1 DSL：`mcTestkit { }`（形态已冻结）

声明测试拓扑、场景与依赖注入。FR-01 冻结四个顶层块的形态；行为（拓扑解析、端口推导、校验、任务编排）由 FR-03/04 实现。平台便捷量（`paper`/`folia`/`velocity`/`waterfall`/`bungeecord`）让消费方无需 import 枚举。

```kotlin
mcTestkit {
    // 后端节点：platform 默认 paper；version 默认 1.20.1；port 省略则由拓扑解析按端口基数推导
    backend("s1") {
        platform = paper          // paper | folia（P1 仅此二者，ADR-0003）
        version = "1.20.1"
        port = 25565              // Int?，可省
    }
    // 代理节点与路由
    proxy("wf") {
        platform = waterfall      // velocity | waterfall | bungeecord
        port = 25577              // Int?，可省
        routesTo("s1")            // 转发到的后端名（路由目标存在性配置期校验）
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
    // 注入到运行目录的待测/依赖插件 jar（值为环境变量名或路径，运行期解析，保证可移植）
    dependencies {
        pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        plugin("SampleLib")
    }
}
```

> 平台枚举只含后端 Paper/Folia、代理 Velocity/Waterfall/BungeeCord，不含 Spigot/Bukkit/Sponge（不列入计划，scope-discipline）。DSL 字段细节可能随 FR-03/04 在 `dsl/` 包内微调，但四个顶层块形态已冻结。集群（FR-10）经 scenario 块**加法新增** `backends(...)` 声明、压测（FR-11）加 `stress {}` 维度，均为加法扩展，不新增顶层块、不改既有字段语义（ADR-0008）。单场景多 bot（FR-16）经 **bot 声明加法扩展**：一个场景可声明多个 bot——具名 `bot("角色") { }`（异质，各自 `username`/`action`/`env`）与 `bot { count = N }`（同质复制 N 份），复用既有 env / 任务名、不新增顶层块；同场景声明 ≥2 个 bot 时每个须有唯一角色名，`count` 须 >0，压测场景禁用 `count`/多 bot（规模用 `botsPerServer`），违反配置期中文报错（ADR-0009）。

> 经 **Velocity 代理**走 modern forwarding（代理 `velocity.toml` + 后端 `paper-global proxies.velocity`，共享 forwarding secret，见 ADR-0010）：支持单后端经代理 / 集群 `/server` 切换 / 崩溃接管 fallback；**不支持压测钉服**（Velocity 单端口无「一端口对一后端」，`stress + via=velocity` 配置期中文报错）。Velocity 用自有版本号（env `…VELOCITY_VERSION` 缺省 `3.3.0-SNAPSHOT`，非后端 MC 版本）；Waterfall/BungeeCord 经代理写 `config.yml`、Velocity 写 `velocity.toml` + `forwarding.secret`。

**机器人目录定位（Gradle 属性，非 DSL 块）**：消费方照抄 `template/bot` 到其项目；编排经 Gradle 属性 `mcTestkit.botDir` 定位（缺省相对**根工程**的 `e2e-bot`，入口脚本固定 `<botDir>/src/connectAndWait.js`）。目录命名不同的用 `-PmcTestkit.botDir=<目录>` 覆盖（相对路径相对根工程解析，绝对路径直接采用），保证可移植、不写死本机绝对路径。这是 FR-04 唯一新增可配项，刻意走 Gradle 属性而非新增 DSL 顶层块（保持 §3.1 冻结形态不变）。

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

> 集群任务（`e2e<Key>Cluster` / `stop<Key>Cluster`）由场景声明 `backends(...)`、压测任务（`e2e<Key>Stress` / `stop<Key>Stress`）由场景声明 `stress {}` 触发（FR-10/11，ADR-0008）。任务名一旦发布即视为契约，保持稳定。
> **单场景多 bot 不新增任务名**（FR-16，ADR-0009）：场景声明多个 bot 时，既有 `launch<Key>Bot` / `e2e<Key>` / `e2e<Key>WithBot` / `e2e<Key>Cluster` **起多个 bot 进程**（per-bot 唯一 `BOT_USERNAME` / 各自 `BOT_ACTION` / 同质复制下发 `BOT_INDEX`），并随场景结束按 pid 全部收尾（集群多 bot pid 收尾并入 `stop<Key>Cluster`）。
> `<Key>` 缺省后端：场景未写 `backend =` 时取首个声明的后端（单后端无需显式指定）。一个声明了 `via` 的场景同时生成直连 `e2e<Key>` 与经代理 `e2e<Key>Via<Proxy>` 两个任务。

### 3.3 环境变量约定（前缀已冻结：`MC_TESTKIT_E2E_`）

用于覆盖默认值、提供 jar / 模板路径、调节规模与超时（须可移植、不写死本机绝对路径）。前缀固定 `MC_TESTKIT_E2E_`（ADR-0006，本期不做 DSL 可配）。已冻结的核心名（**全集随 FR-02/04/06 补全，前缀与风格不变**）：

- 服务端/模板：`…MINECRAFT_VERSION`、`…SERVER_TEMPLATE_DIR`、`…PLUGIN_UNDER_TEST_JAR`、`…PAPER_JAR`/`…FOLIA_JAR`（后端 jar 覆盖，离线/CI 逃生口）。
- 桩↔编排交接（编排起后端时下发，桩据此判定）：`…SCENARIO`（本次场景 id = **DSL 场景名原样下发**，桩据此选场景；故 DSL 场景名须与桩 `ScenarioName` id、机器人 `action` 用**同一个 kebab-case id**、三处一致，否则桩无法匹配场景而判失败）、`…RESULT_FILE`（结果文件**绝对路径** = verify 读取处，桩写到这里二者对齐）、`…BACKEND_NAME`（**本后端的声明名**，编排起**每个**后端时下发，与 `…CLUSTER_BACKENDS` 同源、有序对应；集群/压测下各服各不相同，消费方据此 per-backend 派生身份——典型用法是拼不同 `server-id` 后缀。编排只「告诉每个后端它是谁」，不规定怎么用，见 FR-12）。
- 代理（jar/版本/端口）：`…WATERFALL_JAR`/`…WATERFALL_VERSION`、`…VELOCITY_JAR`/`…VELOCITY_VERSION`、`…BUNGEECORD_JAR`/`…BUNGEECORD_VERSION`、`…PROXY_PORT`、`…PROXY_BASE_PORT`。
- 机器人：`…BOT_ACTION`（场景 action / 场景 id，机器人内核据此分发）、`…BOT_HOST`/`…BOT_PORT`/`…BOT_USERNAME`/`…BOT_AUTH`/`…BOT_VERSION`、`…BOT_CONNECT_TIMEOUT_MS`/`…BOT_RETRY_DELAY_MS`/`…BOT_READY_TIMEOUT_MS`。
- 集群（FR-10）：`…CLUSTER_BACKENDS`（集群场景的**有序后端名**，逗号分隔；编排起 bot 时下发，bot 据此经代理 `/server <name>` 逐个切换到后续后端）。
- 压测（FR-11）：`…STRESS_DURATION_SECONDS`（施压秒数，编排→bot 与桩）、`…BOT_INDEX`（每 bot 进程序号；FR-16 同质批量复制亦复用）、`…STRESS_RANDOM_SEED`（共享随机种子；bot 用 `seed xor botIndex` 播种 RNG 使各 bot 可复现且互异）。规模（服数 / 每服 bot 数）由 DSL `backends(...)` + `stress { botsPerServer }` 表达，不走 env。经代理时机器人协议版本仍由编排固定为后端版本（环境契约，FR-05）。
- 单场景多 bot（FR-16）：**不新增 env**，复用 `…BOT_USERNAME`（多进程时编排强制下发**唯一**名，盖过消费方单值 override）、`…BOT_ACTION`（各 bot 各自的分发动作）、`…BOT_INDEX`（同质 `count = N` 复制时下发 1..N，桩据此按 index 聚合）；集群下每个 bot 仍各自收到 `…CLUSTER_BACKENDS` 以经代理 `/server` 切换。规模（份数 / 角色）由 DSL `bot { count }` + 多个 `bot("角色")` 表达，不走 env（ADR-0009）。
- 机器人协议版本（`…BOT_VERSION`）经代理时由编排自动固定为后端版本（环境契约，FR-05）。
- 代理下载版本缺省取**后端版本**（与 `…BOT_VERSION` 同源）；Waterfall 在 PaperMC 仅按 major.minor 发布，故其缺省与 `…WATERFALL_VERSION` 覆盖均解析为 major.minor（后端 `1.20.1` → Waterfall `1.20`），传完整补丁号版本会 404。

发布凭据走 Gradle 属性（`~/.gradle/gradle.properties`）或同名环境变量 `WCPE_MAVEN_USERNAME` / `WCPE_MAVEN_PASSWORD`（不入库）。

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
> **须原子落盘**：桩写结果文件必须**原子**完成（写同目录临时文件 + 原子 rename 替换），避免编排 verify 读到「写了一半」的结果文件而误判。单后端前台路径靠「后端进程退出后才 verify」时序天然隔离，但集群/压测轮询结果文件、以及消费方桩异步写时存在并发窗口，故约定为契约。`template/harness` 的 `ScenarioResultWriter` 已按此实现（消费方自写桩须照此保证）。
