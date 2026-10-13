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
     * 单后端运行目录名的前缀：`run-`。
     *
     * 与 [backendRunDirName] 同源（后者由它拼出），供需要「识别所有后端运行目录」的调用方
     * （如按后端回写持久缓存）按前缀枚举，而不必自己拼字符串。
     */
    const val BACKEND_RUN_DIR_PREFIX = "run-"

    /**
     * 后端名保留字：`proxy`。
     *
     * 后端运行目录与代理运行目录共用 `run-` 前缀，而代理目录名恒为 [PROXY_RUN_DIR_NAME]
     * （`run-proxy`）——于是 `backend("proxy")` 的运行目录恰好**就是**代理运行目录，两者是同一
     * 物理目录。后果不只是缓存失效：代理铺运行目录时会 `deleteRecursively` 整删（代理每轮全量
     * 重建，不保留子目录），把后端刚铺好的模板 / 权威配置 / 注入插件一起删掉，随后后端又在这个
     * 已被改成代理形态的目录里启动。故在配置期直接拒绝该名，而不是留到运行期去踩。
     */
    const val RESERVED_BACKEND_NAME = "proxy"

    /**
     * 后端名是否命中保留字（[RESERVED_BACKEND_NAME]）。
     *
     * **判定必须比字面相等更宽**，因为目录撞名由**文件系统**决定、而非由字符串决定：
     * - **大小写**：Windows / macOS 的文件系统大小写不敏感，`run-Proxy` 与 `run-proxy` 是同一
     *   目录，故 `Proxy` / `PROXY` 同样会撞（只在大小写敏感的 Linux 上才真的不同）。
     * - **首尾空白**：Windows 落盘会去掉名称尾随的空格与句点，`"proxy "` 实际建成 `run-proxy`；
     *   Java 的 `File` 不会替你拦截这件事。
     *
     * 两处都属「校验放行、文件系统却把它们当成同一目录」，正是本保留字要防的情形，故比较前先
     * `trim()` 并忽略大小写。空白名早已被非空校验拦下，这里的 `trim()` 只影响判定、不改写名字。
     */
    fun isReservedBackendName(backendName: String): Boolean =
        backendName.trim().equals(RESERVED_BACKEND_NAME, ignoreCase = true)

    /**
     * 单后端运行目录名：`run-<后端名>`。
     *
     * 每个后端一个独立目录：多版本矩阵下各后端运行库 / 缓存互不污染。
     */
    fun backendRunDirName(backendName: String): String = "$BACKEND_RUN_DIR_PREFIX$backendName"
}
