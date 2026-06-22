package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.contract.McTestkitDefaults

/**
 * 后端节点声明。
 *
 * FR-01 只承载「声明值」；端口推导、平台落地为下载/运行任务等行为属 FR-03/FR-02/FR-04。
 */
@McTestkitDsl
class BackendSpec(val name: String) {
    /** 后端平台（默认 paper）。 */
    var platform: BackendPlatform = BackendPlatform.PAPER

    /** Minecraft 版本（默认 [McTestkitDefaults.MINECRAFT_VERSION]）。 */
    var version: String = McTestkitDefaults.MINECRAFT_VERSION

    /** 监听端口；null 表示留待拓扑解析（FR-03）按端口基数推导。 */
    var port: Int? = null

    // DSL 便捷量：consumer 可直接写 `platform = paper`，无需 import 枚举
    val paper: BackendPlatform get() = BackendPlatform.PAPER
    val folia: BackendPlatform get() = BackendPlatform.FOLIA
}

/**
 * 代理节点声明（含到后端的路由）。
 */
@McTestkitDsl
class ProxySpec(val name: String) {
    /** 代理平台（默认 waterfall）。 */
    var platform: ProxyPlatform = ProxyPlatform.WATERFALL

    /** 监听端口；null 表示留待拓扑解析推导。 */
    var port: Int? = null

    private val mutableRoutes = mutableListOf<String>()

    /** 该代理转发到的后端名（按声明顺序，路由目标存在性由 FR-03 配置期校验）。 */
    val routes: List<String> get() = mutableRoutes.toList()

    /** 声明该代理转发到的后端（按 [BackendSpec.name] 引用）。 */
    fun routesTo(vararg backendNames: String) {
        mutableRoutes += backendNames
    }

    val velocity: ProxyPlatform get() = ProxyPlatform.VELOCITY
    val waterfall: ProxyPlatform get() = ProxyPlatform.WATERFALL
    val bungeecord: ProxyPlatform get() = ProxyPlatform.BUNGEECORD
}

/**
 * 机器人驱动声明（场景内可选）。
 */
@McTestkitDsl
class BotSpec {
    /** 机器人用户名。 */
    var username: String? = null

    /** 控制动作 / 场景 id（与桩、机器人侧约定一致）。 */
    var action: String? = null

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
 * 压测维度声明（场景内可选，FR-11）。声明 `stress` 即「压测场景」（ADR-0008）：复用
 * [ScenarioSpec.backends] 表 N 服，每服起 [botsPerServer] 个 bot 进程**钉在本服**持续随机施压
 * （不 `/server` 切换，区别于集群跨服场景）。规模与时长在配置期校验须为正。
 */
@McTestkitDsl
class StressSpec {
    /** 每个后端起多少个 bot 进程（钉本服持续施压）；必填 >0（配置期校验）。 */
    var botsPerServer: Int = 0

    /** 持续压测时长（秒）；必填 >0（配置期校验）。bot 据此跑循环。 */
    var durationSeconds: Long = 0

    /** 随机种子；各 bot 用 `seed xor botIndex` 播种使行为可复现且互异。默认 [McTestkitDefaults.STRESS_RANDOM_SEED]。 */
    var randomSeed: Long = McTestkitDefaults.STRESS_RANDOM_SEED
}

/**
 * 端到端场景声明。
 */
@McTestkitDsl
class ScenarioSpec(val name: String) {
    /** 运行于哪个后端（按名称引用；null 表示默认 / 单后端）。与 [backends] 互斥。 */
    var backend: String? = null

    /** 经哪个代理（按名称引用；null 表示直连后端）。 */
    var via: String? = null

    private val mutableBackends = mutableListOf<String>()

    /**
     * 集群场景的多后端引用（按声明顺序）。非空即「集群场景」（FR-10，ADR-0008）：
     * 须配 [via] 代理（bot 经代理 `/server` 在后端间切换），且与单后端 [backend] 互斥。
     */
    val backendRefs: List<String> get() = mutableBackends.toList()

    /** 声明集群场景同时在线的多个后端（bot 经代理在它们间 `/server` 切换，桩跨服判定）。 */
    fun backends(vararg names: String) {
        mutableBackends += names
    }

    private var mutableBot: BotSpec? = null

    /** 该场景的机器人驱动声明；null 表示无机器人（仅 prepare + verify）。 */
    val botSpec: BotSpec? get() = mutableBot

    /** 声明该场景由 mineflayer 机器人驱动。 */
    fun bot(configure: BotSpec.() -> Unit) {
        mutableBot = BotSpec().apply(configure)
    }

    private var mutableStress: StressSpec? = null

    /**
     * 该场景的压测维度声明；非 null 即「压测场景」（FR-11，ADR-0008）：复用 [backends] 表 N 服、
     * 每服起 `botsPerServer` 个 bot 钉服持续施压，`via` 可选（有→N-listener 钉服代理，无→直连）。
     */
    val stressSpec: StressSpec? get() = mutableStress

    /** 声明该场景为压测场景（N 服 × M bot 钉服持续随机施压）。 */
    fun stress(configure: StressSpec.() -> Unit) {
        mutableStress = StressSpec().apply(configure)
    }
}

/**
 * 注入到运行目录的待测 / 依赖插件 jar 声明。
 *
 * 值为「环境变量名或路径」，运行期解析以求可移植（不写死本机绝对路径，NFR）。
 */
@McTestkitDsl
class DependenciesSpec {
    /** 待测插件 jar：环境变量名或路径（默认取工作区构建产物，可经此覆盖）。 */
    var pluginUnderTest: String? = null

    private val mutablePlugins = mutableListOf<String>()

    /** 已声明的依赖插件注入（环境变量名或路径）。 */
    val plugins: List<String> get() = mutablePlugins.toList()

    /** 声明一个依赖插件注入（环境变量名或路径）。 */
    fun plugin(envVarOrPath: String) {
        mutablePlugins += envVarOrPath
    }
}
