package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Velocity 后端 modern forwarding 两件套单元测试（FR-05，ADR-0010，环境契约高风险区）。
 *
 * 验证：`server.properties` online-mode/enforce-secure-profile=false；
 * `config/paper-global.yml` proxies.velocity.{enabled=true, online-mode=false, secret=共享}；
 * **不写** spigot.yml bungeecord（与 BungeeCord 模式互斥）；深合并保留未涉及键。
 */
class BackendVelocityConfigTest {

    private fun tempRunDir(): File = Files.createTempDirectory("mc-testkit-velocity").toFile()

    private fun readProperties(runDir: File): Properties =
        Properties().apply {
            File(runDir, "server.properties").reader(Charsets.UTF_8).use { load(it) }
        }

    @Test
    @DisplayName("配置文件均不存在时应写入 Velocity 配置且不创建 spigot.yml")
    fun missingFilesAreCreatedWithVelocitySettings() {
        val runDir = tempRunDir()

        BackendVelocityConfig.apply(runDir, "1.20.1")

        val props = readProperties(runDir)
        assertEquals("false", props.getProperty(ServerProperties.ONLINE_MODE))
        assertEquals("false", props.getProperty(ServerProperties.ENFORCE_SECURE_PROFILE))

        val paper = File(runDir, "config/paper-global.yml").readText()
        assertTrue(paper.contains("velocity:"), "paper-global.yml 应含 velocity 块，实际：\n$paper")
        assertTrue(
            Regex("(?m)^\\s*enabled:\\s*true\\s*$").containsMatchIn(paper),
            "velocity.enabled 应为 true，实际：\n$paper",
        )
        assertTrue(
            Regex("(?m)^\\s*online-mode:\\s*false\\s*$").containsMatchIn(paper),
            "velocity.online-mode 应为 false，实际：\n$paper",
        )
        assertTrue(
            paper.contains(McTestkitDefaults.VELOCITY_FORWARDING_SECRET),
            "velocity.secret 应为共享 secret，实际：\n$paper",
        )

        // Velocity modern forwarding 不需要 spigot.yml bungeecord：不应被写出
        assertFalse(File(runDir, "spigot.yml").exists(), "Velocity 模式不应写 spigot.yml")
    }

    @Test
    @DisplayName("paper-global.yml 已存在时应补充 Velocity 配置并保留同级键")
    fun existingPaperConfigKeepsSiblingKeys() {
        val runDir = tempRunDir()
        File(runDir, "config").mkdirs()
        File(runDir, "config/paper-global.yml").writeText(
            """
            proxies:
              proxy-protocol: false
              bungee-cord:
                online-mode: true
            """.trimIndent() + "\n",
        )

        BackendVelocityConfig.apply(runDir, "1.20.1")

        val paper = File(runDir, "config/paper-global.yml").readText()
        // velocity 块补上
        assertTrue(paper.contains("velocity:"), "应补 velocity 块，实际：\n$paper")
        assertTrue(paper.contains(McTestkitDefaults.VELOCITY_FORWARDING_SECRET), "应写共享 secret")
        // 不破坏 proxies 下其它键
        assertTrue(paper.contains("bungee-cord:"), "不应破坏既有 bungee-cord 块，实际：\n$paper")
        assertTrue(paper.contains("proxy-protocol:"), "不应破坏 proxies 下其它键，实际：\n$paper")
    }

    @Test
    @DisplayName("提供自定义密钥时应覆盖默认密钥")
    fun customSecretOverridesDefaultSecret() {
        val runDir = tempRunDir()
        BackendVelocityConfig.apply(runDir, "1.20.1", secret = "custom-secret-123")
        val paper = File(runDir, "config/paper-global.yml").readText()
        assertTrue(paper.contains("custom-secret-123"), "应写入自定 secret，实际：\n$paper")
    }

    // ── 版本感知（FR-21）──

    @Test
    @DisplayName("1.16.5 应跳过 paper-global.yml（Velocity modern forwarding 仅 1.19+ 有效）")
    fun modern1165SkipsPaperGlobalYml() {
        val runDir = tempRunDir()
        BackendVelocityConfig.apply(runDir, "1.16.5")

        val props = readProperties(runDir)
        assertEquals("false", props.getProperty(ServerProperties.ONLINE_MODE))
        assertEquals(null, props.getProperty(ServerProperties.ENFORCE_SECURE_PROFILE), "1.16.5 不应有 enforce-secure-profile")

        // Velocity modern forwarding 仅在 1.19+ 有效，旧版不应写 paper-global.yml
        assertFalse(File(runDir, "config/paper-global.yml").exists(), "1.16.5 不应写 paper-global.yml")
    }
}
