package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `server.properties` 编辑单元测试（环境契约）。
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
    @DisplayName("编辑属性时应更新指定键并保留未涉及键")
    fun editUpdatesSpecifiedKeysAndPreservesOthers() {
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
    @DisplayName("属性文件不存在时应新建文件且仅写入指定键")
    fun missingFileIsCreatedWithSpecifiedKeysOnly() {
        val runDir = tempRunDir()

        ServerProperties.edit(runDir, mapOf(ServerProperties.LEVEL_TYPE to "minecraft:flat"))

        val result = readProperties(runDir)
        assertEquals("minecraft:flat", result.getProperty(ServerProperties.LEVEL_TYPE))
        // 不该凭空补出别的键
        assertEquals(null, result.getProperty(ServerProperties.ONLINE_MODE))
    }

    @Test
    @DisplayName("难度键常量应支持缺省与已配置状态判定")
    fun difficultyConstantSupportsDefaultDetection() {
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
    @DisplayName("端口读取应返回已配置值并在缺省时回退默认值")
    fun portUsesConfiguredValueOrDefault() {
        val withPort = tempRunDir()
        writeProperties(withPort, "server-port=28888\n")
        assertEquals(28888, ServerProperties.port(withPort))

        // 文件不存在 → 回退默认端口
        val noFile = tempRunDir()
        assertEquals(ServerProperties.DEFAULT_PORT, ServerProperties.port(noFile))
    }

    // ── versionAwareOverrides（多版本服务端拉起）──

    @Test
    @DisplayName("1.7.10 应移除 simulation-distance 和 enforce-secure-profile，level-type 转为 FLAT")
    fun versionAwareOverridesForLegacy1710() {
        val overrides = mapOf(
            ServerProperties.SERVER_PORT to "25565",
            ServerProperties.ONLINE_MODE to "false",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.LEVEL_TYPE to "minecraft:flat",
        )
        val result = ServerProperties.versionAwareOverrides("1.7.10", overrides)
        assertEquals("25565", result[ServerProperties.SERVER_PORT])
        assertEquals("false", result[ServerProperties.ONLINE_MODE])
        assertNull(result[ServerProperties.SIMULATION_DISTANCE], "1.7.10 应移除 simulation-distance")
        assertNull(result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.7.10 应移除 enforce-secure-profile")
        assertEquals("FLAT", result[ServerProperties.LEVEL_TYPE], "1.7.10 level-type 应为 FLAT")
    }

    @Test
    @DisplayName("1.12.2 应移除 simulation-distance 和 enforce-secure-profile，level-type 转为 FLAT")
    fun versionAwareOverridesForLegacy1122() {
        val overrides = mapOf(
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ServerProperties.LEVEL_TYPE to "minecraft:flat",
        )
        val result = ServerProperties.versionAwareOverrides("1.12.2", overrides)
        assertNull(result[ServerProperties.SIMULATION_DISTANCE], "1.12.2 应移除 simulation-distance")
        assertNull(result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.12.2 应移除 enforce-secure-profile")
        assertEquals("FLAT", result[ServerProperties.LEVEL_TYPE], "1.12.2 level-type 应为 FLAT")
    }

    @Test
    @DisplayName("1.13 应移除 simulation-distance 和 enforce-secure-profile，level-type 转为 minecraft:flat")
    fun versionAwareOverridesForModern113() {
        val overrides = mapOf(
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ServerProperties.LEVEL_TYPE to "FLAT",
        )
        val result = ServerProperties.versionAwareOverrides("1.13", overrides)
        assertNull(result[ServerProperties.SIMULATION_DISTANCE], "1.13 应移除 simulation-distance")
        assertNull(result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.13 应移除 enforce-secure-profile")
        assertEquals("minecraft:flat", result[ServerProperties.LEVEL_TYPE], "1.13 level-type 应为 minecraft:flat")
    }

    @Test
    @DisplayName("1.16.5 应保留 simulation-distance、移除 enforce-secure-profile，level-type 转为 minecraft:flat")
    fun versionAwareOverridesForModern1165() {
        val overrides = mapOf(
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ServerProperties.LEVEL_TYPE to "FLAT",
        )
        val result = ServerProperties.versionAwareOverrides("1.16.5", overrides)
        assertEquals("8", result[ServerProperties.SIMULATION_DISTANCE], "1.16.5 应保留 simulation-distance")
        assertNull(result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.16.5 应移除 enforce-secure-profile")
        assertEquals("minecraft:flat", result[ServerProperties.LEVEL_TYPE], "1.16.5 level-type 应为 minecraft:flat")
    }

    @Test
    @DisplayName("1.18 应保留 simulation-distance、移除 enforce-secure-profile，level-type 转为 minecraft:flat")
    fun versionAwareOverridesForModern118() {
        val overrides = mapOf(
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
        )
        val result = ServerProperties.versionAwareOverrides("1.18", overrides)
        assertEquals("8", result[ServerProperties.SIMULATION_DISTANCE], "1.18 应保留 simulation-distance")
        assertNull(result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.18 应移除 enforce-secure-profile")
    }

    @Test
    @DisplayName("1.19+ 应保留 simulation-distance 和 enforce-secure-profile，level-type 转为 minecraft:flat")
    fun versionAwareOverridesForPaperConfig119Plus() {
        val overrides = mapOf(
            ServerProperties.SIMULATION_DISTANCE to "8",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ServerProperties.LEVEL_TYPE to "FLAT",
        )
        val result = ServerProperties.versionAwareOverrides("1.19.4", overrides)
        assertEquals("8", result[ServerProperties.SIMULATION_DISTANCE], "1.19.4 应保留 simulation-distance")
        assertEquals("false", result[ServerProperties.ENFORCE_SECURE_PROFILE], "1.19.4 应保留 enforce-secure-profile")
        assertEquals("minecraft:flat", result[ServerProperties.LEVEL_TYPE], "1.19.4 level-type 应为 minecraft:flat")

        // 1.20.1 / 1.21.1 同理
        val result20 = ServerProperties.versionAwareOverrides("1.20.1", overrides)
        assertEquals("8", result20[ServerProperties.SIMULATION_DISTANCE])
        assertEquals("false", result20[ServerProperties.ENFORCE_SECURE_PROFILE])
        assertEquals("minecraft:flat", result20[ServerProperties.LEVEL_TYPE])
    }

    @Test
    @DisplayName("不含 level-type 时不应凭空补出 level-type")
    fun versionAwareOverridesWithoutLevelTypeDoesNotAddIt() {
        val overrides = mapOf(
            ServerProperties.SERVER_PORT to "25565",
            ServerProperties.ONLINE_MODE to "false",
        )
        val result = ServerProperties.versionAwareOverrides("1.7.10", overrides)
        assertFalse(result.containsKey(ServerProperties.LEVEL_TYPE), "不应凭空补出 level-type")
    }

    @Test
    @DisplayName("versionAwareOverrides 不应修改入参映射")
    fun versionAwareOverridesDoesNotMutateInput() {
        val overrides = LinkedHashMap<String, String>().apply {
            put(ServerProperties.LEVEL_TYPE, "minecraft:flat")
            put(ServerProperties.SIMULATION_DISTANCE, "8")
        }
        ServerProperties.versionAwareOverrides("1.7.10", overrides)
        // 入参不应被修改
        assertEquals("minecraft:flat", overrides[ServerProperties.LEVEL_TYPE])
        assertEquals("8", overrides[ServerProperties.SIMULATION_DISTANCE])
    }
}
