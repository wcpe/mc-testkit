package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 集群代理配置（集群编排，ADR-0008）：单 listener + N 具名 server（server 名=后端名，供 bot `/server` 切换）。
 */
class ClusterProxyConfigTest {

    @Test
    @DisplayName("集群代理配置应包含单个监听器与全部具名服务器")
    fun clusterConfigContainsSingleListenerAndNamedServers() {
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
        // priorities 为全部后端有序列表（首个默认 + 其余 fallback）：支撑崩溃接管时回退到存活后端
        val prioritiesBlock = Regex("(?ms)priorities:\\s*\\n(?:\\s*-\\s*\\S+\\n)+").find(yml)?.value ?: ""
        assertTrue(prioritiesBlock.contains("- s1"), "priorities 应含默认服 s1：\n$yml")
        assertTrue(prioritiesBlock.contains("- s2"), "priorities 应含 fallback 后端 s2：\n$yml")
    }
}
