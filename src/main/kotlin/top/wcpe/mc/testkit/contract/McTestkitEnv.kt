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

    /** Spigot 后端 jar 路径覆盖；未提供时从已授权的 GetBukkit 公共源下载。 */
    const val SPIGOT_JAR = PREFIX + "SPIGOT_JAR"

    // ── 桩 ↔ 编排 交接（编排在起后端时下发，桩据此判定 / 写出）──
    /** 本次要执行的场景 id；编排起后端时下发，桩据此选择场景驱动（覆盖桩配置默认）。 */
    const val SCENARIO = PREFIX + "SCENARIO"

    /** 结果文件绝对路径；编排指定桩把 status/message 写到这里（= verify 读取处，二者对齐）。 */
    const val RESULT_FILE = PREFIX + "RESULT_FILE"

    /**
     * console 命令通道（`E2E_CMD`）鉴权令牌；编排每构建随机生成，同时下发给服务端桩与 bot 进程。
     * 桩侧未配置该变量时整条命令通道 fail-closed（拒绝一切命令请求）。令牌不进日志。
     */
    const val CMD_TOKEN = PREFIX + "CMD_TOKEN"

    /**
     * console 命令通道的许可发送者玩家名清单（逗号分隔；编排把本轮 bot 用户名下发给桩）。
     * 第二道闸：令牌正确之外还要求发送者在清单内；清单为空串时按「无 bot 场景」处理——拒绝全部命令。
     */
    const val CMD_SENDERS = PREFIX + "CMD_SENDERS"

    /**
     * 本后端的声明名（编排起每个后端时下发，与下发给 bot 的 [CLUSTER_BACKENDS] 同源、有序对应）。
     * 集群/压测下各后端各不相同（如 `s1` / `s2`）；消费方（被测插件或桩）据此 per-backend 派生身份——
     * 典型用法是做 `server-id` 后缀，使同组各服 server-id 不同（跨服归属/转服交接所需）。
     * 编排只负责「告诉每个后端它是谁」，不关心消费方怎么用（每后端身份注入）。
     */
    const val BACKEND_NAME = PREFIX + "BACKEND_NAME"

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

    /** 机器人生命周期回执文件；只用于 E2E 排障，不包含玩家、IP 或协议载荷。 */
    const val BOT_RECEIPT_FILE = PREFIX + "BOT_RECEIPT_FILE"

    // ── driver 车道（通用进程驱动；编排→driver 下发，消费方自带的真客户端 / 外部进程进程读取）──
    /** driver 应连接的服务地址（经代理时为代理地址）。 */
    const val DRIVER_HOST = PREFIX + "DRIVER_HOST"

    /** driver 应连接的服务端口（经代理时为代理端口）。 */
    const val DRIVER_PORT = PREFIX + "DRIVER_PORT"

    /** 本次执行的场景 id（与下发给桩 / bot 的 [SCENARIO] / [BOT_ACTION] 同源）。 */
    const val DRIVER_SCENARIO = PREFIX + "DRIVER_SCENARIO"

    /** driver 所连后端的声明名（与 [BACKEND_NAME] 同源）。 */
    const val DRIVER_BACKEND_NAME = PREFIX + "DRIVER_BACKEND_NAME"

    /** driver 的角色名（`driver("probe")` 声明时下发；匿名 driver 为空串）。 */
    const val DRIVER_ROLE = PREFIX + "DRIVER_ROLE"

    /** 本场景的结果目录绝对路径（driver 如需落证据文件，写这里使产物随场景归档）。 */
    const val DRIVER_RESULTS_DIR = PREFIX + "DRIVER_RESULTS_DIR"

    // ── 录屏 / 截图证据（可选降级能力；编排→driver 下发，套件内 JVM 侧读取亦可）──
    /** 录制证据开关：`1` 表示本 driver 声明要录屏 / 截图证据（`driver { recordEvidence = true }`）。 */
    const val RECORD = PREFIX + "RECORD"

    /** 视觉证据输出目录绝对路径（编排默认指向结果目录下 `recordings/`；录屏 / 截图落这里随场景归档）。 */
    const val RECORD_DIR = PREFIX + "RECORD_DIR"

    // ── 集群（集群编排）/ 压测（压测编排）──
    /** 集群场景的有序后端名（逗号分隔；编排→机器人下发，bot 据此 `/server <name>` 切换目标，集群编排）。 */
    const val CLUSTER_BACKENDS = PREFIX + "CLUSTER_BACKENDS"

    /** 压测持续秒数（编排→机器人，bot 据此跑循环；桩另按其自身计时收尾，压测编排）。 */
    const val STRESS_DURATION_SECONDS = PREFIX + "STRESS_DURATION_SECONDS"

    /** 压测每个 bot 进程的序号（编排→bot，bot 用 seed ^ botIndex 播种 RNG 使各 bot 可复现且互异，压测编排）。 */
    const val BOT_INDEX = PREFIX + "BOT_INDEX"

    /** 压测共享随机种子（编排→bot，与 [BOT_INDEX] 异或后播种 RNG，压测编排）。 */
    const val STRESS_RANDOM_SEED = PREFIX + "STRESS_RANDOM_SEED"
}
