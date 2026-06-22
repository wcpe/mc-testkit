package top.wcpe.mc.testkit.contract

/**
 * 对外契约③：环境变量名集中常量。
 *
 * 统一前缀 `MC_TESTKIT_E2E_`，避免与消费方其它环境变量冲突；改名只需改这一处。
 * 用于覆盖默认值、提供 jar / 模板路径、调节规模与超时（须可移植，不写死本机绝对路径）。
 * 业务特定的场景维度（商店标题、奖励物等）不进框架契约——那是消费方自带桩/机器人的事。
 */
object McTestkitEnv {
    /** 全部 mc-testkit E2E 环境变量统一前缀。 */
    const val PREFIX = "MC_TESTKIT_E2E_"

    // ── 服务端 / 模板 ──
    /** 后端 Minecraft 版本覆盖。 */
    const val MINECRAFT_VERSION = PREFIX + "MINECRAFT_VERSION"

    /** 服务端模板目录（提供依赖插件配置 / 运行库基线）。 */
    const val SERVER_TEMPLATE_DIR = PREFIX + "SERVER_TEMPLATE_DIR"

    /** 待测插件 jar 路径（默认取工作区构建产物，可经此覆盖）。 */
    const val PLUGIN_UNDER_TEST_JAR = PREFIX + "PLUGIN_UNDER_TEST_JAR"

    /** 后端 Paper jar 路径覆盖（默认经内置下载模块按版本下载，可经此直接提供，离线/CI 友好）。 */
    const val PAPER_JAR = PREFIX + "PAPER_JAR"

    /** 后端 Folia jar 路径覆盖（同 [PAPER_JAR]）。 */
    const val FOLIA_JAR = PREFIX + "FOLIA_JAR"

    // ── 桩 ↔ 编排 交接（编排在起后端时下发，桩据此判定 / 写出）──
    /** 本次要执行的场景 id；编排起后端时下发，桩据此选择场景驱动（覆盖桩配置默认）。 */
    const val SCENARIO = PREFIX + "SCENARIO"

    /** 结果文件绝对路径；编排指定桩把 status/message 写到这里（= verify 读取处，二者对齐）。 */
    const val RESULT_FILE = PREFIX + "RESULT_FILE"

    // ── 代理（jar / 版本 / 端口）──
    const val WATERFALL_JAR = PREFIX + "WATERFALL_JAR"
    const val WATERFALL_VERSION = PREFIX + "WATERFALL_VERSION"
    const val VELOCITY_JAR = PREFIX + "VELOCITY_JAR"
    const val VELOCITY_VERSION = PREFIX + "VELOCITY_VERSION"
    const val BUNGEECORD_JAR = PREFIX + "BUNGEECORD_JAR"
    const val BUNGEECORD_VERSION = PREFIX + "BUNGEECORD_VERSION"

    /** 单代理场景监听端口。 */
    const val PROXY_PORT = PREFIX + "PROXY_PORT"

    /** 多后端集群代理基准端口（第 n 服经代理端口 base + n）。 */
    const val PROXY_BASE_PORT = PREFIX + "PROXY_BASE_PORT"

    // ── 机器人连接 / 超时 / 协议 ──
    /** 机器人驱动的场景 action / 场景 id（机器人内核据此分发场景驱动；与桩、控制协议一致）。 */
    const val BOT_ACTION = PREFIX + "BOT_ACTION"
    const val BOT_HOST = PREFIX + "BOT_HOST"
    const val BOT_PORT = PREFIX + "BOT_PORT"
    const val BOT_USERNAME = PREFIX + "BOT_USERNAME"
    const val BOT_AUTH = PREFIX + "BOT_AUTH"

    /** 机器人 mineflayer 协议版本；经代理时由编排自动固定为后端版本。 */
    const val BOT_VERSION = PREFIX + "BOT_VERSION"
    const val BOT_CONNECT_TIMEOUT_MS = PREFIX + "BOT_CONNECT_TIMEOUT_MS"
    const val BOT_RETRY_DELAY_MS = PREFIX + "BOT_RETRY_DELAY_MS"
    const val BOT_READY_TIMEOUT_MS = PREFIX + "BOT_READY_TIMEOUT_MS"

    // ── 集群（FR-10）/ 压测（FR-11）──
    /** 集群场景的有序后端名（逗号分隔；编排→机器人下发，bot 据此 `/server <name>` 切换目标，FR-10）。 */
    const val CLUSTER_BACKENDS = PREFIX + "CLUSTER_BACKENDS"

    /** 压测持续秒数（编排→机器人，bot 据此跑循环；桩另按其自身计时收尾，FR-11）。 */
    const val STRESS_DURATION_SECONDS = PREFIX + "STRESS_DURATION_SECONDS"

    /** 压测每个 bot 进程的序号（编排→bot，bot 用 seed ^ botIndex 播种 RNG 使各 bot 可复现且互异，FR-11）。 */
    const val BOT_INDEX = PREFIX + "BOT_INDEX"

    /** 压测共享随机种子（编排→bot，与 [BOT_INDEX] 异或后播种 RNG，FR-11）。 */
    const val STRESS_RANDOM_SEED = PREFIX + "STRESS_RANDOM_SEED"
}
