package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MinecraftVersionGroup] 版本分组查询单元测试（FR-21）。
 *
 * 穷举 8 个代表版本的分组归属 + 边界版本 + javaVersionSegment。
 */
class MinecraftVersionGroupTest {

    @Test
    @DisplayName("应定义 8 个代表版本且按时间序排列")
    fun representativeVersionsAreOrdered() {
        assertEquals(
            listOf("1.7.10", "1.8.8", "1.12.2", "1.16.5", "1.17.1", "1.19.4", "1.20.1", "1.21.1"),
            MinecraftVersionGroup.REPRESENTATIVE_VERSIONS,
        )
    }

    @Test
    @DisplayName("isLegacy 应正确识别 1.7–1.12 为 Legacy、1.13+ 为非 Legacy")
    fun isLegacyCorrectlyIdentifiesLegacyRange() {
        // Legacy 段（1.7–1.12）
        assertTrue(MinecraftVersionGroup.isLegacy("1.7.10"), "1.7.10 应为 Legacy")
        assertTrue(MinecraftVersionGroup.isLegacy("1.8.8"), "1.8.8 应为 Legacy")
        assertTrue(MinecraftVersionGroup.isLegacy("1.12.2"), "1.12.2 应为 Legacy")
        // 边界：1.12.x 仍为 Legacy
        assertTrue(MinecraftVersionGroup.isLegacy("1.12"), "1.12 应为 Legacy")
        // Modern 段（1.13+）
        assertFalse(MinecraftVersionGroup.isLegacy("1.13"), "1.13 不应为 Legacy")
        assertFalse(MinecraftVersionGroup.isLegacy("1.16.5"), "1.16.5 不应为 Legacy")
        assertFalse(MinecraftVersionGroup.isLegacy("1.20.1"), "1.20.1 不应为 Legacy")
        assertFalse(MinecraftVersionGroup.isLegacy("1.21.1"), "1.21.1 不应为 Legacy")
    }

    @Test
    @DisplayName("needsPaperYml 应正确识别 1.13–1.18 段")
    fun needsPaperYmlCorrectlyIdentifiesModernRange() {
        // Legacy 段不需要 paper.yml
        assertFalse(MinecraftVersionGroup.needsPaperYml("1.7.10"), "1.7.10 不需要 paper.yml")
        assertFalse(MinecraftVersionGroup.needsPaperYml("1.12.2"), "1.12.2 不需要 paper.yml")
        // Modern 段（1.13–1.18）需要 paper.yml
        assertTrue(MinecraftVersionGroup.needsPaperYml("1.13"), "1.13 需要 paper.yml")
        assertTrue(MinecraftVersionGroup.needsPaperYml("1.16.5"), "1.16.5 需要 paper.yml")
        assertTrue(MinecraftVersionGroup.needsPaperYml("1.17.1"), "1.17.1 需要 paper.yml")
        assertTrue(MinecraftVersionGroup.needsPaperYml("1.18"), "1.18 需要 paper.yml")
        // PaperConfig 段（1.19+）不需要 paper.yml（用 paper-global.yml）
        assertFalse(MinecraftVersionGroup.needsPaperYml("1.19"), "1.19 不需要 paper.yml")
        assertFalse(MinecraftVersionGroup.needsPaperYml("1.20.1"), "1.20.1 不需要 paper.yml")
        assertFalse(MinecraftVersionGroup.needsPaperYml("1.21.1"), "1.21.1 不需要 paper.yml")
    }

    @Test
    @DisplayName("needsPaperGlobal 应正确识别 1.19+ 段")
    fun needsPaperGlobalCorrectlyIdentifiesPaperConfigRange() {
        // Legacy / Modern 段不需要 paper-global.yml
        assertFalse(MinecraftVersionGroup.needsPaperGlobal("1.7.10"), "1.7.10 不需要 paper-global.yml")
        assertFalse(MinecraftVersionGroup.needsPaperGlobal("1.16.5"), "1.16.5 不需要 paper-global.yml")
        assertFalse(MinecraftVersionGroup.needsPaperGlobal("1.18"), "1.18 不需要 paper-global.yml")
        // PaperConfig 段（1.19+）需要 paper-global.yml
        assertTrue(MinecraftVersionGroup.needsPaperGlobal("1.19"), "1.19 需要 paper-global.yml")
        assertTrue(MinecraftVersionGroup.needsPaperGlobal("1.19.4"), "1.19.4 需要 paper-global.yml")
        assertTrue(MinecraftVersionGroup.needsPaperGlobal("1.20.1"), "1.20.1 需要 paper-global.yml")
        assertTrue(MinecraftVersionGroup.needsPaperGlobal("1.21.1"), "1.21.1 需要 paper-global.yml")
    }

    @Test
    @DisplayName("isBotSupported 应正确识别 1.8+ 支持 bot、1.7.10 不支持")
    fun isBotSupportedCorrectlyIdentifiesBotRange() {
        // 1.7.10 不支持 bot E2E
        assertFalse(MinecraftVersionGroup.isBotSupported("1.7.10"), "1.7.10 不支持 bot")
        // 1.8+ 支持 bot
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8"), "1.8 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8.8"), "1.8.8 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.12.2"), "1.12.2 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.20.1"), "1.20.1 支持 bot")
    }

    @Test
    @DisplayName("javaVersionSegment 应把 major.minor 用下划线连接")
    fun javaVersionSegmentConnectsMajorMinorWithUnderscore() {
        assertEquals("1_7", MinecraftVersionGroup.javaVersionSegment("1.7.10"))
        assertEquals("1_8", MinecraftVersionGroup.javaVersionSegment("1.8.8"))
        assertEquals("1_12", MinecraftVersionGroup.javaVersionSegment("1.12.2"))
        assertEquals("1_16", MinecraftVersionGroup.javaVersionSegment("1.16.5"))
        assertEquals("1_17", MinecraftVersionGroup.javaVersionSegment("1.17.1"))
        assertEquals("1_19", MinecraftVersionGroup.javaVersionSegment("1.19.4"))
        assertEquals("1_20", MinecraftVersionGroup.javaVersionSegment("1.20.1"))
        assertEquals("1_21", MinecraftVersionGroup.javaVersionSegment("1.21.1"))
    }

    @Test
    @DisplayName("javaVersionSegment 对不足两段的异常串应原样返回不崩溃")
    fun javaVersionSegmentHandlesMalformedInput() {
        assertEquals("1", MinecraftVersionGroup.javaVersionSegment("1"))
        assertEquals("abc", MinecraftVersionGroup.javaVersionSegment("abc"))
        assertEquals("", MinecraftVersionGroup.javaVersionSegment(""))
    }
}
