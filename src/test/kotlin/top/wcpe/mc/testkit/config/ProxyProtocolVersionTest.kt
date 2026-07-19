package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 经代理机器人协议版本固定规则单元测试（FR-05，环境契约高风险区）。
 *
 * 验证纯函数决策：经代理时机器人 mineflayer 协议版本 = 后端 MC 版本
 * （避免代理 ping 不回后端版本导致机器人挑过新协议被踢「Outdated server」）。
 */
class ProxyProtocolVersionTest {

    @Test
    @DisplayName("经代理连接时协议版本应与后端版本一致")
    fun proxyProtocolMatchesBackendVersion() {
        assertEquals("1.20.1", ProxyProtocolVersion.forBackend("1.20.1"))
        assertEquals("1.21.4", ProxyProtocolVersion.forBackend("1.21.4"))
    }
}
