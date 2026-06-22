# mc-testkit

[![CI](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml)
[![E2E](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml)

> 面向 Minecraft 插件的「全平台端到端测试编排」Gradle 插件 + 配套脚手架模板：把真实服务端/代理拉起、互联成测试拓扑，用机器人驱动端到端场景、判定结果并收尾，统一各插件五花八门的 E2E 做法。

## 状态

**v0.1.0**，发布到 [maven.wcpe.top](https://maven.wcpe.top)。以首个接入项目为消费者实机跑通 `e2eSmoke`、「经 Waterfall 代理购买」、跨服集群与持续压测；能力与进度以 [`docs/PRD.md`](docs/PRD.md) §4 FR 表状态列为准。

## 架构一览

三层协作（详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)）：

- **Gradle 编排插件**（本仓库核心，`top.wcpe.mc-testkit`）：内置下载并运行 Paper/Folia 后端与 Velocity/Waterfall/BungeeCord 代理（下载/运行模块自实现，不外挂第三方下载库，见 ADR-0001）；用 `mcTestkit { }` DSL 声明「代理 + 多后端」拓扑，自动注册 prepare / 启动 bot / runServer / proxy / cluster / stress / verify / 缓存回写等任务，并固化已知环境契约。
- **服务端桩插件**（随项目，模板提供骨架）：装备入服玩家、按场景驱动、与 bot 收发控制消息、判定结果写结果文件。
- **mineflayer 机器人**（随项目，模板提供内核）：模拟真实玩家入服，驱动购买/交互等端到端场景。

## 能力（v0.1.0）

- 一行 DSL 声明并拉起「单后端」「代理 + N 后端」测试拓扑。
- 多后端集群（bot 经代理 `/server` 跨服切换、桩跨服判定）与多后端持续压测（N 服 × M bot 钉服施压、各服结果聚合）编排。
- 覆盖 Paper/Folia 后端 + Velocity/Waterfall/BungeeCord 代理（不含 Spigot/Bukkit/Sponge）。
- 自动编排：准备运行目录、注入待测/依赖插件、启动机器人、起服/起代理、读结果判定 PASS/FAIL、收尾杀进程、缓存回写。
- 固化环境契约：经代理时固定 bot 协议版本、paper-global 代理在线模式、BungeeCord 后端配置、依赖数据源/Redis 注入校验。
- `template/` 脚手架：新项目照抄即用的桩插件骨架 + bot 内核 + 一个示例场景。

## 结构

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

## 快速开始（消费方）

**1. 声明插件仓库**（`settings.gradle.kts`）：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
}
```

**2. 应用插件、声明拓扑与场景**（`build.gradle.kts`）：

```kotlin
plugins {
    id("top.wcpe.mc-testkit") version "0.1.0"
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

**3. 照抄脚手架并跑场景**：把 `template/`（桩插件 + 机器人内核）拷进项目按 [`template/README.md`](template/README.md) 接线，然后：

```bash
./gradlew e2eBuy          # 直连后端跑场景
./gradlew e2eBuyViaWf     # 经 Waterfall 代理跑场景
```

服务端模板 / 依赖 jar / 规模等经 `MC_TESTKIT_E2E_*` 环境变量提供，完整任务名与环境变量约定见 [`docs/API.md`](docs/API.md)。本仓库自身的构建/发布命令见 [`docs/OPERATIONS.md`](docs/OPERATIONS.md)。

## 约定

提交、分支、文档同步等协作约定见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) 与 [`.claude/rules/`](.claude/rules/)。

## 许可

[MIT](LICENSE)。
