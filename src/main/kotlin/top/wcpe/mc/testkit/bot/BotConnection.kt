package top.wcpe.mc.testkit.bot

import top.wcpe.mc.testkit.contract.McTestkitEnv

/**
 * 机器人连接的缺省值（仅本包用；非对外契约，故不进 `contract/`）。
 *
 * 都是可被消费方同名环境变量覆盖的「兜底默认」，不写死本机绝对路径（NFR 可移植）。
 */
internal object BotConnectionDefaults {
    /** 默认连接主机：本机回环。 */
    const val HOST = "127.0.0.1"

    /** 默认认证模式：离线（E2E 在可信本机 / CI，后端 online-mode=false）。 */
    const val AUTH = "offline"

    /** 默认连接重试总窗口（毫秒）：机器人重连直到后端就绪。真实后端含数据源/依赖初始化常需数十秒
     *  启动（实测真实后端 + MySQL 约 60s），故默认 180s，给慢启动后端留足重试窗口（可经 env 覆盖）。 */
    const val CONNECT_TIMEOUT_MS = "180000"

    /** 默认连接失败重试间隔（毫秒）：代理先于后端就绪时机器人重试用。 */
    const val RETRY_DELAY_MS = "2000"

    /** 默认等待桩 `E2E_READY` 的就绪超时（毫秒）。 */
    const val READY_TIMEOUT_MS = "120000"
}

/**
 * 一次机器人驱动的连接参数（机器人驱动与结果判定）。
 *
 * 只承载「这次怎么连」，[toEnvironment] 把它折成传给机器人进程的环境变量（键全部取自
 * [McTestkitEnv]、前缀 `MC_TESTKIT_E2E_`）。纯函数 + 注入式覆盖取值器，便于穷举单测，
 * 不耦合 Gradle `Project`（任务侧用 `project.providers.environmentVariable(name).orNull` 传入）。
 *
 * @property action 控制动作 / 场景 id（与桩、机器人侧约定一致；同时作日志 / pid 的 key）。
 * @property username 机器人用户名（可被消费方 [McTestkitEnv.BOT_USERNAME] 覆盖）。
 * @property port 显式端口（如指向代理端口）；非空时**优先于**消费方覆盖与缺省解析。
 * @property version mineflayer 协议版本；非空时写出（经代理时由编排固定为后端 MC 版本，环境契约）。
 */
data class BotConnection(
    val action: String,
    val username: String,
    val port: Int? = null,
    val version: String? = null,
) {

    /**
     * 折成机器人进程环境变量。
     *
     * @param extraEnvironment 追加项（场景特定 env，**最后**合入，可覆盖以上任意键）。
     * @param override 消费方覆盖取值器：给环境变量名、返回其值（无覆盖返回 null）。放末位以便
     *   常用调用写成尾随 lambda（`toEnvironment { name -> ... }`）。
     * @return 键全部以 `MC_TESTKIT_E2E_` 开头的环境变量映射。
     */
    fun toEnvironment(
        extraEnvironment: Map<String, String> = emptyMap(),
        override: (String) -> String?,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        // 场景 action / 场景 id：写入 BOT_ACTION，机器人内核（template/bot）据此分发场景驱动
        env[McTestkitEnv.BOT_ACTION] = action

        // 用户名：消费方覆盖优先，否则用声明值
        env[McTestkitEnv.BOT_USERNAME] = override(McTestkitEnv.BOT_USERNAME)?.takeIf { it.isNotBlank() } ?: username

        // 主机 / 认证：消费方覆盖优先，否则缺省
        env[McTestkitEnv.BOT_HOST] = override(McTestkitEnv.BOT_HOST)?.takeIf { it.isNotBlank() } ?: BotConnectionDefaults.HOST
        env[McTestkitEnv.BOT_AUTH] = override(McTestkitEnv.BOT_AUTH)?.takeIf { it.isNotBlank() } ?: BotConnectionDefaults.AUTH

        // 端口：显式给定（如代理端口）> 消费方覆盖；都没有则不写（由任务侧按运行目录解析后经 extraEnvironment 补）
        val resolvedPort = port?.toString() ?: override(McTestkitEnv.BOT_PORT)?.takeIf { it.isNotBlank() }
        if (resolvedPort != null) {
            env[McTestkitEnv.BOT_PORT] = resolvedPort
        }

        // 协议版本：显式给定 > 消费方覆盖；都没有则不写（让 mineflayer 自协商）
        val resolvedVersion = version?.takeIf { it.isNotBlank() }
            ?: override(McTestkitEnv.BOT_VERSION)?.takeIf { it.isNotBlank() }
        if (resolvedVersion != null) {
            env[McTestkitEnv.BOT_VERSION] = resolvedVersion
        }

        // 各超时：消费方覆盖优先，否则缺省
        env[McTestkitEnv.BOT_CONNECT_TIMEOUT_MS] =
            override(McTestkitEnv.BOT_CONNECT_TIMEOUT_MS)?.takeIf { it.isNotBlank() } ?: BotConnectionDefaults.CONNECT_TIMEOUT_MS
        env[McTestkitEnv.BOT_RETRY_DELAY_MS] =
            override(McTestkitEnv.BOT_RETRY_DELAY_MS)?.takeIf { it.isNotBlank() } ?: BotConnectionDefaults.RETRY_DELAY_MS
        env[McTestkitEnv.BOT_READY_TIMEOUT_MS] =
            override(McTestkitEnv.BOT_READY_TIMEOUT_MS)?.takeIf { it.isNotBlank() } ?: BotConnectionDefaults.READY_TIMEOUT_MS

        // 场景特定追加项最后合入，可覆盖以上任意键
        env.putAll(extraEnvironment)
        return env
    }
}
