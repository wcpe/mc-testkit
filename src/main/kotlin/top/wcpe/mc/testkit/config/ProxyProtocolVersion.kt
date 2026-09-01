package top.wcpe.mc.testkit.config

/**
 * 经代理机器人 mineflayer 协议版本固定规则（环境契约）。
 *
 * 经代理时代理 ping 不一定回后端真实版本，机器人据此自动挑版本可能选到过新协议而被踢
 * （"Outdated server"）。本规则固定一条决策：**经代理时机器人协议版本 = 后端 MC 版本**。
 *
 * 本对象只给规则（纯函数）、不起机器人：任务自动编排 接线时把返回值喂给 机器人驱动与结果判定 的
 * `BotConnection.version`（即环境变量 `MC_TESTKIT_E2E_BOT_VERSION`）。
 */
object ProxyProtocolVersion {

    /**
     * 经代理时机器人应固定使用的协议版本。
     *
     * @param backendVersion 后端 Minecraft 版本（如 `1.20.1`）。
     * @return 机器人 mineflayer 协议版本（即 [backendVersion]）。
     */
    fun forBackend(backendVersion: String): String = backendVersion
}
