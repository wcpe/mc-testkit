# ADR-0017：开放下载/运行基建为公开 API，并引入声明式外部制品源

## 状态

已接受（扩展 [ADR-0001](0001-gradle-plugin-and-self-provisioning.md) 的「内置自实现」边界、[ADR-0016](0016-maven-coordinate-server-jars.md) 的制品来源维度；**不取代**其决策正文——自实现、不外挂第三方下载库、按平台枚举下载这三条逐字保留）

## 背景

mc-testkit 至今的消费模型是**整体编排**：消费方声明拓扑与场景，框架把「下载 → 起服 → 跑 bot → 判定 → 收尾」全包。这套模型对标准 E2E 足够，但有一类消费方用不上它：

AllinCore 的真服验收（真实 loom 客户端连入 + 代理集群 + 加密产物交接）有自己的**编排层**——`PaperRealServerService` / `BungeeProxyService` 两个 Gradle `BuildService`，特征与框架的编排模型不同：

- 跨任务长期持有进程引用、`ensureStarted()` 幂等、`close()` 时强杀进程树；
- 服务端就绪后还要继续动作（经 stdin 注入控制台命令产出加密产物、再把产物交接到客户端 gameDir）；
- 客户端生命周期由 loom 的 run 任务驱动，框架当前不支持（PRD 非目标）。

但它的**下载与起服**这一层是平台无关的，与框架重复度极高：

| AllinCore 自研 | 框架已有 | 重复度 |
|---|---|---|
| `FillV3Downloads`（74 行，Fill v3 + 正则解析） | `PaperDownloadsApi`（正规 JSON + sha256 校验） | 高 |
| `resolvePaperJar` / `resolveCachedJar` | `JarProvisionService` + `JarCache`（含来源溯源） | 高 |
| `BungeeProxyService` 的 Waterfall + `cmd_server` 预置 | `WaterfallModuleProvisioner` | 极高（同一逻辑逐条对应） |
| 裸 `ProcessBuilder` 起服 | `ServerLauncher`（paperclip 识别 / thin jar classpath / 版本感知参数） | 高 |

问题是这些能力**够不着**：

1. **可见性**：`Downloader` / `PaperDownloadsApi` / `JarProvisionService` / `JarCache` / `WaterfallModuleProvisioner` 全部声明为 Kotlin `internal`，跨构建不可访问（实测编译器逐个报 `Cannot access ... it is internal in file`）。它们在字节码层其实已是 `public`，仅元数据标记挡住了消费方。
2. **来源维度的空缺**：框架的下载源是**按平台枚举封闭**的（`ProvisionPlatform` 六个平台）。而验收常需注入第三方插件：AllinCore 要从 Hangar 取固定版本（PlaceholderAPI）、从 eCloud API 动态解析最新版地址（Player 扩展）。这类「非平台枚举、需查 API 才知道地址」的制品，当前只能各自手写。

于是 AllinCore 手搓了一份等价实现——**框架本身就是为了复用而写**，这种重复正是它要消除的对象。

## 决策

两件事，加法、非破坏。

### 一、开放下载/运行基建为公开 API

把 `provision/` 中**稳定的下载与运行能力**提升为公开 API，供消费方在自有编排中复用：

| 公开的类型 | 职责 |
|---|---|
| `ServerJarProvisioner` | 平台 + 版本 → jar，含 `*_JAR` / Maven 坐标 / 内置下载三级裁决（`create()` 工厂已 public，本次保持） |
| `ServerLauncher` | 启停服务端 / 代理进程（已 public） |
| `JavaRuntimeSelector` | 按 MC 版本选 JRE（已 public） |
| `PaperDownloadsApi`、`PaperDownload` | PaperMC Fill v3 客户端与构建产物模型（本次开放） |
| `Downloader` | HTTP 下载 / 取文本（含 UA、重定向跟随、超时）（本次开放） |
| `WaterfallModuleProvisioner` | Waterfall `module:*` 预置（保 `/server` 可用）（本次开放） |
| `Hashing`（`File.sha256()`） | 下载完整性校验（本次开放） |

**仍留 `internal`**（公开面最小必要原则）：

- `ProvisionPlatform`：源码已注明「本枚举不对外暴露」——DSL 侧已有 `dsl/Platforms` 作对外平台枚举，本枚举只供内部解析下载源。公开它会连带泄露六平台的下载源细节。
- `JarProvisionService` / `JarCache`：其方法签名依赖 `ProvisionPlatform`，公开即须连带公开后者。而对外能力已由 `ServerJarProvisioner`（平台以 `String` 表达）完整覆盖，公开它们属重复暴露。消费方的通用下载需求由本 ADR 第二部分的外部制品源承担。
- `JsonLite`、`BungeeCordJenkinsApi`、`PROVISION_USER_AGENT` 等实现细节。

公开面一经发布即契约（ADR-0006）：**加法**演进；移除或改签名按 SemVer 升 major。

### 二、引入声明式「外部制品源」

在 `provision/` 新增外部制品源能力，覆盖平台枚举之外的制品。两种形态：

- **固定 URL**：声明 `url` + 目标文件名 + 可选期望 `sha256`。对应「制品地址稳定、版本由我钉死」的场景（如 Hangar 的固定版本下载端点）。
- **API 动态解析**：声明一个**纯函数 resolver**（响应文本 → 下载地址），对齐既有 `PaperDownloadsApi.parseDownload` 的风格——解析逻辑可喂固定样本文本单测、不打网络。对应「地址带内容哈希、随再上传而变，须先查 API」的场景。

复用既有 `Downloader` / `JarCache` / `Hashing`；缓存落点与 `<platform>/`、`maven/` 并列，运维可辨识、可清理。

**明确不做**（守 ADR-0001 的「保持精简」）：

- 不做完整插件市场客户端（不实现 Modrinth / Hangar / SpigotMC 的市场 API、搜索、版本列表）；
- 不做依赖传递解析（外部源只拉声明的那个制品）；
- 不做鉴权 / 凭据管理（需要鉴权的源由消费方经既有方式提供可达 URL）。

## 理由

- **复用的前提是可达**：能力已在框架内实现且有测试覆盖，重复实现纯属浪费。开放公开面**不新增实现**，只是去掉可见性标记——字节码层本就是 `public`，风险与成本都极低。
- **外部源收在「声明式」而非「市场客户端」**：AllinCore 需要的本质是「给我一个地址，我把它下下来并缓存好」。市场 API 的语义（搜索、版本漂移、鉴权、多平台映射）会把这个口子扩成无底洞，与 ADR-0001「不搬运插件市场下载」的取舍一致——那条取舍针对的是**市场语义**，不是「下载任意 URL」这个动作。
- **与 ADR-0016 同构**：ADR-0016 已确立「制品来源是独立维度」的思路（`mavenServer` 只决定 jar 从哪来，不影响 `version` 字段驱动的配置生成）。外部制品源是同一维度的第三条来源，概念上不新。
- **消费方是具体的**：本次有明确消费方（AllinCore 验收），不是为假想需求预留空壳（守 `.claude/rules/scope-discipline.md`）。

## 后果

- **正面**：
  - 消费方可只复用下载/起服、自建编排，不必被迫接受整体编排模型。
  - 第三方插件注入不再需要各自手写 API 解析 + 缓存 + 校验；获得 sha256 校验与来源溯源（`source.properties`）。
  - AllinCore 可删除自研下载层，改用有单测覆盖的实现。
- **约束（须长期遵守）**：
  - **公开面是契约**：改签名 / 移除须按 SemVer 升 major 并写迁移（ADR-0006）。
  - **公开面须保持可序列化友好**：这些类型会被消费方在 Gradle 任务动作 / `BuildService` 参数中使用；若引入不可序列化字段，会破坏消费方的配置缓存（AllinCore 启用 `problems=fail` 严格模式）。
  - **外部源不得演化成市场客户端**：新增源类型须先证明「声明式表达不了」，否则即为镀金。
  - 公开面扩大后，内部重构需兼顾外部使用——这是一次性的成本，换取复用收益。
- **不改动**：既有 DSL 形态、任务命名、env 前缀、控制协议、结果文件格式、`*_JAR` / `*_VERSION` 语义；内置下载的六个平台覆盖不变；不外挂第三方下载库（ADR-0001）不变。

## 备选方案

- **消费方直接调 `internal` 的字节码**（Kotlin 元数据绕过 / 反射）：可行但属滥用——框架把 `internal` 作为设计意图表达，绕过它等于把内部实现当契约，后续重构会静默破坏消费方。落选。
- **把 provision 层抽成独立发布的 API 构件**：边界更干净（消费方只依赖小构件），但要新增发布物、版本联动、CI 配置，而 mc-testkit 是「插件 + 契约」的单一构件形态（ADR-0001/0006）。当期收益不抵成本，落选；若公开面后续显著扩大可再议。
- **让消费方整体改用框架编排（放弃自研编排）**：这是「最彻底」的复用，但要求框架支持真实游戏客户端驱动——那是 PRD 明确的非目标，且客户端的生命周期与判定语义都不同，改动面远大于本 ADR。落选（属后续独立决策，本次范围外）。
- **只开放 provision、不做外部制品源**：AllinCore 仍要保留 Hangar / eCloud 两处自研解析，消重不彻底。落选。
- **为外部源做完整市场客户端**：见「理由」——会把口子扩成无底洞，违 ADR-0001 的「保持精简」。落选。
