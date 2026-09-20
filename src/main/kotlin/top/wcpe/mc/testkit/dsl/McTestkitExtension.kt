package top.wcpe.mc.testkit.dsl

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import top.wcpe.mc.testkit.contract.McTestkitRunDirectories

/**
 * `mcTestkit { }` 扩展：声明测试拓扑（后端 / 代理 / 路由）、场景与依赖注入。
 *
 * 这是对外 DSL 契约（插件骨架 冻结形态，见 docs/API.md）。它只「忠实记录声明」并暴露只读访问器；
 * 拓扑解析与端口推导（拓扑 DSL）、任务编排（任务自动编排）等读取这些声明，**不在此实现**——
 * 以此让 Wave 1 各 FR 各占一包、互不撞这个入口扩展文件。
 *
 * `open`：供 Gradle ObjectFactory 装饰为 ExtensionAware 实例。
 */
@McTestkitDsl
open class McTestkitExtension {
    private val mutableBackends = mutableListOf<BackendSpec>()
    private val mutableProxies = mutableListOf<ProxySpec>()
    private val mutableScenarios = mutableListOf<ScenarioSpec>()
    private val mutableServes = mutableListOf<ServeSpec>()
    private val mutableVersionMatrices = mutableListOf<VersionMatrixSpec>()
    private val dependencies = DependenciesSpec()

    /**
     * 运行根目录（`<buildDir>/mc-testkit`）；由插件在 `apply` 期注入，消费方不设置。
     *
     * 延迟到执行期才解析，保证与任务侧推导出的运行目录同源（见 [McTestkitRunDirectories]）。
     */
    internal lateinit var runRoot: Provider<Directory>

    /** 已声明的后端节点（只读快照）。 */
    val declaredBackends: List<BackendSpec> get() = mutableBackends.toList()

    /** 已声明的代理节点（只读快照）。 */
    val declaredProxies: List<ProxySpec> get() = mutableProxies.toList()

    /** 已声明的场景（只读快照）。 */
    val declaredScenarios: List<ScenarioSpec> get() = mutableScenarios.toList()

    /** 已声明的持久手测目标（只读快照，持久手测 serve）。 */
    val declaredServes: List<ServeSpec> get() = mutableServes.toList()

    /** 已声明的版本矩阵（只读快照；插件 apply 期会展开为 backend + scenario）。 */
    val declaredVersionMatrices: List<VersionMatrixSpec> get() = mutableVersionMatrices.toList()

    /** 依赖注入声明。 */
    val declaredDependencies: DependenciesSpec get() = dependencies

    /** 声明一个后端节点。 */
    fun backend(name: String, configure: BackendSpec.() -> Unit = {}): BackendSpec =
        BackendSpec(name).apply(configure).also { mutableBackends += it }

    /** 声明一个代理节点。 */
    fun proxy(name: String, configure: ProxySpec.() -> Unit = {}): ProxySpec =
        ProxySpec(name).apply(configure).also { mutableProxies += it }

    /** 声明一个端到端场景。 */
    fun scenario(name: String, configure: ScenarioSpec.() -> Unit = {}): ScenarioSpec =
        ScenarioSpec(name).apply(configure).also { mutableScenarios += it }

    /**
     * 声明一个端到端场景（无附加配置重载）。
     *
     * Groovy 消费方必备：Kotlin 的默认参数对 Groovy 不可见，`scenario("smoke")` 在 Groovy 里
     * 本会因找不到单参重载而失败（须写 `scenario("smoke") { }` 显式空闭包）；此重载补齐该缺口。
     */
    fun scenario(name: String): ScenarioSpec = scenario(name) { }

    /** 声明一个持久手测目标（起服挂住供真人客户端连入，持久手测 serve）。 */
    fun serve(name: String, configure: ServeSpec.() -> Unit = {}): ServeSpec =
        ServeSpec(name).apply(configure).also { mutableServes += it }

    /** 声明一个多版本矩阵（展开为 backend + scenario + 串行聚合任务）。 */
    fun versionMatrix(name: String, configure: VersionMatrixSpec.() -> Unit = {}): VersionMatrixSpec =
        VersionMatrixSpec(name).apply(configure).also { mutableVersionMatrices += it }

    /** 声明注入到运行目录的待测 / 依赖插件 jar。 */
    fun dependencies(configure: DependenciesSpec.() -> Unit): DependenciesSpec =
        dependencies.apply(configure)

    /**
     * 某后端节点的运行目录（`<buildDir>/mc-testkit/run-<后端名>`）。
     *
     * **消费方向运行目录注入配置 / 验收桩文件的落点**：`prepareE2e<场景>` 会先铺好该目录
     * （模板、权威配置、依赖插件），消费方的注入任务以 `dependsOn("prepareE2e<场景>")` 保证顺序，
     * 再把文件写进来。
     *
     * ```kotlin
     * val runDir = mcTestkit.backendRunDirectory("paper")
     * tasks.register("installProbeConfig") {
     *     dependsOn("prepareE2eSmoke")
     *     doLast { File(runDir.get().asFile, "plugins/Probe/config.yml").writeText(...) }
     * }
     * ```
     *
     * 返回 [Provider] 而非 `File`：目录在执行期解析，与任务侧的布局推导同源；不要在配置期
     * `get()`（那时构建目录可能还没定），也不要把扩展对象捕获进任务动作（配置缓存不友好）。
     *
     * @param backendName 后端名，须与 `backend("…")` 的声明名一致。
     */
    fun backendRunDirectory(backendName: String): Provider<Directory> {
        check(::runRoot.isInitialized) {
            "mcTestkit 扩展尚未初始化：请确认本工程已应用插件 id \"" +
                top.wcpe.mc.testkit.contract.McTestkitContract.PLUGIN_ID +
                "\" 后再访问 backendRunDirectory。"
        }
        return runRoot.map { it.dir(McTestkitRunDirectories.backendRunDirName(backendName)) }
    }
}
