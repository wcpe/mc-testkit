package top.wcpe.mc.testkit.topology

/**
 * 拓扑解析的端口推导基数（拓扑 DSL 私有常量）。
 *
 * 节点未显式声明端口时，按「基数 + 在同类节点中的 0 基序号」推导（见 [TopologyResolver]）。
 * 取值沿用首个接入项目惯例：后端 25565（MC 默认端口）、代理 25577（BungeeCord 默认端口）。
 *
 * 注意：这是 拓扑 DSL 自有常量，**不放入 `contract/` 冻结契约文件**——端口基数是编排内部细节，
 * 非对外契约；消费方按需用显式 `port` 覆盖即可。
 */
object TopologyDefaults {
    /** 后端端口推导基数（第 n 个待推导后端 → BACKEND_BASE_PORT + n）。 */
    const val BACKEND_BASE_PORT = 25565

    /** 代理端口推导基数（第 n 个待推导代理 → PROXY_BASE_PORT + n）。 */
    const val PROXY_BASE_PORT = 25577
}
