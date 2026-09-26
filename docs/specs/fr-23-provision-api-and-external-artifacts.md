# 功能规格：开放下载/运行基建为公开 API + 声明式外部制品源

> 状态：已交付@v0.12.0　·　关联 PRD：FR-23　·　关联决策：[ADR-0017](../adr/0017-provision-api-and-external-artifact-sources.md)

## 1. 背景与目标

mc-testkit 至今的消费模型是**整体编排**：消费方声明拓扑与场景，框架把「下载 → 起服 → 跑 bot → 判定 → 收尾」全包。但有一类消费方用不上它——AllinCore 的真服验收要驱动真实 loom 客户端 + 代理集群 + 加密产物交接，有自己的编排层（Gradle `BuildService` 形态：长期持有进程、`ensureStarted()` 幂等、就绪后经 stdin 注入命令）。

这类消费方的**下载与起服**层与框架高度重复，却够不着：`Downloader` / `PaperDownloadsApi` / `WaterfallModuleProvisioner` 等是 Kotlin `internal`。此外，第三方插件注入（Hangar 固定版本、eCloud 动态解析）需要平台枚举之外的制品来源。

**目标**：让消费方可**只复用底层、自建编排**，并让第三方制品来源可声明式表达。

**范围内**：开放 provision 稳定能力为公开 API；新增声明式外部制品源（固定 URL / API 动态解析）。

**范围外**（守 PRD 非目标与 ADR-0001 精简原则）：真实游戏客户端驱动、Fabric/Forge 平台、GameTest 套件判定、完整插件市场客户端（市场 API / 搜索 / 版本列表 / 鉴权管理）、依赖传递解析。

## 2. 需求（要什么）

- 消费方能在自己的 Gradle 构建逻辑里 import 并使用框架的下载 / 起服能力，**不改框架代码**。
- 公开类型须能被消费方在任务动作 / `BuildService` 参数中使用——即**可序列化友好**（AllinCore 启用严格配置缓存 `problems=fail`）。
- 外部制品源支持两种形态：地址固定的（一次性给定 URL）与地址须查 API 的（先取响应文本，再解析地址）。
- 外部制品下载后**缓存复用**：命中缓存不发网络（离线 / 弱网复验不被外网波动阻塞）。
- 支持可选 sha256 校验；缓存命中时亦重新核对，防手工替换后误用。
- 既有 DSL / 任务名 / env 前缀 / 控制协议 / 结果文件格式 / `*_JAR` 语义**完全不变**。

## 3. 设计（怎么做）

决策正文见 [ADR-0017](../adr/0017-provision-api-and-external-artifact-sources.md)，此处只记落地面。

**公开面**（去掉 `internal`）：

| 类型 | 文件 |
|---|---|
| `Downloader`（含 `download` / `fetchText`） | `provision/Downloader.kt` |
| `PaperDownloadsApi`、`PaperDownload` | `provision/PaperDownloadsApi.kt` |
| `WaterfallModuleProvisioner` | `provision/WaterfallModuleProvisioner.kt` |
| `File.sha256()` | `provision/Hashing.kt` |

已 public 的 `ServerJarProvisioner` / `ServerLauncher` / `JavaRuntimeSelector` 不动。

**仍留 internal**：`ProvisionPlatform`（源码已注明不对外暴露，DSL 侧有 `dsl/Platforms`）、`JarProvisionService` / `JarCache`（签名依赖 `ProvisionPlatform`，而对外能力已由 `ServerJarProvisioner` 以 `String` 平台完整覆盖）、`JsonLite` / `BungeeCordJenkinsApi` / `PROVISION_USER_AGENT`。

**新增类型**（`provision/ExternalArtifactProvisioner.kt`）：

- `ExternalArtifactSource`（sealed）：`id`（缓存段）/ `fileName` / `expectedSha256?`
  - `FixedUrl`：`url`
  - `ApiResolved`：`apiUrl` + `resolver`
- `ArtifactUrlResolver`（`fun interface` + `Serializable`）：响应文本 → 下载地址；便利实现 `lastUrlMatch(regex)`
- `ExternalArtifactProvisioner(cacheRoot)`：`cacheFile(source)` 纯函数推导 + `provision(source, logger)` 下载并缓存

缓存布局 `<cacheRoot>/external/<id>/<fileName>`，与 `<platform>/`、`maven/` 并列。落盘走同目录临时文件 + `ATOMIC_MOVE`（并发读者只见「无文件」或「完整文件」）。

**可序列化是本设计的硬约束**：Kotlin SAM 转换产出的 lambda **不是** `Serializable`，故 `lastUrlMatch` 用具名类 `LastUrlMatchResolver` 实现，而非 lambda（与 `MavenServerJarSource` 同款约束，见 ADR-0016）。

## 4. 任务拆分

- [x] ADR-0017 + 架构不变量同步（受控例外登记）
- [x] 松绑公开面（4 处）
- [x] 新增外部制品源（`ExternalArtifactProvisioner.kt`）
- [x] 单测 `ExternalArtifactProvisionerTest`（9 例，零网络）
- [x] 消费方视角编译验证（独立工程 import 全部公开类型）
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG
- [ ] `./gradlew build` 全绿

## 5. 验收标准

- 独立消费方工程能 import 全部公开类型并编译通过（**已验证**：`/tmp/mctk-verify` 覆盖 8 类公开面 + 2 种外部源形态）。
- 外部制品源单测覆盖：缓存路径分层与隔离、命中缓存不发网络、哈希不符视为未命中、解析器取末项、无匹配时中文报错、解析器与来源均可序列化往返。
- `./gradlew build` 全绿（含 ktlint + TestKit）。
- **实机维度**（需用户确认）：AllinCore 侧接入后真服验收端到端跑通——沙箱网络无法完成 15MB+ 制品下载与起服，故该维度留待真机。

## 6. 风险 / 待定

- **公开面即契约**：一经发布，签名变更须按 SemVer 升 major。
- **消费方配置缓存**：公开类型若引入不可序列化字段会破坏消费方配置缓存（已在单测中锁定序列化往返）。
- **外部源不得演化成市场客户端**：新增源类型须先证明声明式表达不了，否则即镀金（已写入架构不变量）。
