package top.wcpe.mc.testkit.config

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [bungeeProxyConfigYml] 纯函数单测（FR-04）：生成的 BungeeCord 系代理配置含关键转发项。
 */
class ProxyConfigTest {

    @Test
    fun `生成的代理配置含监听端口 后端地址与离线转发关键项`() {
        val yml = bungeeProxyConfigYml(listenPort = 25577, backendAddress = "127.0.0.1:25565")

        // 监听端口
        assertTrue("0.0.0.0:25577" in yml, yml)
        assertTrue("query_port: 25577" in yml, yml)
        // 后端地址（强制转发到单后端）
        assertTrue("address: 127.0.0.1:25565" in yml, yml)
        assertTrue("force_default_server: true" in yml, yml)
        // 离线 + IP 透传（与后端 BungeeCord 模式配套）
        assertTrue("ip_forward: true" in yml, yml)
        assertTrue("online_mode: false" in yml, yml)
        // 关连接限流（机器人入服不被节流）
        assertTrue("connection_throttle: -1" in yml, yml)
    }
}
