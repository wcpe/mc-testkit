package top.wcpe.mc.testkit.bot

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 机器人环境变量构建单元测试（机器人驱动与结果判定）。
 *
 * 穷举校验：键全部取自 [McTestkitEnv]（前缀 `MC_TESTKIT_E2E_`）、无任何业务特定 `SAMPLEBIZ_`、
 * 值正确（含端口显式覆盖、缺省回退、消费方同名环境变量覆盖）。是纯函数，不连进程 / Gradle。
 */
class BotConnectionTest {

    /** 不提供任何覆盖的取值器（模拟「消费方未设环境变量」）。 */
    private val noOverride: (String) -> String? = { null }

    @Test
    @DisplayName("默认连接应仅生成 MC_TESTKIT_E2E_ 前缀键且不包含 SAMPLEBIZ")
    fun defaultConnectionUsesOnlyContractPrefix() {
        val env = BotConnection(action = "buy-success", username = "BuyBot")
            .toEnvironment(override = noOverride)

        assertTrue(env.isNotEmpty(), "环境变量不应为空")
        env.keys.forEach { key ->
            assertTrue(key.startsWith(McTestkitEnv.PREFIX), "环境变量键须以 ${McTestkitEnv.PREFIX} 开头：$key")
            assertFalse(key.contains("SAMPLEBIZ"), "不得残留业务特定前缀 SAMPLEBIZ：$key")
        }
    }

    @Test
    @DisplayName("默认连接应正确写入用户名、主机与认证等核心配置")
    fun defaultConnectionWritesCoreValues() {
        val env = BotConnection(action = "buy-success", username = "BuyBot")
            .toEnvironment(override = noOverride)

        // 场景 action 写入 BOT_ACTION（机器人内核据此分发场景驱动）
        assertEquals("buy-success", env[McTestkitEnv.BOT_ACTION])
        assertEquals("BuyBot", env[McTestkitEnv.BOT_USERNAME])
        // host / auth / 各超时都应有缺省值
        assertTrue(env.containsKey(McTestkitEnv.BOT_HOST))
        assertTrue(env.containsKey(McTestkitEnv.BOT_AUTH))
        assertTrue(env.containsKey(McTestkitEnv.BOT_CONNECT_TIMEOUT_MS))
        assertTrue(env.containsKey(McTestkitEnv.BOT_RETRY_DELAY_MS))
        assertTrue(env.containsKey(McTestkitEnv.BOT_READY_TIMEOUT_MS))
    }

    @Test
    @DisplayName("存在显式端口时应优先使用显式值")
    fun explicitPortOverridesFallbackValues() {
        val env = BotConnection(action = "via-proxy", username = "ProxyBot", port = 25577)
            .toEnvironment { name -> if (name == McTestkitEnv.BOT_PORT) "19999" else null }

        // 构造时显式给定的端口（如代理端口）应胜出
        assertEquals("25577", env[McTestkitEnv.BOT_PORT])
    }

    @Test
    @DisplayName("未提供显式端口时应使用消费方环境变量覆盖值")
    fun missingExplicitPortUsesEnvironmentOverride() {
        val env = BotConnection(action = "direct", username = "DirectBot", port = null)
            .toEnvironment { name -> if (name == McTestkitEnv.BOT_PORT) "20000" else null }

        assertEquals("20000", env[McTestkitEnv.BOT_PORT])
    }

    @Test
    @DisplayName("消费方同名环境变量应覆盖主机、用户名与认证配置")
    fun environmentOverridesConnectionValues() {
        val overrides = mapOf(
            McTestkitEnv.BOT_HOST to "10.0.0.5",
            McTestkitEnv.BOT_USERNAME to "OverrideBot",
            McTestkitEnv.BOT_AUTH to "microsoft",
        )
        val env = BotConnection(action = "x", username = "DefaultBot")
            .toEnvironment { name -> overrides[name] }

        assertEquals("10.0.0.5", env[McTestkitEnv.BOT_HOST])
        assertEquals("OverrideBot", env[McTestkitEnv.BOT_USERNAME])
        assertEquals("microsoft", env[McTestkitEnv.BOT_AUTH])
    }

    @Test
    @DisplayName("未提供版本时不应写出，提供或覆盖时应写出对应值")
    fun versionIsWrittenOnlyWhenProvided() {
        // 不给 version、消费方也没设：BOT_VERSION 不应出现（让 mineflayer 自协商）
        val envNoVersion = BotConnection(action = "x", username = "B").toEnvironment(override = noOverride)
        assertNull(envNoVersion[McTestkitEnv.BOT_VERSION])

        // 经代理固定协议版本：构造时传入 version → 写出
        val envFixed = BotConnection(action = "x", username = "B", version = "1.20.1").toEnvironment(override = noOverride)
        assertEquals("1.20.1", envFixed[McTestkitEnv.BOT_VERSION])

        // 消费方覆盖 version
        val envOverride = BotConnection(action = "x", username = "B")
            .toEnvironment { name -> if (name == McTestkitEnv.BOT_VERSION) "1.21" else null }
        assertEquals("1.21", envOverride[McTestkitEnv.BOT_VERSION])
    }

    @Test
    @DisplayName("附加环境变量应追加并覆盖默认配置")
    fun extraEnvironmentOverridesDefaults() {
        val env = BotConnection(action = "x", username = "B")
            .toEnvironment(extraEnvironment = mapOf(McTestkitEnv.BOT_HOST to "extra-host"), override = noOverride)

        assertEquals("extra-host", env[McTestkitEnv.BOT_HOST])
    }
}
