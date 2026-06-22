package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.DependenciesSpec
import top.wcpe.mc.testkit.dsl.McTestkitDsl
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec

/**
 * `mcTestkit { }` 扩展：声明测试拓扑（后端 / 代理 / 路由）、场景与依赖注入。
 *
 * 这是对外 DSL 契约（FR-01 冻结形态，见 docs/API.md）。它只「忠实记录声明」并暴露只读访问器；
 * 拓扑解析与端口推导（FR-03）、任务编排（FR-04）等读取这些声明，**不在此实现**——
 * 以此让 Wave 1 各 FR 各占一包、互不撞这个入口扩展文件。
 *
 * `open`：供 Gradle ObjectFactory 装饰为 ExtensionAware 实例。
 */
@McTestkitDsl
open class McTestkitExtension {
    private val mutableBackends = mutableListOf<BackendSpec>()
    private val mutableProxies = mutableListOf<ProxySpec>()
    private val mutableScenarios = mutableListOf<ScenarioSpec>()
    private val dependencies = DependenciesSpec()

    /** 已声明的后端节点（只读快照）。 */
    val declaredBackends: List<BackendSpec> get() = mutableBackends.toList()

    /** 已声明的代理节点（只读快照）。 */
    val declaredProxies: List<ProxySpec> get() = mutableProxies.toList()

    /** 已声明的场景（只读快照）。 */
    val declaredScenarios: List<ScenarioSpec> get() = mutableScenarios.toList()

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

    /** 声明注入到运行目录的待测 / 依赖插件 jar。 */
    fun dependencies(configure: DependenciesSpec.() -> Unit): DependenciesSpec =
        dependencies.apply(configure)
}
