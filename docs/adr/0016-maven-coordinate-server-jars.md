# ADR-0016：服务端 / 代理 jar 支持按 Maven 坐标解析（含镜像缓存）

## 状态

已接受

## 背景

服务端 / 代理 jar 此前只有两条来源：内置下载（PaperMC API / Spigot Jenkins）与 env `*_JAR` 覆盖
（本机放文件 + 设环境变量）。当制品**不随公开仓库分发**、或需要**固定 / 自建构建**（例如从私有仓库
`repo.wcpe.top/repository/r3d` 取 `io.papermc.paper:paper:1.12.2`）时，本机没有文件、CI 也拉不到
PaperMC 的旧版本，`*_JAR` 逃生口要求人肉准备文件，CI 里因此跑不了。

依赖插件侧（`dependencies { mavenPlugin(...) }`）已在 ADR-0015 建立「交给 Gradle 原生依赖解析」的
模式；本 ADR 把它**对称扩展到服务端 / 代理 jar**（`backend/proxy { mavenServer(...) }`），并补上
缓存与 CI 缓存。

## 决策

`backend { }` / `proxy { }` 加法新增 `mavenServer(coordinate)` 声明。解析与来源裁决：

```
① env `*_JAR` 覆盖        （离线逃生口，最高优先，不求值 Maven 来源）
② mavenServer(坐标)       （命中镜像即复用；否则 Gradle 原生解析 → 镜像落盘）
③ 内置下载                （PaperMC API / Spigot Jenkins）
```

- **解析交给 Gradle 原生依赖解析**：不自己实现 POM 解析、传递依赖、仓库顺序与鉴权。坐标按
  `isTransitive = false` 只取声明的那个制品（服务端 jar 无传递依赖概念）。
- **镜像缓存**：坐标解析出的 jar 被**拷贝**（源属 Gradle 依赖缓存，不可移动）进
  `<gradleUserHome>/caches/mc-testkit-jars/maven/<group 路径>/<artifact>/<version>/<artifact>-<version>.jar`
  ——沿用 Maven 仓库自身层级（按**坐标**推导，与 DSL 的 `version` 字段无关）；落盘走同目录临时文件 +
  `ATOMIC_MOVE` 原子替换，避免半成品镜像被复用。
- **注册期分流（关键）**：注册期按坐标纯函数推导镜像路径并做一次 `isFile` 检查——
  - **命中** → 来源直接持该普通 `File`，**不创建任何 configuration**：该任务被调度时不触仓库、不产生
    配置缓存解析负担，可离线。
  - **未命中** → 才创建 detached configuration（解析时机落在配置缓存存储期，无法避免）。
- **CI**：`.github/workflows/e2e.yml` 为 `~/.gradle/caches/mc-testkit-jars/maven` 增加**显式**
  `actions/cache` 条目（独立 key），使该镜像子树不因其它 workflow 改动而整体失效。

## 理由

- **与依赖插件侧同构**（ADR-0015）：同一套「Gradle 原生解析 + 惰性 FileCollection + 限定捕获范围」
  机制复用，消费方心智一致，维护面小。
- **为什么仍保留内置下载**：内置下载是**默认体验**（零声明即可起服），且自带版本段选择、sha256 校验、
  PaperMC/Spigot 多源回退。Maven 是**显式逃生口**，不是替代品。
- **为什么镜像而不是只用 Gradle 的 `modules-2`**：`modules-2` 由 Gradle 管理，运维看不见也清不掉；
  镜像落在 mc-testkit 自己的缓存根下，**运维可辨识、可清理**，且 CI 能按独立 key 缓存。
  `setup-gradle` 虽默认缓存 `modules-2`，但那是整体 Gradle User Home 缓存的一部分，粒度粗、key 随
  作业变化。
- **为什么 `*_JAR` 优先级最高**：它是**已发布**的离线 / CI 逃生口（API.md §3.3），压在其上会破坏既有
  语义；且优先级越显式越该靠前——env 变量比 DSL 声明更"临时、更强制"。
- **为什么在注册期查镜像**：这是「命中即零仓库访问」的唯一实现方式——只要创建了 configuration，
  被调度任务的配置缓存存储期就会解析它。代价是配置期多一次文件存在性检查；其依据是镜像对任务属
  **未声明输入**，与既有 `*_JAR` 逃生口**行为一致**（执行期取值、不进配置缓存输入集）。这是刻意取舍，
  非疏漏；镜像被删时由 provisioner 在执行期以中文错误拦下，绝不静默用错文件。

## 后果

- **正面**：制品不随公开仓库分发的服务端 / 代理可经坐标声明，CI 可跑；镜像命中即零解析、可离线；
  仓库与凭据沿用消费方既有 Gradle 配置，框架不新增凭据面。
- **约束**：
  - 镜像与 Gradle `modules-2` 各存一份（大 jar 磁盘翻倍，如 Paper 约 47 MB）；可用既有
    `purgeE2eRuntimeCache` 语义清理，必要时后续补清理任务。
  - 不做传递依赖；不校验「坐标版本 vs `version` 字段」一致性（二者是不同维度：jar 真源 vs
    服务端行为配置）。
  - 捕获粒度是「**声明集合**」而非「本任务涉及的坐标」：起服类任务（e2e / 经代理 / 集群 / 压测 / serve）
    捕获的是依赖插件坐标 + **全部**服务端 / 代理坐标，故其被调度时全部已声明坐标都会解析（配置缓存
    存储期）——某个与本次任务无关的坐标拼错也会让构建失败。这属「快速失败」（错坐标本就该修），但
    实际作用域比直觉大，故在此写明。`prepareE2e<Key>` 只注入插件、不起服务端，故只捕获依赖插件坐标
    （`pluginsOnly()`）；完全不涉及坐标的任务（`stop<Key>Serve` / `syncE2eRuntimeCache` /
    `npmInstallBot`）一概不解析，也不触仓库。
  - 上述解析失败在配置缓存构建下表现为 Gradle 原生报错；中文诊断文案作用于非配置缓存构建。
- **不改动**：任务命名、env 前缀、控制协议、结果文件格式；内置下载与 `*_JAR` / `*_VERSION` 语义不变。

## 备选方案

- **扩展 `provision/` 的下载基建**（`JarProvisionService` / `Downloader`）去拉 Maven 制品：该基建面向
  「平台 + 版本」的服务端构件（接 PaperMC API / Spigot Jenkins），扩成通用 Maven 客户端要自己实现
  POM 解析、传递依赖、仓库顺序与鉴权，落选。
- **只用 Gradle `modules-2`、不做镜像**：省掉缓存代码，但制品位置运维不可见、CI 缓存粒度粗，且无法
  在镜像命中时短路解析配置，落选（维护者与消费方都需要可辨识的缓存位置）。
- **新增独立解析任务**（解析到中间目录、消费任务依赖它）：未调度该任务时不解析，但需要该 jar 时仍在
  存储期解析；代价是新增任务名与中间目录，与「不改任务命名」冲突，收益不抵成本，落选。
- **配置期直接解析**：报错最可控，但配置期即发网络请求、配置缓存每次都重解析，与硬约束冲突，落选。
