# ADR-0013：把 Spigot 后端纳入平台范围（取代 ADR-0003）

## 状态

已接受（取代 [ADR-0003](0003-p1-platform-scope.md)）

## 背景

ADR-0003 把平台范围定为「后端 Paper / Folia + 三代理」，其中一条否决理由是 **Spigot/Bukkit 没有官方可直接下载的产物**（需 BuildTools 本地构建），因此与 Sponge 一起"不列入计划、不预留分支"。

事实后来变了：社区维护的 Spigot **固定版本构件**可以直接按版本号下载（GetBukkit 及其 GitHub 镜像），不再需要本地 BuildTools。代码已按此落地（`96325b9`）——`dsl/BackendPlatform` 含 `SPIGOT`、`provision/ProvisionPlatform.SPIGOT` 走多源回退、`JarProvisionService` 下载后记录来源 / 版本 / 本地 SHA-256，且带单测覆盖。但该提交未同步 ADR 与文档，形成「代码已支持、文档仍写不支持」的漂移。本 ADR 追认这一决策，并取代 ADR-0003 的平台范围条款。

## 决策

平台范围 = **后端 Paper / Folia / Spigot**、**代理 Velocity / Waterfall / BungeeCord**。**Bukkit / Sponge 仍不列入计划**（不实现、不预留分支）。

Spigot 走**受控公共构件源**，并沿用 FR-02 既有的缓存与校验约束：

- 首源 `download.getbukkit.org`，不可达时回退 GitHub 镜像；全部源失败抛中文错误，不静默降级。
- 无远端 sha256 → 下载后校验「结构合法 jar」，并把**实际来源 / 版本 / 本地 SHA-256** 写入缓存目录的 `source.properties`；命中缓存时重新核对这三项与当前文件哈希，防止手工替换后误用。
- 仍支持 `MC_TESTKIT_E2E_SPIGOT_JAR` 覆盖（存在即直接返回、不发网络），与其余平台同款离线 / CI 逃生口。

## 理由

- ADR-0003 的否决理由（需 BuildTools 本地构建）已不成立：有可直接下载的固定版本构件后，成本从「一个独立的构建工程」降到「一次多源下载 + 溯源」。
- 多源回退 + 溯源 + 本地哈希**不是新机制**——BungeeCord 早已用同款「无远端 hash 时校验结构合法 jar + 记录本地 hash」，Spigot 只是复用了既有能力并把它显式化，没有引入新依赖。
- Paper 向下兼容 Spigot / Bukkit 插件 API，但在真实分布里仍有只面向 Spigot 发布的被测插件；纳入 Spigot 能覆盖这部分主链路，成本却很低。

## 后果

**正面**

- 后端平台覆盖到 Spigot，`backend { platform = spigot }` 与其余后端走同一套下载 / 缓存 / 启动路径。
- 构件来源可追溯：出问题时能从 `source.properties` 查到是哪个源下载的、什么版本、hash 多少。

**负面 / 约束**

- Spigot 构件**不是官方一手产物**，来源可用性不由本项目控制；多源回退与溯源是对冲手段，不是保证。
- 无远端 sha256，**无法校验构件在上游是否被替换**——只能保证「下载到的东西是合法 jar，且之后没被本地改动」。这是本决策明确接受的残余风险，与 BungeeCord 的既有取舍一致。
- 依赖公共构件源意味着跑 Spigot 场景需联网（与 Paper 一致，无额外约束）；离线仍走 `SPIGOT_JAR` 覆盖。
- 范围纪律同步：`.claude/rules/scope-discipline.md`、PRD 非目标与各 FR 规格里「不支持 Spigot」的表述一并收敛为「不支持 Bukkit / Sponge」。

## 备选方案

- **维持 ADR-0003、删除 Spigot 实现**：能消除漂移，但会删掉一个已完整实现、有单测、带构件溯源的能力，且真实消费者可能已在用——落选。
- **连带把 Bukkit / Sponge 一起纳入**：Bukkit 没有等价的固定版本公共构件，Sponge 有独立的下载 / 运行方式，两者都仍需各自的额外工程，与「复用既有下载机制」这个前提不符——落选，保持不列入计划。
