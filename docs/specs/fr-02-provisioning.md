# 功能规格：内置服务端/代理下载与运行

> 状态：开发中　·　关联 PRD：FR-02　·　分支：feature/fr-02-provision

## 1. 背景与目标

E2E 编排要先把真实「代理 + 后端」拉起来：下载对应平台 / 版本的服务端或代理 jar、缓存复用、以子进程运行。本规格落地这件事的下载与运行原语，属第一期（MVP）FR-02。

**关键决策（ADR-0001：自实现下载/运行）**：mc-testkit **在插件内自实现**下载与运行，**不外挂第三方下载库**。原因：外部下载库未必覆盖 BungeeCord（无 BungeeCord 下载 API），而 P1 平台范围（ADR-0003）要 BungeeCord；自含下载避免依赖外部制品的发布 / 版本联动负担，可移植性（NFR）更强。下载核心由维护者自实现，覆盖 `paperapi/` / `bungeecordapi/` / `util/Downloader` / `service/Downloads*` 这些下载 / 缓存 / 启动能力（见 §3、ADR-0001）。

## 2. 需求（要什么）

- 范围内：
  - **下载 + 缓存**：Paper / Folia / Velocity / Waterfall 经 PaperMC 下载服务 Fill v3（`fill.papermc.io` / `fill-data.papermc.io`）；BungeeCord 经 SpigotMC Jenkins（`hub.spigotmc.org/jenkins`）。按 平台 / 版本 / 构建号 缓存到持久缓存目录；hash 校验复用（PaperMC 给 sha256 则校验远端 hash，BungeeCord 无远端校验则校验为结构合法 jar 并记录本地 hash 防本地损坏）。
  - **jar 解析**：给定 平台 + 版本，返回 jar `File`。**优先** [McTestkitEnv] 的 `*_JAR` 覆盖——存在即直接返回该路径、**不发网络**（离线 / CI 逃生口）；版本可由 `*_VERSION` 覆盖（缺省取 [McTestkitDefaults.MINECRAFT_VERSION] / 平台缺省）。
  - **启动助手（精简）**：给定 jar + 运行目录 + JVM 参数，以子进程启动 server / proxy，返回 `Process`；pid 落盘供收尾（复用 FR-06 `bot/` 的 pid 收尾思路，但**不改 `bot/` 包**）。
- 不做（范围外）：
  - **不外挂第三方下载库**（架构红线，ADR-0001）。
  - **不搬运**插件市场下载（Modrinth / Hangar / GitHub / Url）——依赖插件经 `mcTestkit { dependencies { } }` 注入，不需要（镀金红线）。
  - **不注册任何 Gradle 任务**（FR-04 整合器负责把本包接成 prepare / runServer / proxy / cluster 等任务并接线收尾）；尽量不改 `McTestkitPlugin`。
  - **不做** Spigot / Bukkit / Sponge（不在项目计划内，ADR-0003）。
  - 不做拓扑解析（FR-03）、环境契约写盘（FR-05）、机器人（FR-06）、结果判定（verify/）。
  - 真实下载 / 起服属 FR-08 实机维度，CI 单测不打网络。

## 3. 设计（怎么做）

落在新包 `top.wcpe.mc.testkit.provision`（ARCHITECTURE §2 的 `provision/` 条）。下载核心**由维护者自实现**，不引 Gradle BuildService / Jackson 等重型依赖，改为**纯 JDK**（`java.net.HttpURLConnection` / `MessageDigest` / `JarFile`）+ 手写极简 JSON 解析，保持最小依赖（不引第三方 JSON / HTTP 库），与 FR-06 同样 JDK-only 风格。

- **平台映射** `ProvisionPlatform`（内部）：P1 五平台 → PaperMC project 名（`paper`/`folia`/`velocity`/`waterfall`）或 BungeeCord 标记 + env 名（`*_JAR`/`*_VERSION`）。**不新增对外平台枚举**（DSL 已有 `dsl/Platforms`，本包内部用）。
- **JSON 解析** `JsonLite`（自实现，免引 Jackson）：手写递归下降解析器，够解析 PaperMC / Jenkins 的小响应（对象 / 数组 / 字符串 / 数 / 布尔 / null）。纯函数、可喂固定文本穷举单测。
- **PaperMC API** `PaperDownloadsApi`（自实现）：解析 `projects/<p>/versions/<v>/builds` 取构建列表、`projects/<p>/versions/<v>/builds/<b>` 取 `server:default` 下载名 + sha256 + 对象存储 URL。HTTP 取文本与解析分离，解析逻辑纯函数可单测。
- **Jenkins API** `BungeeCordJenkinsApi`（自实现）：取 `lastSuccessfulBuild` 构建号、拼 artifact 下载 URL。
- **下载工具** `Downloader`（自实现）+ `Hashing`（自实现）：HTTP 下载到临时文件、sha256 校验。
- **缓存键 / 路径** `JarCache`（自实现缓存布局）：`<cacheRoot>/<platform>/<version>/<build>.jar` 路径推导（纯函数）；命中且 hash 一致即复用，否则下载。
- **jar 解析** `ServerJarProvisioner`：编排「env `*_JAR` 覆盖 → 直接返回；否则 env `*_VERSION` / 缺省定版本 → 经对应 API 解析构建 → 缓存命中复用 / 下载」。env 取值经注入的 `(name)->String?` 取值器（纯函数边界，便于「设了 `*_JAR` 就不发网络」单测），不耦合 Gradle `Project`。
- **启动助手** `ServerLauncher`（自实现，用 `ProcessBuilder`）：用 `java.home` 的 `java` 可执行 + JVM 参数 + jar（Paper 用 `-jar`，代理同 `-jar`）在运行目录后台启动，返回 `Process`；pid 落盘（复用 FR-06 同款 pid 文件思路，本包自带 `provisionPidFile`，不改 `bot/`）。

依赖方向：本包只依赖 `contract/`（env 名 / 缺省版本）与 JDK；不反依赖消费项目 / `template/`；不外挂第三方下载库、不引第三方 JSON / HTTP。下载 / 运行核心全部由维护者自实现，整包随 mc-testkit 本体以 MIT 发布。

## 4. 任务拆分

- [ ] 测试先行：JSON 解析（喂固定 PaperMC / Jenkins 响应文本）。
- [ ] 测试先行：PaperMC 构建解析 / 下载 URL 拼接、Jenkins 构建解析 / URL（喂固定文本，不打网络）。
- [ ] 测试先行：缓存键 / 路径推导（纯函数）。
- [ ] 测试先行：jar 解析——设 `*_JAR` 时返回覆盖路径且**不发网络**；`*_VERSION` 覆盖被采纳。
- [ ] 实现 `provision/`（平台映射 / JSON / 两 API / 下载 / 缓存 / jar 解析 / 启动助手）。
- [ ] 文档同步：ARCHITECTURE `provision/` 条核对、CHANGELOG 未发布段追加一行。

## 5. 验收标准

- 新增单测红 → 绿；`./gradlew build` 全绿（validatePlugins + 全部测试，FR-01/03/06 既有测试不回归）。
- jar 解析：设某 `*_JAR` 环境变量时返回该覆盖路径且**全程不发网络**；`*_VERSION` 覆盖被采纳。
- JSON / 构建 / URL 解析：对固定样本文本解析出正确构建号 / 下载名 / sha256 / 下载 URL。
- 缓存路径：按 平台 / 版本 / 构建号推导稳定、不写死本机绝对路径（缓存根由调用方注入）。
- **实机维度（需用户在 FR-08 备齐网络 / JDK 环境确认）**：真实经 PaperMC / Jenkins 下载五平台 jar、缓存复用、子进程起服 / 起代理成功——单测不打网络、不替代，标「待 FR-08 实机验」。

## 6. 风险 / 待定

- PaperMC Fill v3 对 User-Agent 与响应结构有契约要求；适配集中在 `PaperDownloadsApi` / `Downloader`，后续下载服务变更时只需改本包一处。
- BungeeCord Jenkins 无远端 sha256 与下载产物匹配，只能校验"结构合法 jar"+ 记录本地 hash 防本地损坏（本包既定取舍）。
- 启动助手本期只提供「起一个进程返回 Process + pid 落盘」；前台被测后端自停驱动后台收尾、就绪时序、集群批量回收等编排与收尾接线属 FR-04，本包不做。
- 真实下载 / 起服只能 FR-08 实机验，单测不打网络（诚实标注）。
