package top.wcpe.mc.testkit.contract

/**
 * 运行目录布局（对外契约②：消费方据此向运行目录注入配置 / 验收桩文件，见 docs/API.md）。
 *
 * **单一真源**：任务自动编排的路径推导（`task/RunLayout`）与对外访问器
 * （`McTestkitExtension.backendRunDirectory`）都从这里取名——布局若调整，两侧同时改，
 * 消费方不会因目录改名而静默失配（曾发生过：消费方按旧布局写配置，mc-testkit 换布局后
 * 配置落在无人使用的目录，场景静默失败）。
 *
 * 一经发布即契约，变更须走兼容评估。
 */
object McTestkitRunDirectories {
    /** E2E 工作根目录名（`<buildDir>/mc-testkit`）。 */
    const val WORK_DIR_NAME = "mc-testkit"

    /** 代理运行目录名（`<工作根>/run-proxy`）。 */
    const val PROXY_RUN_DIR_NAME = "run-proxy"

    /**
     * 单后端运行目录名：`run-<后端名>`。
     *
     * 每个后端一个独立目录：多版本矩阵下各后端运行库 / 缓存互不污染。
     */
    fun backendRunDirName(backendName: String): String = "run-$backendName"
}
