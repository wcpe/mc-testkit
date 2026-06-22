package top.wcpe.mc.testkit.config

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BungeeCord 后端三件套单元测试（FR-05，环境契约高风险区）。
 *
 * 验证对临时 runDir 应用后三处值正确：
 * - `server.properties`：`online-mode=false`；
 * - `spigot.yml`：`settings.bungeecord: true`；
 * - `config/paper-global.yml`：`proxies.bungee-cord.online-mode: false`。
 * 覆盖「文件预先存在」与「文件不存在」两条路径。
 */
class BackendBungeeCordConfigTest {

    private fun tempRunDir(): File = Files.createTempDirectory("mc-testkit-bungee").toFile()

    private fun readProperties(runDir: File): Properties =
        Properties().apply {
            File(runDir, "server.properties").reader(Charsets.UTF_8).use { load(it) }
        }

    @Test
    fun `文件全不存在时三件套均写出正确值`() {
        val runDir = tempRunDir()

        BackendBungeeCordConfig.apply(runDir)

        // server.properties
        val props = readProperties(runDir)
        assertEquals("false", props.getProperty(ServerProperties.ONLINE_MODE))
        assertEquals("false", props.getProperty(ServerProperties.ENFORCE_SECURE_PROFILE))

        // spigot.yml：settings.bungeecord: true
        val spigot = File(runDir, "spigot.yml").readText()
        assertTrue(
            Regex("(?m)^\\s*bungeecord:\\s*true\\s*$").containsMatchIn(spigot),
            "spigot.yml 应含 bungeecord: true，实际：\n$spigot",
        )

        // config/paper-global.yml：proxies.bungee-cord.online-mode: false
        val paper = File(runDir, "config/paper-global.yml").readText()
        assertTrue(
            Regex("(?m)^\\s*online-mode:\\s*false\\s*$").containsMatchIn(paper),
            "paper-global.yml 应含 online-mode: false，实际：\n$paper",
        )
        assertTrue(paper.contains("bungee-cord:"), "paper-global.yml 应含 bungee-cord 块，实际：\n$paper")
    }

    @Test
    fun `已存在文件时补丁现有键而非另起一行`() {
        val runDir = tempRunDir()
        // 预先存在 spigot.yml（bungeecord 默认 false），应被补成 true
        File(runDir, "spigot.yml").writeText(
            """
            settings:
              bungeecord: false
              sample-count: 12
            """.trimIndent() + "\n",
        )
        // 预先存在 paper-global.yml（online-mode 默认 true），应被补成 false
        File(runDir, "config").mkdirs()
        File(runDir, "config/paper-global.yml").writeText(
            """
            proxies:
              bungee-cord:
                online-mode: true
              velocity:
                enabled: false
            """.trimIndent() + "\n",
        )

        BackendBungeeCordConfig.apply(runDir)

        val spigot = File(runDir, "spigot.yml").readText()
        assertTrue(
            Regex("(?m)^\\s*bungeecord:\\s*true\\s*$").containsMatchIn(spigot),
            "spigot.yml 的 bungeecord 应被补成 true，实际：\n$spigot",
        )
        // 不破坏同块其它键
        assertTrue(spigot.contains("sample-count: 12"), "不应破坏 spigot.yml 其它键")
        // 不应残留旧的 false 值
        assertTrue(
            !Regex("(?m)^\\s*bungeecord:\\s*false\\s*$").containsMatchIn(spigot),
            "不应残留 bungeecord: false，实际：\n$spigot",
        )

        val paper = File(runDir, "config/paper-global.yml").readText()
        assertTrue(
            Regex("(?m)^\\s*online-mode:\\s*false\\s*$").containsMatchIn(paper),
            "paper-global.yml 的 bungee-cord.online-mode 应被补成 false，实际：\n$paper",
        )
        // velocity 块不应被误改
        assertTrue(paper.contains("velocity:"), "不应破坏 paper-global.yml 的 velocity 块")
    }
}
