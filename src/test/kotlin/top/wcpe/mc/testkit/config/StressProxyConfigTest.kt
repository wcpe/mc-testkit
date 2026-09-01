package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 压测 N-listener 钉服代理配置生成（压测编排）的纯函数测试：一端口对一后端、priority 钉服、全后端 servers。
 */
class StressProxyConfigTest {

    @Test
    @DisplayName("多个监听器应按端口分别固定到对应后端服务器")
    fun stressConfigPinsEachListenerToBackend() {
        val yml = bungeeStressProxyConfigYml(
            listOf(
                StressProxyBinding("s1", "127.0.0.1:25565", 25577),
                StressProxyBinding("s2", "127.0.0.1:25566", 25578),
            ),
        )
        // 两个 listener 各绑一个端口
        assertTrue(yml.contains("0.0.0.0:25577"), "应有 s1 的 listener 端口")
        assertTrue(yml.contains("0.0.0.0:25578"), "应有 s2 的 listener 端口")
        // 钉服：各 listener 的 priorities 钉到自己的后端
        assertTrue(yml.contains("- s1"), "listener 应钉到 s1")
        assertTrue(yml.contains("- s2"), "listener 应钉到 s2")
        // servers 段含全部后端地址（供路由）
        assertTrue(yml.contains("127.0.0.1:25565"), "servers 应含 s1 地址")
        assertTrue(yml.contains("127.0.0.1:25566"), "servers 应含 s2 地址")
        // 钉服 + 透传关键项
        assertTrue(yml.contains("force_default_server: true"))
        assertTrue(yml.contains("ip_forward: true"))
        assertTrue(yml.contains("online_mode: false"))
    }

    @Test
    @DisplayName("绑定列表为空时应抛出中文错误")
    fun emptyStressBindingsThrowChineseError() {
        val ex = assertFailsWith<IllegalArgumentException> { bungeeStressProxyConfigYml(emptyList()) }
        assertTrue(ex.message!!.contains("绑定") || ex.message!!.contains("至少"))
    }
}
