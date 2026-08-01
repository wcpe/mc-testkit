package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bot 版本范围校验单元测试（FR-21）。
 *
 * 验证 [MinecraftVersionGroup.isBotSupported] 的决策：
 * - 1.7.10（< 1.8）不支持 bot E2E → 跳过 bot 启动 + 告警，scenario 仍继续。
 * - 1.8.8+ 正常启动 bot。
 *
 * 实际跳过逻辑在 `McTestkitTasks.launchBots` / `launchStressBotsForServer` 中接线，
 * 这里测其依赖的纯函数决策（不耦合 Gradle `Project`）。
 */
class BotVersionGuardTest {

    @Test
    @DisplayName("1.7.10 不支持 bot E2E（应跳过 bot 启动）")
    fun legacy1710DoesNotSupportBot() {
        assertFalse(MinecraftVersionGroup.isBotSupported("1.7.10"), "1.7.10 不支持 bot E2E")
    }

    @Test
    @DisplayName("1.8+ 支持 bot E2E（应正常启动 bot）")
    fun modernVersionsSupportBot() {
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8"), "1.8 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8.8"), "1.8.8 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.12.2"), "1.12.2 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.16.5"), "1.16.5 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.17.1"), "1.17.1 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.19.4"), "1.19.4 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.20.1"), "1.20.1 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.21.1"), "1.21.1 支持 bot")
    }

    @Test
    @DisplayName("边界版本 1.8 应支持 bot、1.7.x 不支持")
    fun boundaryVersionIsCorrect() {
        assertFalse(MinecraftVersionGroup.isBotSupported("1.7"), "1.7 不支持 bot")
        assertFalse(MinecraftVersionGroup.isBotSupported("1.7.10"), "1.7.10 不支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8"), "1.8 支持 bot")
        assertTrue(MinecraftVersionGroup.isBotSupported("1.8.8"), "1.8.8 支持 bot")
    }
}
