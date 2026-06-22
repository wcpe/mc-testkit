package top.wcpe.mc.testkit.config

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `server.properties` 编辑单元测试（FR-05）。
 *
 * 验证纯函数式编辑：改对涉及键、保留未涉及键；用临时目录读写，不连任何外部依赖。
 */
class ServerPropertiesTest {

    private fun tempRunDir(): File = Files.createTempDirectory("mc-testkit-serverprops").toFile()

    private fun writeProperties(runDir: File, content: String) {
        File(runDir, "server.properties").writeText(content, Charsets.UTF_8)
    }

    private fun readProperties(runDir: File): Properties =
        Properties().apply {
            File(runDir, "server.properties").reader(Charsets.UTF_8).use { load(it) }
        }

    @Test
    fun `编辑保留未涉及键并改对涉及键`() {
        val runDir = tempRunDir()
        // 既有文件含一个不该被动的自定义键
        writeProperties(runDir, "motd=旧标题\nserver-port=25565\nonline-mode=true\n")

        ServerProperties.edit(
            runDir,
            mapOf(
                ServerProperties.ONLINE_MODE to "false",
                ServerProperties.SERVER_PORT to "30000",
            ),
        )

        val result = readProperties(runDir)
        // 涉及键被改对
        assertEquals("false", result.getProperty(ServerProperties.ONLINE_MODE))
        assertEquals("30000", result.getProperty(ServerProperties.SERVER_PORT))
        // 未涉及键原样保留
        assertEquals("旧标题", result.getProperty("motd"))
    }

    @Test
    fun `文件不存在时新建并只写入指定键`() {
        val runDir = tempRunDir()

        ServerProperties.edit(runDir, mapOf(ServerProperties.LEVEL_TYPE to "minecraft:flat"))

        val result = readProperties(runDir)
        assertEquals("minecraft:flat", result.getProperty(ServerProperties.LEVEL_TYPE))
        // 不该凭空补出别的键
        assertEquals(null, result.getProperty(ServerProperties.ONLINE_MODE))
    }

    @Test
    fun `难度键常量与缺省判定（FR-13）`() {
        assertEquals("difficulty", ServerProperties.DIFFICULTY)

        // 模板未设难度 → load 不含该键（编排据此补默认 peaceful，保护测试玩家）
        val noDifficulty = tempRunDir()
        writeProperties(noDifficulty, "server-port=25565\n")
        assertFalse(ServerProperties.load(noDifficulty).containsKey(ServerProperties.DIFFICULTY))

        // 模板已设难度 → load 含该键（编排据此保留消费方设定，不覆盖）
        val withDifficulty = tempRunDir()
        writeProperties(withDifficulty, "difficulty=hard\n")
        assertTrue(ServerProperties.load(withDifficulty).containsKey(ServerProperties.DIFFICULTY))
        assertEquals("hard", ServerProperties.load(withDifficulty).getProperty(ServerProperties.DIFFICULTY))

        // 缺省补 peaceful 后可读回，且保留未涉及键
        ServerProperties.edit(noDifficulty, mapOf(ServerProperties.DIFFICULTY to "peaceful"))
        val edited = readProperties(noDifficulty)
        assertEquals("peaceful", edited.getProperty(ServerProperties.DIFFICULTY))
        assertEquals("25565", edited.getProperty(ServerProperties.SERVER_PORT))
    }

    @Test
    fun `port 读取已写入端口缺省回退默认值`() {
        val withPort = tempRunDir()
        writeProperties(withPort, "server-port=28888\n")
        assertEquals(28888, ServerProperties.port(withPort))

        // 文件不存在 → 回退默认端口
        val noFile = tempRunDir()
        assertEquals(ServerProperties.DEFAULT_PORT, ServerProperties.port(noFile))
    }
}
