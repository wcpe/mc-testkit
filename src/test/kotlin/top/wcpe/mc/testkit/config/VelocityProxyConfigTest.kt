package top.wcpe.mc.testkit.config

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Velocity 代理配置（`velocity.toml`）生成单元测试（FR-02/03，ADR-0010）。
 *
 * 验证 modern forwarding + 单端口 bind + N 具名 server + try 落地/fallback 顺序，覆盖单后端与集群。
 */
class VelocityProxyConfigTest {

    @Test
    fun `单后端 velocity_toml 含 bind 与 modern forwarding 与单 server`() {
        val toml = velocityProxyConfigToml(
            listenPort = 25577,
            servers = listOf("s1" to "127.0.0.1:25565"),
        )
        // 单端口 bind
        assertTrue(toml.contains("bind = \"0.0.0.0:25577\""), "应 bind 代理端口：\n$toml")
        // modern forwarding + secret 文件引用
        assertTrue(toml.contains("player-info-forwarding-mode = \"modern\""), "应为 modern forwarding")
        assertTrue(toml.contains("forwarding-secret-file = \"forwarding.secret\""), "应引用 forwarding.secret 文件")
        // 离线放行机器人
        assertTrue(toml.contains("online-mode = false"), "应离线模式放行 bot")
        // 关强制密钥认证：放行无签名 key 的离线机器人（否则 1.19+ 离线 bot 入服/发命令被踢）
        assertTrue(toml.contains("force-key-authentication = false"), "应关强制密钥认证放行离线 bot")
        // 具名 server + 地址
        assertTrue(toml.contains("\"s1\" = \"127.0.0.1:25565\""), "应有具名 server s1：\n$toml")
        // try 含该 server
        assertTrue(Regex("(?ms)try = \\[.*\"s1\".*]").containsMatchIn(toml), "try 应含 s1：\n$toml")
        // 必须显式写空 [forced-hosts] 覆盖 Velocity 默认示例（否则引用不存在的 server 致 Velocity 拒绝启动）
        assertTrue(toml.contains("[forced-hosts]"), "应显式写空 [forced-hosts] 覆盖默认示例：\n$toml")
    }

    @Test
    fun `集群 velocity_toml 含全部具名 server 且 try 为有序全后端`() {
        val toml = velocityProxyConfigToml(
            listenPort = 25577,
            servers = listOf("s1" to "127.0.0.1:25565", "s2" to "127.0.0.1:25566"),
        )
        assertTrue(toml.contains("\"s1\" = \"127.0.0.1:25565\""), "应有 server s1")
        assertTrue(toml.contains("\"s2\" = \"127.0.0.1:25566\""), "应有 server s2")
        // try 为有序全后端（首个默认服 s1，其余 fallback s2）：支撑 FR-15 崩溃接管回退
        val tryBlock = Regex("(?ms)try = \\[.*?]").find(toml)?.value ?: ""
        assertTrue(tryBlock.contains("\"s1\""), "try 应含默认服 s1：\n$toml")
        assertTrue(tryBlock.contains("\"s2\""), "try 应含 fallback 后端 s2：\n$toml")
        assertTrue(
            tryBlock.indexOf("\"s1\"") < tryBlock.indexOf("\"s2\""),
            "try 顺序应为 s1 在前（默认服）、s2 在后（fallback）：\n$toml",
        )
    }

    @Test
    fun `空 server 列表报错`() {
        assertFailsWith<IllegalArgumentException> {
            velocityProxyConfigToml(listenPort = 25577, servers = emptyList())
        }
    }
}
