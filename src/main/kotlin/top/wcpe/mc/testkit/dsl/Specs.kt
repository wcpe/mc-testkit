package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.contract.McTestkitDefaults

/**
 * 后端节点声明。
 *
 * 插件骨架 只承载「声明值」；端口推导、平台落地为下载/运行任务等行为属 拓扑 DSL/内置下载与运行/任务自动编排。
 */
@McTestkitDsl
class BackendSpec(val name: String) {
    /** 后端平台（默认 paper）。 */
    var platform: BackendPlatform = BackendPlatform.PAPER

    /** Minecraft 版本（默认 [McTestkitDefaults.MINECRAFT_VERSION]）。 */
    var version: String = McTestkitDefaults.MINECRAFT_VERSION

    /**
     * 后端进程需要的 Java 主版本；null 表示沿用「按 MC 版本选运行时」的既有解析链
     * （`MC_TESTKIT_JAVA_HOME_<版本段>` > `JAVA_HOME` > 当前 JVM）。
     *
     * 显式声明后走强制路径：必须由 `MC_TESTKIT_JAVA_HOME_<主版本>` 精确提供，不允许回退——
     * 用于低版本服务端（如 1.16.5 的 patcher 拒绝 Java 17+）在_plugin 运行于新 JVM 时锁定旧 JRE。
     */
    var javaVersion: Int? = null

    /** 监听端口；null 表示留待拓扑解析（拓扑 DSL）按端口基数推导。 */
    var port: Int? = null

    private val mutableEnvironment = LinkedHashMap<String, String>()
    private val mutableJvmArgs = mutableListOf<String>()
    private val mutableJavaAgents = mutableListOf<String>()
    private var mutableTemplateDirectory: String? = null
    private var mutableMavenServer: String? = null

    /** 注入该后端进程的节点环境变量（后声明同名值覆盖先声明值）。 */
    val environment: Map<String, String> get() = mutableEnvironment.toMap()

    /** 追加到该后端 JVM 的参数（按声明顺序）。 */
    val jvmArgs: List<String> get() = mutableJvmArgs.toList()

    /** 该后端 Java agent 的原始声明（环境变量名或路径，运行期解析）。 */
    val javaAgents: List<String> get() = mutableJavaAgents.toList()

    /** 该后端模板目录的原始声明（环境变量名或路径）；null 表示回退旧全局模板环境变量。 */
    val templateDirectoryDeclaration: String? get() = mutableTemplateDirectory

    /** 该后端服务端 jar 的 Maven 坐标声明；null 表示走内置下载。 */
    val mavenServer: String? get() = mutableMavenServer

    /**
     * 声明该后端的服务端 jar 来自 **Maven 坐标** `group:artifact:version`（框架按 Gradle 原生依赖解析拉取）。
     *
     * 用于制品不随公开仓库分发、或需要固定构建（如自建 / 私有仓库里的服务端）的场景——不必再在本机
     * 放文件 + 设 `MC_TESTKIT_E2E_<平台>_JAR`。解析优先级：env `*_JAR` 覆盖 > 本坐标 > 内置下载；坐标
     * 解析出的 jar 会**镜像**进 `<mc-testkit-jars>/maven/...`，下次直接命中镜像、不再触发解析。
     *
     * 坐标版本与 [version] 字段互不干涉：[version] 仍驱动服务端配置生成与 Java 运行时选择，本坐标只决定
     * 「jar 从哪来」。
     */
    fun mavenServer(coordinate: String) {
        mutableMavenServer = MavenCoordinate.requireValid(coordinate)
    }

    /** 声明一个仅注入该后端进程的字面量环境变量。 */
    fun env(name: String, value: String) {
        mutableEnvironment[name] = value
    }

    /** 追加一个该后端专属的 JVM 参数。 */
    fun jvmArg(value: String) {
        mutableJvmArgs += value
    }

    /** 声明一个该后端专属 Java agent（环境变量名或路径，运行期解析）。 */
    fun javaAgent(envOrPath: String) {
        mutableJavaAgents += envOrPath
    }

    /** 声明该后端的模板目录（环境变量名或路径，运行期解析）。 */
    fun templateDirectory(envOrPath: String) {
        mutableTemplateDirectory = envOrPath
    }

    // DSL 便捷量：consumer 可直接写 `platform = paper`，无需 import 枚举
    val paper: BackendPlatform get() = BackendPlatform.PAPER
    val folia: BackendPlatform get() = BackendPlatform.FOLIA
    val spigot: BackendPlatform get() = BackendPlatform.SPIGOT
}

/**
 * 代理节点声明（含到后端的路由）。
 */
@McTestkitDsl
class ProxySpec(val name: String) {
    /** 代理平台（默认 waterfall）。 */
    var platform: ProxyPlatform = ProxyPlatform.WATERFALL

    /** 代理软件版本；null 表示沿用平台默认版本策略。 */
    var version: String? = null

    /** 代理进程需要的 Java 主版本；null 表示沿用当前 JVM。 */
    var javaVersion: Int? = null

    /** 监听端口；null 表示留待拓扑解析推导。 */
    var port: Int? = null

    private val mutableRoutes = mutableListOf<String>()
    private val mutablePlugins = mutableListOf<String>()
    private val mutableEnvironment = LinkedHashMap<String, String>()
    private val mutableJvmArgs = mutableListOf<String>()
    private val mutableJavaAgents = mutableListOf<String>()
    private var mutableTemplateDirectory: String? = null
    private var mutableMavenServer: String? = null

    /** 该代理转发到的后端名（按声明顺序，路由目标存在性由 拓扑 DSL 配置期校验）。 */
    val routes: List<String> get() = mutableRoutes.toList()

    /** 该代理专属插件 jar 的原始声明（环境变量名或路径，按声明顺序）。 */
    val plugins: List<String> get() = mutablePlugins.toList()

    /** 注入该代理进程的节点环境变量（后声明同名值覆盖先声明值）。 */
    val environment: Map<String, String> get() = mutableEnvironment.toMap()

    /** 追加到该代理 JVM 的参数（按声明顺序）。 */
    val jvmArgs: List<String> get() = mutableJvmArgs.toList()

    /** 该代理 Java agent 的原始声明（环境变量名或路径，运行期解析）。 */
    val javaAgents: List<String> get() = mutableJavaAgents.toList()

    /** 该代理模板目录的原始声明（环境变量名或路径）。 */
    val templateDirectoryDeclaration: String? get() = mutableTemplateDirectory

    /** 该代理软件 jar 的 Maven 坐标声明；null 表示走内置下载。 */
    val mavenServer: String? get() = mutableMavenServer

    /**
     * 声明该代理的软件 jar 来自 **Maven 坐标** `group:artifact:version`（框架按 Gradle 原生依赖解析拉取）。
     *
     * 与 [BackendSpec.mavenServer] 对称：用于制品不随公开仓库分发、或需要固定构建（如自建代理构建）的场景。
     * 解析优先级：env `*_JAR` 覆盖 > 本坐标 > 内置下载；坐标 jar 会**镜像**进
     * `<mc-testkit-jars>/maven/...`，下次直接命中镜像、不再触发解析。
     */
    fun mavenServer(coordinate: String) {
        mutableMavenServer = MavenCoordinate.requireValid(coordinate)
    }

    /** 声明该代理转发到的后端（按 [BackendSpec.name] 引用）。 */
    fun routesTo(vararg backendNames: String) {
        mutableRoutes += backendNames
    }

    /** 声明一个仅注入该代理节点的插件 jar（环境变量名或路径，运行期解析）。 */
    fun plugin(envOrPath: String) {
        mutablePlugins += envOrPath
    }

    /** 声明一个仅注入该代理进程的字面量环境变量。 */
    fun env(name: String, value: String) {
        mutableEnvironment[name] = value
    }

    /** 追加一个该代理专属的 JVM 参数。 */
    fun jvmArg(value: String) {
        mutableJvmArgs += value
    }

    /** 声明一个该代理专属 Java agent（环境变量名或路径，运行期解析）。 */
    fun javaAgent(envOrPath: String) {
        mutableJavaAgents += envOrPath
    }

    /** 声明该代理的模板目录（环境变量名或路径，运行期解析）。 */
    fun templateDirectory(envOrPath: String) {
        mutableTemplateDirectory = envOrPath
    }

    val velocity: ProxyPlatform get() = ProxyPlatform.VELOCITY
    val waterfall: ProxyPlatform get() = ProxyPlatform.WATERFALL
    val bungeecord: ProxyPlatform get() = ProxyPlatform.BUNGEECORD
}

/**
 * 机器人驱动声明（场景内可选；一个场景可声明多个，单场景多 bot）。
 *
 * 实现 [java.io.Serializable]：注册期声明定形后可被任务动作闭包安全捕获（配置缓存要求动作捕获图
 * 可序列化，不得含 `Project`）。
 *
 * @property role 角色标签（`bot("admin") { }` 的名字；匿名 `bot { }` 为 null）。同场景声明多个 bot 时
 *   须各有唯一 role 以区分（异质角色 / 多进程的日志·pid·username 基名），见 ADR-0009。
 */
@McTestkitDsl
class BotSpec(val role: String? = null) : java.io.Serializable {
    /** 机器人用户名。 */
    var username: String? = null

    /** 控制动作 / 场景 id（与桩、机器人侧约定一致）。 */
    var action: String? = null

    /**
     * 同质复制份数（单场景多 bot）：>1 表示把本 bot 复制 N 份，各唯一 username（基名 + 序号）、经 `BOT_INDEX`
     * （1..N）区分，都用同一 action / env。默认 1（单进程）。压测场景禁用（规模用 `stress { botsPerServer }`
     * 表达）。
     *
     * 注意：派生 username = `<基名><序号>` 须满足 Minecraft 离线名约束（≤16 字符、仅 `[A-Za-z0-9_]`），
     * 故 `count` 较大时请用**短基名**（如 `username = "P"` → `P1`..`P99`），否则 bot 会因用户名非法而连不上。
     */
    var count: Int = 1

    private val mutableEnv = LinkedHashMap<String, String>()

    /** 透传给机器人进程的**业务特定**环境变量（如商店标题/槽位/期望奖励等，消费方自定名）。 */
    val env: Map<String, String> get() = mutableEnv.toMap()

    /**
     * 声明一个透传给机器人进程的业务环境变量。
     *
     * 框架契约只固化通用连接/超时 env（[top.wcpe.mc.testkit.contract.McTestkitEnv]）；
     * 业务维度（商店标题、槽位、期望奖励等）天然项目特定，经此**原样透传**给消费方自带的机器人，
     * 不进框架契约（scope-discipline）。
     */
    fun env(name: String, value: String) {
        mutableEnv[name] = value
    }
}

/**
 * 压测维度声明（场景内可选，压测编排）。声明 `stress` 即「压测场景」（ADR-0008）：复用
 * [ScenarioSpec.backends] 表 N 服，每服起 [botsPerServer] 个 bot 进程**钉在本服**持续随机施压
 * （不 `/server` 切换，区别于集群跨服场景）。规模与时长在配置期校验须为正。
 */
@McTestkitDsl
class StressSpec : java.io.Serializable {
    /** 每个后端起多少个 bot 进程（钉本服持续施压）；必填 >0（配置期校验）。 */
    var botsPerServer: Int = 0

    /** 持续压测时长（秒）；必填 >0（配置期校验）。bot 据此跑循环。 */
    var durationSeconds: Long = 0

    /** 随机种子；各 bot 用 `seed xor botIndex` 播种使行为可复现且互异。默认 [McTestkitDefaults.STRESS_RANDOM_SEED]。 */
    var randomSeed: Long = McTestkitDefaults.STRESS_RANDOM_SEED
}

/**
 * 端到端场景声明。
 *
 * 实现 [java.io.Serializable]：注册期声明定形后可被任务动作闭包安全捕获（配置缓存要求动作捕获图
 * 可序列化，不得含 `Project`）。
 */
@McTestkitDsl
class ScenarioSpec(val name: String) : java.io.Serializable {
    /** 运行于哪个后端（按名称引用；null 表示默认 / 单后端）。与 [backends] 互斥。 */
    var backend: String? = null

    /** 经哪个代理（按名称引用；null 表示直连后端）。 */
    var via: String? = null

    private val mutableBackends = mutableListOf<String>()

    /**
     * 集群场景的多后端引用（按声明顺序）。非空即「集群场景」（集群编排，ADR-0008）：
     * 须配 [via] 代理（bot 经代理 `/server` 在后端间切换），且与单后端 [backend] 互斥。
     */
    val backendRefs: List<String> get() = mutableBackends.toList()

    /** 声明集群场景同时在线的多个后端（bot 经代理在它们间 `/server` 切换，桩跨服判定）。 */
    fun backends(vararg names: String) {
        mutableBackends += names
    }

    private val mutableBots = mutableListOf<BotSpec>()

    /** 该场景声明的全部机器人驱动（按声明顺序；空表示无机器人，仅 prepare + verify，单场景多 bot）。 */
    val botSpecs: List<BotSpec> get() = mutableBots.toList()

    /** 首个机器人声明（向后兼容单 bot 读取 + 压测取首个）；null 表示无机器人。 */
    val botSpec: BotSpec? get() = mutableBots.firstOrNull()

    /** 声明该场景由一个匿名 mineflayer 机器人驱动。 */
    fun bot(configure: BotSpec.() -> Unit) {
        mutableBots += BotSpec().apply(configure)
    }

    /**
     * 声明该场景由一个**具名角色** mineflayer 机器人驱动（单场景多 bot）。同场景声明多个 bot 时须各有唯一
     * role（异质角色，如 `admin` / `target`）；role 兼作该 bot 的日志/pid/username 基名。
     */
    fun bot(role: String, configure: BotSpec.() -> Unit) {
        mutableBots += BotSpec(role).apply(configure)
    }

    private var mutableStress: StressSpec? = null

    /**
     * 该场景的压测维度声明；非 null 即「压测场景」（压测编排，ADR-0008）：复用 [backends] 表 N 服、
     * 每服起 `botsPerServer` 个 bot 钉服持续施压，`via` 可选（有→N-listener 钉服代理，无→直连）。
     */
    val stressSpec: StressSpec? get() = mutableStress

    /** 声明该场景为压测场景（N 服 × M bot 钉服持续随机施压）。 */
    fun stress(configure: StressSpec.() -> Unit) {
        mutableStress = StressSpec().apply(configure)
    }

    private val mutableBeforeHooks = mutableListOf<ScenarioHook>()
    private val mutableReadyHooks = mutableListOf<ScenarioHook>()
    private val mutableAfterHooks = mutableListOf<ScenarioHook>()

    /** 该场景的「场景前」钩子（按声明顺序执行；空表示无）。 */
    val beforeHooks: List<ScenarioHook> get() = mutableBeforeHooks.toList()

    /** 该场景的「节点就绪后」钩子（按声明顺序执行；空表示无）。 */
    val readyHooks: List<ScenarioHook> get() = mutableReadyHooks.toList()

    /** 该场景的「场景后」钩子（按声明顺序执行；空表示无）。 */
    val afterHooks: List<ScenarioHook> get() = mutableAfterHooks.toList()

    /**
     * 声明一个「场景前」钩子：在**运行目录准备完成之后、服务端 / 代理启动之前**执行。
     *
     * 典型用途：拉起被测系统依赖的**外部进程**（独立控制面、外部服务），并等它就绪——
     * 必须先于服务端启动，否则服务端内的 agent 会因连不上依赖而注册失败（虽会退避重试，但拖慢且不稳）。
     *
     * 钩子抛异常即判该场景失败——但 [afterScenario] 声明的钩子**仍会执行**（收尾不被跳过）。
     */
    fun beforeScenario(hook: ScenarioHook) {
        mutableBeforeHooks += hook
    }

    /**
     * 声明一个「节点就绪后」钩子：在**全部服务端 / 代理端口可连之后、机器人启动之前**执行。
     *
     * 与 [beforeScenario] 的区别在时序：
     * - [beforeScenario] 时服务端**尚未启动**（用于起外部依赖）
     * - 本钩子时服务端**已启动并就绪**（用于依赖服务端的初始化：注册审批、造数、下发配置）
     *
     * 仅在集群场景与直连场景生效（这两条路径有明确的「节点就绪」时刻）；经代理与压测场景不支持，
     * 声明了会在配置期报错，不静默忽略。
     */
    fun readyScenario(hook: ScenarioHook) {
        mutableReadyHooks += hook
    }

    /**
     * 声明一个「场景后」钩子：在场景判定完成后执行，**正常 / 失败 / 中断三路径都会执行**
     * （编排侧用 `finalizedBy` + 任务体内 `try/finally` 双保险，与既有收尾语义一致）。
     *
     * 典型用途：按 pid 收尾 [beforeScenario] 起的外部进程、清理临时资源。
     */
    fun afterScenario(hook: ScenarioHook) {
        mutableAfterHooks += hook
    }
}

/**
 * 持久手测（serve）目标声明（持久手测 serve，ADR-0011）。
 *
 * 声明「把哪个后端（+ 可选经哪个代理）拉起并挂住，供真人客户端连入手测」。与 [ScenarioSpec] 不同，
 * serve **不驱动 bot、不判定 PASS/FAIL**——它只起服并阻塞到手动停。生成 `serve<Key>` / `stop<Key>Serve`
 * 任务（见 [top.wcpe.mc.testkit.contract.McTestkitTaskNames]）。
 *
 * @property name serve 名（拓扑内唯一，折成任务名 `serve<Key>` 的中缀）。
 */
@McTestkitDsl
class ServeSpec(val name: String) : java.io.Serializable {
    /** 起哪个后端（按 [BackendSpec.name] 引用；null = 默认取首个声明的后端）。与 [backends] 互斥。 */
    var backend: String? = null

    /** 经哪个代理（按 [ProxySpec.name] 引用；null = 直连后端）。设了则该代理须 routesTo 目标后端。 */
    var via: String? = null

    private val mutableBackends = mutableListOf<String>()

    /**
     * 集群 serve 的多后端引用（按声明顺序，集群 serve）。非空即**集群 serve**：把这些后端 + 代理整套挂起，
     * 真人经代理 `/server` 在它们间切换手测。须配 [via] 代理，且与单后端 [backend] 互斥。
     */
    val backendRefs: List<String> get() = mutableBackends.toList()

    /** 声明集群 serve 同时挂起的多个后端（真人经代理 `/server` 在它们间切换手测，集群 serve）。 */
    fun backends(vararg names: String) {
        mutableBackends += names
    }

    private val mutableBots = mutableListOf<BotSpec>()

    /**
     * 该 serve 期间可选起的机器人驱动（serve 人机混场；空 = 纯手测无 bot）。serve 起这些 bot 把环境驱到某状态
     * （如造点数据 / 模拟其他玩家），但**不按结果文件判定 / 收尾**——挂住让真人同时连入「人机混场」同测，
     * 直到手动停时随后端 / 代理一并收尾。多 bot 规则同场景（单场景多 bot）：各唯一 role、`count` 同质复制。
     */
    val botSpecs: List<BotSpec> get() = mutableBots.toList()

    /** 声明一个匿名 bot 在 serve 期间驱动（人机混场，serve 人机混场）。 */
    fun bot(configure: BotSpec.() -> Unit) {
        mutableBots += BotSpec().apply(configure)
    }

    /** 声明一个具名角色 bot 在 serve 期间驱动（同 serve 多 bot 须各唯一 role，serve 人机混场）。 */
    fun bot(role: String, configure: BotSpec.() -> Unit) {
        mutableBots += BotSpec(role).apply(configure)
    }
}

/**
 * 注入到运行目录的待测 / 依赖插件 jar 声明。
 *
 * 依赖插件有两种来源，可混用：**环境变量名或路径**（[plugin]，运行期解析以求可移植，不写死本机
 * 绝对路径，NFR）与 **Maven 坐标**（[mavenPlugin]，经 Gradle 原生依赖解析拉取）。
 */
@McTestkitDsl
class DependenciesSpec {
    /**
     * 待测插件 jar：环境变量名或路径。
     *
     * **未声明（null）时进入自测模式**（[selfJar]）：框架按契约默认注入**本模块 `jar` 任务的产物**
     * （`build/libs/<name>-<version>.jar`，契约 §3.1「默认取工作区构建产物」），并自动把
     * `prepareE2e<Key>` / `e2e<Key>` 接到该 `jar` 任务上——消费方无需再手写 `dependsOn` 样板。
     * 运行期仍可经 `MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 覆盖（CI / GradleRunner 注入用）。
     */
    var pluginUnderTest: String? = null

    /**
     * 是否处于自测模式（[pluginUnderTest] 未显式声明，由框架回退为本模块 jar 产物）。
     *
     * 插件 apply 期（afterEvaluate）填充；任务自动编排据此把 prepare / e2e 任务自动接到 `jar` 任务，
     * 并在解析期让 `MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 优先于 jar 产物路径。
     */
    var selfJar: Boolean = false
        internal set

    private val mutablePlugins = mutableListOf<String>()

    /** 已声明的依赖插件注入（环境变量名或路径）。 */
    val plugins: List<String> get() = mutablePlugins.toList()

    /** 声明一个依赖插件注入（环境变量名或路径）。 */
    fun plugin(envVarOrPath: String) {
        mutablePlugins += envVarOrPath
    }

    private val mutableMavenPlugins = mutableListOf<String>()

    /** 已声明的依赖插件 Maven 坐标（`group:artifact:version`，按声明顺序）。 */
    val mavenPlugins: List<String> get() = mutableMavenPlugins.toList()

    /**
     * 声明一个依赖插件注入：Maven 坐标 `group:artifact:version`（框架按 Gradle 原生依赖解析拉取）。
     *
     * 与 [plugin] 的「环境变量名或路径」并列，是**加法扩展**：既有声明语义不变。坐标在任务执行期
     * 落成本地 jar，按制品名（如 `foo-plugin-1.2.0.jar`）注入后端运行目录的 `plugins/`，与
     * [plugins] 同为非被测插件——不必再在本机放文件 + 设环境变量，CI 因而能跑这类场景。
     *
     * 仓库用消费方**当前生效**的仓库：框架不管理仓库声明，也不感知凭据（由消费方自己的 Gradle
     * 配置提供）。注意消费方若启用 `RepositoriesMode.PREFER_SETTINGS`，只有 settings 级仓库生效。
     *
     * 配置期校验：必须恰为 `group:artifact:version` 三段非空，且拒绝动态版本与区间
     * （`+` / `latest.*` / `[1.0,2.0)`）——它们会让同一份声明拉到不同制品。`-SNAPSHOT` 后缀**允许**
     * （测试框架有正当用途：验证尚未发布的快照），但快照制品不可复现，不适用需要严格重现的回归。
     *
     * 只拉声明的那个制品，**不解析传递依赖**（`isTransitive = false`）：插件运行期依赖该进服务端的
     * 库目录而非 `plugins/`，自动投放会放错位置；消费方如需运行库仍自行管理。
     */
    fun mavenPlugin(coordinate: String) {
        mutableMavenPlugins += MavenCoordinate.requireValid(coordinate)
    }
}
