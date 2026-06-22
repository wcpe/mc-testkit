package top.wcpe.mc.testkit.config

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 集群代理配置（FR-10，ADR-0008）：单 listener + N 具名 server（server 名=后端名，供 bot `/server` 切换）。
 */
class ClusterProxyConfigTest {

    @Test
    fun `集群代理配置含单 listener 与全部具名 server`() {
        val yml = bungeeClusterProxyConfigYml(
            listenPort = 25577,
            backends = listOf("s1" to "127.0.0.1:25565", "s2" to "127.0.0.1:25566"),
        )
        // 单 listener 绑代理端口
        assertTrue(yml.contains("host: 0.0.0.0:25577"), "应有单 listener 绑代理端口")
        // N 个具名 server（server 名 = 后端名，供 /server 切换）
        assertTrue(Regex("(?m)^\\s+s1:").containsMatchIn(yml), "应有具名 server s1")
        assertTrue(yml.contains("127.0.0.1:25565"))
        assertTrue(Regex("(?m)^\\s+s2:").containsMatchIn(yml), "应有具名 server s2")
        assertTrue(yml.contains("127.0.0.1:25566"))
        // 透传与默认服落首个后端
        assertTrue(yml.contains("ip_forward: true"))
        assertTrue(yml.contains("force_default_server: true"))
    }
}
