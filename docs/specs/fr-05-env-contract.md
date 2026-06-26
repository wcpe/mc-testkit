# 功能规格：固化环境契约

> 状态：开发中　·　关联 PRD：FR-05　·　分支：feature/fr-05-serverconfig

## 1. 背景与目标

各插件手搓 E2E 时，反复在同样的"经代理才会踩"的环境契约上摔跤：机器人经代理 ping 拿不到后端版本会挑过新协议被踢（"Outdated server"）；后端进 BungeeCord 模式要同时设 `spigot.yml settings.bungeecord` + `paper-global proxies.bungee-cord.online-mode` + `server.properties online-mode=false`，缺一不可；依赖库（数据源 / Redis 等）注入缺失时往往静默继续、到运行期才炸得莫名其妙。本规格把这些契约**固化在框架一处**（ADR-0004），让所有消费方默认拿到正确配置，改一次全项目生效。属第一期（MVP）FR-05。

本规格只产出「把后端运行目录配置调成可被测 / 经代理模式」的**框架级纯函数**与**注入缺失校验机制**；不注册任务（FR-04 整合器接线）、不起进程、不下载、不写业务配置模板。

## 2. 需求（要什么）

- 范围内（package `top.wcpe.mc.testkit.serverconfig`）：
  - **`server.properties` 编辑**：纯函数式读现有 properties → 改指定键 → 写回，**保留未涉及键**。涵盖端口、`online-mode`、`enforce-secure-profile`、`level-type`、`max-players`、`view-distance`、`simulation-distance`、`spawn-protection` 等可被测后端常调键；调用方按需传，不强行全写。
  - **BungeeCord 后端三件套**（经代理必需，一处固化）：
    1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（离线 bot 经离线代理进服）。
    2. `spigot.yml`：`settings.bungeecord: true`（接受代理转发握手）。
    3. `config/paper-global.yml`：`proxies.bungee-cord.online-mode: false`（按转发 UUID 处理）。
  - **Velocity modern forwarding 两件套**（经 Velocity 代理必需，与 BungeeCord 三件套**二选一**，后续补，见 ADR-0010）：
    1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（同 BungeeCord）。
    2. `config/paper-global.yml`：`proxies.velocity.{enabled=true, online-mode=false, secret=<共享>}`——secret 与代理 `forwarding.secret` 同值。**不写** `spigot.yml settings.bungeecord`（Velocity 与 BungeeCord 模式互斥）。
  - **经代理机器人协议版本固定规则**：一个纯函数 / 常量决策——「经代理时机器人 mineflayer 协议版本 = 后端 MC 版本」，返回该版本字符串供 FR-04 接线时喂给 FR-06 的 `BotConnection.version`（FR-05 只给规则，不起机器人）。
  - **依赖注入缺失校验**：通用助手——给定「必需注入项（名→是否已提供）」，缺失时抛**中文** `GradleException`，说明缺什么、怎么补（提示对应 `MC_TESTKIT_E2E_*_JAR` / `SERVER_TEMPLATE_DIR` 等逃生口）；不绑定具体依赖插件 / Redis 名（那是消费方声明的）。
- 不做（范围外）：
  - 不注册任何 Gradle 任务（FR-04）；不碰 `contract/` / `dsl/` / `model/` / `provision/` / `bot/` / `verify/` / 插件入口 / `build.gradle.kts`。
  - 不写消费方业务配置模板（业务场景 / 某依赖插件 / 具体插件配置）——只留通用服务端配置编辑。
  - 不下载 / 不起进程 / 不做拓扑解析。
  - 不实现 Spigot/Bukkit/Sponge（不在项目计划内）。
  - 真实经代理拉起后端、验证握手成功属 FR-08 实机维度，本规格不在 CI 跑真服。

## 3. 设计（怎么做）

落在新包 `top.wcpe.mc.testkit.serverconfig`（ARCHITECTURE §2 的 `serverconfig/` 条），纯函数优先、用临时目录可穷举测试：

- **`ServerProperties`**（`server.properties` 编辑）
  - `load(runDir)`：读运行目录下 `server.properties`（不存在返回空 `Properties`）。
  - `edit(runDir, overrides, comment)`：load → 把 `overrides`（`Map<String,String>`）逐键写入（已存在键被覆盖、未涉及键原样保留）→ `store` 写回（UTF-8）。返回写回后的 `Properties` 便于断言。
  - `port(runDir)`：读 `server-port`，缺省回退到 [McTestkitDefaults] 端口（无则 25565 常量）。
  - 仅依赖 JDK `Properties` / `File`，不耦合 Gradle。键名是 Minecraft `server.properties` 既定字面量（`server-port` / `online-mode` 等），由本包内常量集中声明。
- **`BackendBungeeCordConfig`**（BungeeCord 三件套）
  - `apply(runDir)`：依次落地三件套——`ServerProperties.edit` 写 `online-mode=false` + `enforce-secure-profile=false`；`spigot.yml` 开 `settings.bungeecord: true`；`config/paper-global.yml` 写 `proxies.bungee-cord.online-mode: false`。
  - YAML 用「文件已存在则按行 / 正则补丁、不存在则写最小片段」策略（Paper/Spigot 首启会合并补全其余默认项并保留本值），避免引第三方 YAML 库。写入字段遵循 config-files 规则（kebab-case + 中文注释，写最小片段时带中文说明头）。
- **`ProxyProtocolVersion`**（协议版本固定规则）
  - `forBackend(backendVersion)`：纯函数，经代理时返回 `backendVersion`（机器人 mineflayer 协议版本 = 后端 MC 版本）。FR-04 接线时把它喂给 `BotConnection.version` / env `MC_TESTKIT_E2E_BOT_VERSION`。
- **`DependencyInjections`**（注入缺失校验）
  - `requireAll(injections, hintEnvByName)`：给定 `injections: Map<String,Boolean>`（注入项名 → 是否已提供），任一为 `false` 即收集，全部缺失项汇总抛**中文** `GradleException`（列出缺哪些、怎么补，可选 `hintEnvByName` 给每项对应的环境变量逃生口提示）。全部齐全则静默返回。机制通用，不写死具体依赖名。

依赖方向：本包只依赖 `contract/`（如 [McTestkitDefaults] 缺省、[McTestkitEnv] 名做提示）、`model/`（如需 [ResolvedBackend] 取版本）与 JDK；配置期错误抛 `GradleException`（与 `model/` 一致），不反依赖消费项目 / `template/`。沿用 ADR-0004 既定决策，无需新 ADR。

## 4. 任务拆分

- [ ] 测试先行：`ServerProperties.edit` 保留未涉及键、改对涉及键。
- [ ] 测试先行：`BackendBungeeCordConfig.apply` 写后 `spigot.yml` / `paper-global.yml` / `server.properties` 三处值正确（含文件已存在 / 不存在两路径）。
- [ ] 测试先行：`DependencyInjections.requireAll` 缺失 → 中文错误（含缺项名）；齐全 → 不报错。
- [ ] 测试先行：`ProxyProtocolVersion.forBackend` 返回后端版本。
- [ ] 实现 `serverconfig/` 四个能力。
- [ ] 文档同步：PRD 状态、ARCHITECTURE（serverconfig 条核对）、CHANGELOG。

## 5. 验收标准

- 新增单测红 → 绿；`./gradlew build` 全绿（validatePlugins + 全部测试不回归）。
- `server.properties` 编辑：写指定键后，未涉及的既有键原样保留、涉及键值正确。
- BungeeCord 三件套：对临时 runDir 应用后，`spigot.yml settings.bungeecord=true`、`config/paper-global.yml proxies.bungee-cord.online-mode=false`、`server.properties online-mode=false` 三处值均正确（文件预先存在与不存在两种情形都对）。
- 注入校验：缺失时抛**中文** `GradleException` 且文案含缺项名与补法；齐全时不抛。
- 协议版本固定：`forBackend("1.20.1")` 返回 `"1.20.1"`。
- **实机维度（需用户在 FR-08 备齐服务端 / 代理 / 依赖环境确认）**：真实经代理拉起后端、机器人按固定协议版本握手成功、依赖注入齐全时正常启动——单测不替代，标「待 FR-08 实机验」。

## 6. 风险 / 待定

- `paper-global.yml` / `spigot.yml` 的键路径随 Paper / Spigot 上游版本可能微调；本包用「按行补丁 + 最小片段兜底」尽量稳健，随上游变化属维护者职责（ADR-0004 已记，集中一处）。
- `Properties.store` 会写一行时间戳注释且不保证键序；本包不依赖键序、断言只看键值，行为等价于参考实现。
