# mc-testkit

[![CI](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml)
[![E2E](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml)
[![version](https://img.shields.io/badge/version-v0.7.0-blue)](VERSION)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)

> 面向 Minecraft 插件的**全平台端到端测试编排** Gradle 插件 + 配套脚手架模板：把真实服务端/代理拉起、互联成测试拓扑，用机器人驱动端到端场景、判定结果并收尾，统一各插件五花八门的 E2E 做法。

## 特性

- **声明式拓扑 DSL**：`mcTestkit { }` 一行声明「后端 + 代理」拓扑，自动注册 prepare / 启动 bot / runServer / proxy / cluster / stress / verify / 缓存回写等任务，配置期中文报错。
- **内置下载与运行**：自实现 Paper/Folia 后端与 Velocity/Waterfall/BungeeCord 代理的下载与运行（不外挂第三方下载库，ADR-0001），jar 缓存 + hash 校验复用，`MC_TESTKIT_E2E_*_JAR` 环境变量可覆盖。
- **多版本服务端拉起（v0.6.0，FR-21）**：8 个代表版本（1.7.10 / 1.8.8 / 1.12.2 / 1.16.5 / 1.17.1 / 1.19.4 / 1.20.1 / 1.21.1）Paper 下载、拉起与版本感知配置适配——按版本过滤 `server.properties` 键、生成 `paper.yml` / `paper-global.yml`、选择 Java 运行时（`MC_TESTKIT_JAVA_HOME_<版本段>` 覆盖 > `JAVA_HOME` 回退）；1.7.10 自动跳过 bot 并告警。另支持 Minecraft 26.x 新版号识别。
- **全平台代理**：Velocity（modern forwarding）/ Waterfall / BungeeCord，单后端经代理、集群 `/server` 切换、崩溃接管 fallback 均实机跑通（ADR-0010）。
- **多版本代理与诊断型 JVM 编排（v0.7.0，FR-22）**：`proxy` 可声明独立 `version`、`javaVersion`、`jvmArg(...)` 与 `javaAgent(...)`，`backend` 可声明 `jvmArg(...)` 与 `javaAgent(...)`；普通/代理/集群/压测/serve 全部启动路径统一传参，显式 Java 主版本经 `MC_TESTKIT_JAVA_HOME_<主版本>` 严格选择，不回退到不匹配的运行时（ADR-0012）。
- **多后端集群（FR-10/15）**：N 后端 + 代理「单 listener + N server」，bot 经代理跨服切换、桩跨服判定；默认后端宕机时 bot 重连回退到存活后端，支撑「崩溃接管」类 E2E。
- **多后端持续压测（FR-11）**：N 服 × M bot 钉服施压，各服桩收集聚合结果、框架统一判定。
- **单场景多 bot（FR-16）**：异质具名角色 + 同质批量复制（`bot { count = N }`），各唯一 username、经 `BOT_INDEX` 区分。
- **每后端身份注入（FR-12）**：起每个后端下发其 DSL 声明名 `MC_TESTKIT_E2E_BACKEND_NAME`，消费方据此 per-backend 派生身份（如同组各服不同 `server-id`）。
- **节点运行时注入（v0.5.0，FR-20）**：backend / proxy 可分别声明节点 env 与模板目录，proxy 可声明专属插件；`dependencies { }` 仍只注入后端。
- **持久手测 serve（v0.4.0，FR-17/18/19）**：复用同一拓扑声明把「（代理 +）后端 + 插件」挂起供真人客户端连入手测——单后端 / 集群 `/server` 切服 / 可选并起 bot 人机混场；Ctrl+C / `stop<Key>Serve` 三重收尾、端口不漏。
- **固化环境契约**：`server.properties` 真实读改写回、BungeeCord/Velocity 配置 YAML 对象化深合并、经代理固定 bot 协议版本、依赖（数据源/Redis）注入校验，一处固化消费方默认生效。
- **脚手架模板**：`template/` 提供桩插件骨架（Paper/Folia 双兼容）+ mineflayer bot 内核 + 示例场景，照抄即用。
- **自举实机 E2E**：CI 实机跑通全矩阵——单服(±bot) / 经代理（Waterfall·BungeeCord·Velocity）/ 集群 / 压测 / 单场景多 bot / 崩溃接管 / Folia 后端。

## 支持的平台

| 角色 | 平台 | 说明 |
|---|---|---|
| 后端 | Paper / Folia | 1.7.10–1.21.1 代表版本（v0.6.0 起，FR-21）；按版本适配配置与 Java 运行时 |
| 代理 | Velocity / Waterfall / BungeeCord | 含 Velocity modern forwarding；v0.7.0 起 Velocity 可指定版本（3.1.1 / 最新 3.x，4.1.0 需 Java 25）；`stress + via=velocity` 因单端口不支持（配置期中文报错） |
| 机器人 | mineflayer（Node.js） | 随 `template/` 提供内核；1.7.10 不支持（跳过 + 日志告警） |
| 范围外 | Spigot / Bukkit / Sponge | 平台范围见 ADR-0003 |

## 快速开始

**① 声明插件仓库**（`settings.gradle.kts`）：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
}
```

**② 应用插件、声明拓扑与场景**（`build.gradle.kts`）：

```kotlin
plugins {
    id("top.wcpe.mc-testkit") version "0.7.0"
}

mcTestkit {
    backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
    proxy("wf") { platform = waterfall; port = 25577; routesTo("s1") }
    scenario("buy") {
        backend = "s1"; via = "wf"
        bot { username = "Buyer"; action = "buy" } // 业务 env 经 bot { env(name, value) } 透传
    }
    dependencies {
        pluginUnderTest = "MY_PLUGIN_JAR"  // 环境变量名或 jar 路径
        plugin("SampleLib")                  // 依赖插件（同上）
    }
}
```

**③ 照抄脚手架并跑场景**：把 `template/`（桩插件 + 机器人内核）拷进项目按 [`template/README.md`](template/README.md) 接线，然后：

```bash
./gradlew e2eBuy          # 直连后端跑场景
./gradlew e2eBuyViaWf     # 经 Waterfall 代理跑场景
```

**v0.5.0+ 节点声明示例**：

```kotlin
backend("s1") {
    env("MYPLUGIN_NODE", "s1")
    templateDirectory("MC_TESTKIT_E2E_S1_TEMPLATE_DIR")
}
proxy("wf") {
    routesTo("s1")
    plugin("MC_TESTKIT_E2E_PROXY_PLUGIN_JAR")
    env("MYPLUGIN_PROXY_NODE", "wf")
    templateDirectory("MC_TESTKIT_E2E_PROXY_TEMPLATE_DIR")
}
```

`envOrPath` 的非空环境变量值优先，否则按路径解析；节点 `env(...)` 不得声明大小写任意形式的 `MC_TESTKIT_E2E_` 保留前缀。`dependencies { }` 仍只注入后端，不会把待测或依赖插件复制到代理。

服务端模板 / 依赖 jar / 规模等经 `MC_TESTKIT_E2E_*` 环境变量提供，完整任务名与环境变量约定见 [`docs/API.md`](docs/API.md)。本仓库自身的构建/发布命令见 [`docs/OPERATIONS.md`](docs/OPERATIONS.md)。

## 架构一览

三层协作（详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)）：

- **Gradle 编排插件**（本仓库核心，`top.wcpe.mc-testkit`）：内置下载并运行 Paper/Folia 后端与 Velocity/Waterfall/BungeeCord 代理（下载/运行模块自实现，不外挂第三方下载库，见 ADR-0001）；用 `mcTestkit { }` DSL 声明「代理 + 多后端」拓扑，自动注册 prepare / 启动 bot / runServer / proxy / cluster / stress / verify / 缓存回写等任务，并固化已知环境契约。
- **服务端桩插件**（随项目，模板提供骨架）：装备入服玩家、按场景驱动、与 bot 收发控制消息、判定结果写结果文件。
- **mineflayer 机器人**（随项目，模板提供内核）：模拟真实玩家入服，驱动购买/交互等端到端场景。

## 项目结构

```
mc-testkit/
  build.gradle.kts / settings.gradle.kts   # Gradle 插件工程（java-gradle-plugin + kotlin-dsl）
  src/main/kotlin/top/wcpe/mc/testkit/      # 插件实现：McTestkitPlugin + mcTestkit{} DSL + 任务/编排助手
  template/                                 # 脚手架：桩插件骨架 + mineflayer bot 内核 + 示例场景 + 复制说明
  docs/                                     # PRD / ARCHITECTURE / API / ADR / 运维 / 贡献指南
  .claude/rules/                            # 防漂移规则（架构不变量 / 范围 / 决策 / 文档 / 质量 / 风格）
```

> `template/` 是纯拷贝脚手架（不被插件构建依赖、不进发布产物）；消费方照抄到自己项目按需改。

## 文档导航

- 需求：[`docs/PRD.md`](docs/PRD.md)
- 架构：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- 接口：[`docs/API.md`](docs/API.md)
- 运维：[`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- 安全：[`SECURITY.md`](SECURITY.md)
- 决策：[`docs/adr/`](docs/adr/)
- 演进与维护：[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)
- 变更史：[`CHANGELOG.md`](CHANGELOG.md)

## 版本与变更

当前 **v0.7.0**（发布到 [maven.wcpe.top](https://maven.wcpe.top)）。完整变更见 [`CHANGELOG.md`](CHANGELOG.md)；能力与进度以 [`docs/PRD.md`](docs/PRD.md) §4 FR 表状态列为准。

## 贡献

提交、分支、文档同步等协作约定见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) 与 [`.claude/rules/`](.claude/rules/)。

## 许可

[MIT](LICENSE)。
