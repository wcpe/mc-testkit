package top.wcpe.mc.testkit.contract

/**
 * 对外契约②：自动生成任务的命名约定。
 *
 * 任务名一经发布即视为契约、保持稳定。场景名（kebab / camel 皆可）统一折成 PascalCase 中缀，
 * 代理名同理。具体任务由 任务自动编排 按消费方 `mcTestkit { }` 声明数据驱动地注册，此处只定名字怎么拼。
 */
object McTestkitTaskNames {
    /** 安装机器人 mineflayer 依赖。 */
    const val NPM_INSTALL_BOT = "npmInstallE2eBot"

    /** 运行库 / 下载缓存回写到持久缓存。 */
    const val SYNC_RUNTIME_CACHE = "syncE2eRuntimeCache"

    /** 清空持久缓存。 */
    const val PURGE_RUNTIME_CACHE = "purgeE2eRuntimeCache"

    /** 准备任务：`prepareE2e<Key>`。 */
    fun prepare(scenario: String): String = "prepareE2e" + scenario.toTaskKey()

    /** 验证任务（直连后端）：`e2e<Key>`。 */
    fun verify(scenario: String): String = "e2e" + scenario.toTaskKey()

    /** 经代理验证任务：`e2e<Key>Via<Proxy>`。 */
    fun verifyVia(scenario: String, proxy: String): String =
        verify(scenario) + "Via" + proxy.toTaskKey()

    /** 启动机器人任务：`launch<Key>Bot`。 */
    fun launchBot(scenario: String): String = "launch" + scenario.toTaskKey() + "Bot"

    /** 一键「启动机器人 + 验证」任务：`e2e<Key>WithBot`。 */
    fun withBot(scenario: String): String = verify(scenario) + "WithBot"

    /** 集群验证任务（多后端 + 代理 + 切换机器人）：`e2e<Key>Cluster`（集群编排，ADR-0008）。 */
    fun cluster(scenario: String): String = verify(scenario) + "Cluster"

    /** 集群收尾任务（按 pid 停全部集群后端 + 代理）：`stop<Key>Cluster`。 */
    fun stopCluster(scenario: String): String = "stop" + scenario.toTaskKey() + "Cluster"

    /** 压测验证任务（N 服 × M bot 钉服持续施压 + 聚合判定）：`e2e<Key>Stress`（压测编排，ADR-0008）。 */
    fun stress(scenario: String): String = verify(scenario) + "Stress"

    /** 压测收尾任务（按 pid 停全部压测后端 + 代理）：`stop<Key>Stress`。 */
    fun stopStress(scenario: String): String = "stop" + scenario.toTaskKey() + "Stress"

    /** 持久手测起服任务（前台起后端 + 可选代理并挂住，持久手测 serve，ADR-0011）：`serve<Key>`。 */
    fun serve(serveName: String): String = "serve" + serveName.toTaskKey()

    /** 持久手测收尾任务（按 pid 停 serve 后端 + 代理）：`stop<Key>Serve`。 */
    fun stopServe(serveName: String): String = "stop" + serveName.toTaskKey() + "Serve"
}

/** 把场景 / 代理名（kebab-case / camelCase / 空格分隔）折成任务名用的 PascalCase 中缀。 */
internal fun String.toTaskKey(): String =
    split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
