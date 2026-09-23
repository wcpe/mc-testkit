# ADR-0015：依赖插件制品按 Maven 坐标解析（交给 Gradle 原生依赖解析）

## 状态

已接受

## 背景

消费方的 e2e 需要注入第三方插件 jar，但这些制品（如 ServerProbe 依赖的 MCE 闭包）不随公开仓库分发。此前 `dependencies { }` 的值**只支持环境变量名或路径**（`pluginUnderTest` / `plugin(...)`），于是只能「本机放文件 + 设环境变量」——CI 里没有那个文件，这类场景就跑不了。

需要新增一种声明：直接把 Maven 坐标写进 `dependencies { }`，框架自动解析到本地 jar 并注入后端运行目录的 `plugins/`。

两条既有约束框定了方案：

1. **配置缓存兼容是硬约束**（既有 `ConfigurationCacheFunctionalTest` 回归锚）。任务动作闭包绝不捕获 `Project`；且解析不得在配置期做——那既会让配置缓存失效，又会在配置期发网络请求。
2. **不复用 `provision/` 的下载基建**。`JarProvisionService` / `JarCache` / `Downloader` 是「平台 + 版本」下载**服务端 / 代理** jar 的（接 PaperMC API 与 Spigot Jenkins），不是通用依赖下载器；POM 解析、传递依赖、仓库顺序、鉴权都不该自己实现。

## 决策

**依赖插件制品一律交给 Gradle 原生依赖解析**，框架只做三件事：声明期校验坐标、注册期建惰性文件集合、执行期落成 jar 后复用既有注入管线。

- **DSL**：`DependenciesSpec` 加法新增 `mavenPlugin(coordinate)`，与既有 `plugin(...)` / `pluginUnderTest` 并列；配置期校验坐标须恰为 `group:artifact:version` 三段非空，并拒绝动态版本与版本区间（`+` / `latest.*` / `[1.0,2.0)`）——它们会让同一份声明在不同时刻拉到不同制品。`-SNAPSHOT` 放行（测试框架有正当用途），但不可复现。
- **注册期**：为每个坐标建一个 `detachedConfiguration`，仅纳入声明的那个制品（**不可传递**），聚成 `MavenCoordinateSources`（可序列化；ADR-0016 将其泛化为同时承载服务端 / 代理 jar 坐标）。
- **执行期**：把文件集合落成 `Map<坐标, File>`，注入既有的 `resolveDependencyJars`（纯函数 + 取值器风格不变），随后走既有 `ResolvedPluginJar` → `injectBackendDependencies` 路径，以 Maven 制品名落到 `plugins/`（`underTest = false`）。
- **仓库来源**：用消费方**当前生效**的仓库。框架不管理 repository 声明、不感知凭据（由消费方自己的 Gradle 配置提供），报错里也**不**建议消费方往项目级加仓库。

## 理由

- **不自己实现 POM 解析 / 传递依赖 / 仓库顺序 / 鉴权**：这些正是 Gradle 已经做对且消费方已经配好的东西。自己实现等于重新发明一遍，还要维护仓库优先级与凭据处理。
- **`isTransitive = false`**：插件运行期依赖应进**服务端的库目录**，而不是 `plugins/`；自动投放会放错位置。消费方如需运行库仍自行管理（ServerProbe 现在手工维护 MCE 闭包 8 条，本期不动）。这条同时让「一个坐标 = 一个 jar」成立，简化了注入与报错。
- **`RepositoriesMode.PREFER_SETTINGS` 的现实**：消费方（ServerProbe 即是）启用该模式时**项目级** repositories 被忽略、只有 **settings 级**生效。故框架既不往项目级加仓库，也不在报错里把「去项目级加仓库」当解法——那对这类消费方是错的指引。

### 配置缓存相关的取舍（关键）

**Gradle 原生依赖解析无法真正推迟到任务动作内**：实测（Gradle 8.9）凡把该文件集合纳入某个**被调度**任务的捕获图（闭包直接捕获、可序列化 holder、`Provider`、`objects.fileCollection().from(...)`、任务 `@InputFiles` / `@Internal` 属性），配置缓存**存储期**就会去解析它；只有**未被调度**的任务不解析。因此「解析惰性到任务执行期」与「不自己实现 POM 解析」不可兼得。

本 ADR 选择：**限定捕获范围**——`MavenCoordinateSources` 只传给真正需要注入依赖插件的任务注册点（prepare / e2e / 经代理 / 集群 / 压测 / serve），**不进**共享的 `TaskExecutionContext`。由此：

- 不需要依赖的任务（`stop<Key>Serve` / `syncE2eRuntimeCache` / `npmInstallBot` 等）**永不解析坐标**、永不发网络请求——「配置期不发网络请求」在这些路径上可被测试证否。
- 需要依赖的任务：解析发生在被调度后的配置缓存存储期（而非动作体内）。此时坐标解析失败是 Gradle 自己的英文报错；框架的中文文案作用于非配置缓存构建。

## 后果

- **正面**：消费方在 `dependencies { }` 里写坐标即可，无需本机放文件 + 设环境变量，CI 因而能跑这类场景；仓库与凭据沿用消费方既有 Gradle 配置，框架零新增凭据面。
- **约束**：
  - 只拉声明的那个制品，**不解析传递依赖**；需要运行库的消费方仍自行投放到服务端库目录。
  - 动态版本与版本区间被拒绝——需要此类语义的消费方仍可用 `plugin(...)` 走自己的解析。
  - 坐标解析在「需要依赖的任务被调度时」发生（见上），故其失败在配置缓存构建下表现为 Gradle 原生报错；中文诊断文案只在非配置缓存构建生效。
  - 框架不管理仓库，故**不会**替消费方补仓库或凭据；报错只做归因（坐标拼写 / 仓库未在 settings 声明 / 凭据缺失）。
- **不改动**：任务命名、env 前缀、控制协议、结果文件格式均不变；`pluginUnderTest` / `plugin(...)` 语义与优先级不变（纯加法，SemVer minor）。v1 **不做** `pluginUnderTest` 的坐标形式。

## 备选方案

- **复用 `provision/` 下载基建**：该基建面向「平台 + 版本」的服务端 / 代理 jar，接的是 PaperMC API 与 Spigot Jenkins，不通用；扩成通用依赖下载器要自己实现 POM 解析、传递依赖、仓库顺序与鉴权，落选。
- **新增独立解析任务**（解析到 `build/mc-testkit/injected-dependencies/`，消费任务依赖它并读普通文件）：未调度该任务时确实不解析，但同样无法避免「需要依赖时在存储期解析」；代价是新增任务名与中间目录、改动面更大（与「不改任务命名」「简单优先」相冲突），收益不抵成本。
- **配置期就解析并校验**：报错最可控、测试最好写，但明确放弃惰性——会让配置期发网络请求、配置缓存每次都要重解析，与硬约束冲突，落选。
