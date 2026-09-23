# mc-testkit

[![CI](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/ci.yml)
[![E2E](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml/badge.svg)](https://github.com/wcpe/mc-testkit/actions/workflows/e2e.yml)
[![Release](https://img.shields.io/github/v/release/wcpe/mc-testkit)](https://github.com/wcpe/mc-testkit/releases/latest)
[![Maven](https://img.shields.io/badge/maven.wcpe.top-top.wcpe.mc-blue)](https://maven.wcpe.top/repository/maven-releases/)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**面向 Minecraft 插件的全平台端到端测试编排 Gradle 插件。**

用声明式 DSL 把真实服务端 / 代理拉起并连成测试拓扑，用 mineflayer 机器人驱动端到端场景，按结果文件判定并干净收尾——把各插件五花八门的 E2E 做法收敛成一套可复用工具。

```kotlin
mcTestkit {
    backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
    proxy("wf") { platform = waterfall; port = 25577; routesTo("s1") }
    scenario("buy") {
        backend = "s1"; via = "wf"
        bot { username = "Buyer"; action = "buy" }
    }
}
// ./gradlew e2eBuyViaWf
```

## 为什么

每个 Minecraft 插件都要自己起服、配代理、写机器人、收尾杀进程。做法五花八门，环境契约（代理协议版本、`paper-global`、BungeeCord 后端配置、数据源注入）在每个项目里重复踩坑。mc-testkit 把这些固化成插件内一处编排，消费方只声明拓扑和场景。

## 特性

**拓扑与编排**
- 声明式 `mcTestkit { }` DSL：单后端、经代理、多后端集群、持续压测、单场景多 bot
- 自动注册 prepare / 启动 bot / runServer / proxy / cluster / stress / verify / 缓存回写任务
- 配置期中文报错（拓扑不合法 / 路由缺失 / 端口冲突等）

**真实环境**
- 内置下载并运行 Paper / Folia / Spigot 与 Velocity / Waterfall / BungeeCord（自实现，不外挂第三方下载库）
- Paper 代表版本 1.7.10 – 1.21.1，含版本感知配置与 Java 运行时选择
- Velocity modern forwarding、集群 `/server` 切换、崩溃接管 fallback
- 持久手测 `serve`：同一拓扑挂起供真人客户端连入，Ctrl+C / `stop<Key>Serve` 三重收尾

**机器人与判定**
- mineflayer 机器人驱动场景；结果文件为唯一权威（PASS/FAIL）
- 固化环境契约：`server.properties`、代理 YAML 深合并、经代理固定 bot 协议版本
- 每后端身份注入（`MC_TESTKIT_E2E_BACKEND_NAME`），便于 per-backend 派生 `server-id`

**工程化**
- 桩插件骨架 + 机器人内核脚手架（`template/`，拷贝即用）
- 共享协议胶水：`harness-core`（Maven）+ `@wcpe/mc-testkit-bot`（npm）
- 兼容 Gradle `--configuration-cache` 与 `--build-cache`
- 自测模式：被测插件就是本模块时可零样板接线

## 支持的平台

| 角色 | 平台 | 说明 |
|---|---|---|
| 后端 | Paper / Folia / Spigot | Paper/Folia 覆盖 1.7.10–1.21.1 代表版本 |
| 代理 | Velocity / Waterfall / BungeeCord | 含 Velocity modern forwarding；压测不支持 Velocity（单端口） |
| 机器人 | mineflayer（Node.js ≥ 18） | 1.7.10 不支持 bot（仅验服务端拉起） |

不在范围内：Bukkit / Sponge。

## 快速开始

### 环境要求

| 组件 | 要求 |
|---|---|
| JDK | 8+（运行服务端需匹配对应 MC 版本；模板 harness 字节码为 Java 8） |
| Node.js | ≥ 18（mineflayer 机器人） |
| 网络 | 首次运行需下载服务端/代理 jar（可缓存或用 `MC_TESTKIT_E2E_*_JAR` 覆盖） |
| Gradle | 8.x / 9.x（兼容配置缓存与构建缓存） |

### 1. 声明插件仓库

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
}
```

### 2. 应用插件并声明拓扑

`build.gradle.kts`：

```kotlin
plugins {
    id("top.wcpe.mc-testkit") version "0.12.0"
}

mcTestkit {
    backend("s1") {
        platform = paper; version = "1.20.1"; port = 25565
        // 服务端 jar 也可来自 Maven 坐标（优先级：*_JAR 覆盖 > 本坐标 > 内置下载）
        // mavenServer("io.papermc.paper:paper:1.12.2")
    }
    proxy("wf") { platform = waterfall; port = 25577; routesTo("s1") }
    scenario("buy") {
        backend = "s1"; via = "wf"
        bot { username = "Buyer"; action = "buy" }
    }
    dependencies {
        // 环境变量名或 jar 路径；被测插件就是本模块时可省略（自测模式自动接线 jar）
        pluginUnderTest = "MY_PLUGIN_JAR"
        // 或直接写 Maven 坐标：框架按 Gradle 原生依赖解析拉取（仓库用你当前生效的仓库，见下方提示）
        mavenPlugin("com.example:foo-plugin:1.2.0")
    }
}
```

> 用私有仓库的坐标时，仓库要写在**生效的那一层**：消费方若启用 `RepositoriesMode.PREFER_SETTINGS`（如 ServerProbe），项目级 `repositories { }` 会被忽略，只有 `settings.gradle.kts` 里的仓库生效。凭据由你自己的 Gradle 配置提供，框架不感知。

### 3. 接入脚手架并运行

把 `template/` 拷进项目，按 [`template/README.md`](template/README.md) 接线，然后：

```bash
./gradlew e2eBuy          # 直连后端
./gradlew e2eBuyViaWf     # 经 Waterfall 代理
```

完整任务名、环境变量（`MC_TESTKIT_E2E_*`）与 DSL 说明见 [`docs/API.md`](docs/API.md)。

## 架构

三层协作，详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：

1. **Gradle 编排插件**（本仓库）——拓扑 DSL、任务装配、下载运行、环境契约、结果判定
2. **服务端桩插件**（随消费方项目）——装备玩家、驱动场景、写结果文件
3. **mineflayer 机器人**（随消费方项目）——模拟真实玩家入服驱动业务

`template/` 是纯拷贝脚手架，不被插件运行期依赖、不进发布产物。

## 仓库结构

```
mc-testkit/
  src/main/kotlin/top/wcpe/mc/testkit/   # 插件实现
  harness-core/                          # 桩侧协议胶水库（Maven）
  template/                              # 脚手架：桩插件 + bot 内核 + 示例
  docs/                                  # PRD / 架构 / API / ADR / 运维 / 贡献
```

## 文档

| 文档 | 内容 |
|---|---|
| [API](docs/API.md) | DSL、任务名、环境变量、结果文件契约 |
| [架构](docs/ARCHITECTURE.md) | 模块划分与机制 |
| [运维](docs/OPERATIONS.md) | 构建、发布、E2E 触发方式 |
| [贡献](docs/CONTRIBUTING.md) | 分支模型、文档同步、协作约定 |
| [变更日志](CHANGELOG.md) | 各版本变更 |
| [Releases](https://github.com/wcpe/mc-testkit/releases) | GitHub Release 说明 |

## 贡献

欢迎 Issue 与 PR。提交前请过验证门（`./gradlew build`），并同步受影响文档——约定见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)。

## 许可

[MIT](LICENSE)
