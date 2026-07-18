package top.wcpe.mc.testkit.topology

import top.wcpe.mc.testkit.dsl.BackendPlatform
import top.wcpe.mc.testkit.dsl.ProxyPlatform

/**
 * 已解析的后端节点（FR-03）。
 *
 * 由 [TopologyResolver] 从冻结的 `BackendSpec` 解析而来；与 spec 不同，[port] 已是确定的端口
 * （显式声明或按端口基数推导），解析后不再为 null。
 */
data class ResolvedBackend(
    /** 后端名（拓扑内唯一，且不与代理名相撞）。 */
    val name: String,
    /** 后端平台（P1 仅 Paper / Folia，由枚举类型在编译期保证）。 */
    val platform: BackendPlatform,
    /** Minecraft 版本。 */
    val version: String,
    /** 监听端口（已解析）。 */
    val port: Int,
    /** 仅注入该后端进程的节点环境变量。 */
    val environment: Map<String, String> = emptyMap(),
    /** 后端模板目录的原始声明（环境变量名或路径）；null 表示回退旧全局模板。 */
    val templateDirectory: String? = null,
)

/**
 * 已解析的代理节点（FR-03）。
 *
 * [routes] 为该代理转发到的后端名（均已校验存在于同一拓扑的后端集合中）。
 */
data class ResolvedProxy(
    /** 代理名（拓扑内唯一，且不与后端名相撞）。 */
    val name: String,
    /** 代理平台（P1 仅 Velocity / Waterfall / BungeeCord）。 */
    val platform: ProxyPlatform,
    /** 监听端口（已解析）。 */
    val port: Int,
    /** 转发到的后端名（按声明顺序，均已校验存在）。 */
    val routes: List<String>,
    /** 该代理专属插件 jar 的原始声明（环境变量名或路径，按声明顺序）。 */
    val plugins: List<String> = emptyList(),
    /** 仅注入该代理进程的节点环境变量。 */
    val environment: Map<String, String> = emptyMap(),
    /** 代理模板目录的原始声明（环境变量名或路径）。 */
    val templateDirectory: String? = null,
)

/**
 * 一次测试的内存拓扑模型（FR-03）：后端集合 + 代理集合（含代理→后端路由）。
 *
 * 这是 `mcTestkit { }` 声明解析后的权威结构，供 FR-04 编排数据驱动地装配任务；
 * 本模型不含任何行为，端口已确定、引用已校验。
 */
data class Topology(
    /** 已解析的后端集合（按声明顺序）。 */
    val backends: List<ResolvedBackend>,
    /** 已解析的代理集合（按声明顺序）。 */
    val proxies: List<ResolvedProxy>,
)
