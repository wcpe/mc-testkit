package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ProvisionPlatform.downloadVersion] 版本粒度归一单元测试（内置下载与运行，首个消费者验证 实机暴露的版本推导缝）。
 *
 * 锁定：Waterfall 在 PaperMC 仅按 major.minor 发布，完整 MC 版本（1.20.1）须截到 1.20 再请求，
 * 否则 `.../projects/waterfall/versions/1.20.1` 返回 404；Paper/Folia/Velocity 原样不截。
 */
class ProvisionPlatformTest {

    @Test
    @DisplayName("Waterfall 处理完整版本时应截取主版本与次版本")
    fun truncateWaterfallPatchVersion() {
        assertEquals("1.20", ProvisionPlatform.WATERFALL.downloadVersion("1.20.1"))
        assertEquals("1.21", ProvisionPlatform.WATERFALL.downloadVersion("1.21.4"))
    }

    @Test
    @DisplayName("Waterfall 处理主次版本时应保持结果不变")
    fun keepWaterfallMajorMinorVersionIdempotent() {
        assertEquals("1.20", ProvisionPlatform.WATERFALL.downloadVersion("1.20"))
    }

    @Test
    @DisplayName("Paper 与 Folia 处理版本时应保留完整版本号")
    fun preserveCompleteBackendVersion() {
        assertEquals("1.20.1", ProvisionPlatform.PAPER.downloadVersion("1.20.1"))
        assertEquals("1.20.1", ProvisionPlatform.FOLIA.downloadVersion("1.20.1"))
        // 26.x 新版号：Paper/Folia 仍按完整版本请求 Fill API
        assertEquals("26.2", ProvisionPlatform.PAPER.downloadVersion("26.2"))
        assertEquals("26.2", ProvisionPlatform.FOLIA.downloadVersion("26.2"))
    }

    @Test
    @DisplayName("Waterfall 处理 26.x 完整版本时应截到 major.minor")
    fun truncateWaterfallNewSchemePatchVersion() {
        assertEquals("26.2", ProvisionPlatform.WATERFALL.downloadVersion("26.2"))
        assertEquals("26.2", ProvisionPlatform.WATERFALL.downloadVersion("26.2.1"))
    }

    @Test
    @DisplayName("Velocity 处理自有版本时应保留原始版本号")
    fun preserveVelocityVersion() {
        assertEquals("3.3.0-SNAPSHOT", ProvisionPlatform.VELOCITY.downloadVersion("3.3.0-SNAPSHOT"))
    }

    @Test
    @DisplayName("Spigot 使用固定版本公共构件地址并保留回退顺序")
    fun resolveSpigotGetBukkitSource() {
        assertEquals(
            listOf(
                "https://download.getbukkit.org/spigot/spigot-1.20.1.jar",
                "https://github.com/BaldGang/spigot-build/releases/latest/download/spigot-1.20.1.jar",
            ),
            ProvisionPlatform.SPIGOT.downloadUrls("1.20.1"),
        )
    }
}
