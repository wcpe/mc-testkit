package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [PaperConfigAdapter] 按版本生成 Paper 配置单元测试（多版本服务端拉起）。
 *
 * 穷举 8 个代表版本 + 边界版本，验证返回的配置文件名 + 嵌套路径 + 值正确。
 */
class PaperConfigAdapterTest {

    @Test
    @DisplayName("1.7.10–1.12（Legacy）应返回 null（跳过 Paper 配置）")
    fun legacyVersionsReturnNull() {
        assertNull(PaperConfigAdapter.forVersion("1.7.10"), "1.7.10 应跳过 Paper 配置")
        assertNull(PaperConfigAdapter.forVersion("1.8.8"), "1.8.8 应跳过 Paper 配置")
        assertNull(PaperConfigAdapter.forVersion("1.12.2"), "1.12.2 应跳过 Paper 配置")
        assertNull(PaperConfigAdapter.forVersion("1.12"), "1.12 应跳过 Paper 配置")
    }

    @Test
    @DisplayName("1.13–1.18（Modern）应返回 paper.yml + settings.bungeecord.online-mode: true")
    fun modernVersionsReturnPaperYml() {
        val versions = listOf("1.13", "1.16.5", "1.17.1", "1.18")
        versions.forEach { version ->
            val config = PaperConfigAdapter.forVersion(version)
            assertEquals("paper.yml", config?.fileName, "$version 文件名应为 paper.yml")
            assertEquals(listOf("settings", "bungeecord", "online-mode"), config?.path, "$version 路径应为 settings.bungeecord.online-mode")
            assertEquals(true, config?.value, "$version 值应为 true")
        }
    }

    @Test
    @DisplayName("1.19+（PaperConfig）应返回 config/paper-global.yml + proxies.online-mode: true")
    fun paperConfigVersionsReturnPaperGlobalYml() {
        val versions = listOf("1.19", "1.19.4", "1.20.1", "1.21.1")
        versions.forEach { version ->
            val config = PaperConfigAdapter.forVersion(version)
            assertEquals("config/paper-global.yml", config?.fileName, "$version 文件名应为 config/paper-global.yml")
            assertEquals(listOf("proxies", "online-mode"), config?.path, "$version 路径应为 proxies.online-mode")
            assertEquals(true, config?.value, "$version 值应为 true")
        }
    }

    @Test
    @DisplayName("8 个代表版本的配置决策应全部正确")
    fun allRepresentativeVersionsHaveCorrectConfig() {
        // Legacy → null
        assertNull(PaperConfigAdapter.forVersion("1.7.10"))
        assertNull(PaperConfigAdapter.forVersion("1.8.8"))
        assertNull(PaperConfigAdapter.forVersion("1.12.2"))
        // Modern → paper.yml
        val modern1165 = PaperConfigAdapter.forVersion("1.16.5")
        assertEquals("paper.yml", modern1165?.fileName)
        val modern1171 = PaperConfigAdapter.forVersion("1.17.1")
        assertEquals("paper.yml", modern1171?.fileName)
        // PaperConfig → paper-global.yml
        val paperConfig1194 = PaperConfigAdapter.forVersion("1.19.4")
        assertEquals("config/paper-global.yml", paperConfig1194?.fileName)
        val paperConfig1201 = PaperConfigAdapter.forVersion("1.20.1")
        assertEquals("config/paper-global.yml", paperConfig1201?.fileName)
        val paperConfig1211 = PaperConfigAdapter.forVersion("1.21.1")
        assertEquals("config/paper-global.yml", paperConfig1211?.fileName)
    }

    @Test
    @DisplayName("边界版本 1.13 应返回 paper.yml、1.19 应返回 paper-global.yml")
    fun boundaryVersionsAreCorrect() {
        val config113 = PaperConfigAdapter.forVersion("1.13")
        assertNotNull(config113, "1.13 不应返回 null")
        assertEquals("paper.yml", config113.fileName)

        val config119 = PaperConfigAdapter.forVersion("1.19")
        assertNotNull(config119, "1.19 不应返回 null")
        assertEquals("config/paper-global.yml", config119.fileName)
    }
}
