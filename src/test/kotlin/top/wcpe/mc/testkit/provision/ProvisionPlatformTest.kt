package top.wcpe.mc.testkit.provision

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ProvisionPlatform.downloadVersion] 版本粒度归一单元测试（FR-02，FR-08 实机暴露的版本推导缝）。
 *
 * 锁定：Waterfall 在 PaperMC 仅按 major.minor 发布，完整 MC 版本（1.20.1）须截到 1.20 再请求，
 * 否则 `.../projects/waterfall/versions/1.20.1` 返回 404；Paper/Folia/Velocity 原样不截。
 */
class ProvisionPlatformTest {

    @Test
    fun `Waterfall 把完整 MC 版本截到 major_minor`() {
        assertEquals("1.20", ProvisionPlatform.WATERFALL.downloadVersion("1.20.1"))
        assertEquals("1.21", ProvisionPlatform.WATERFALL.downloadVersion("1.21.4"))
    }

    @Test
    fun `Waterfall 已是 major_minor 时幂等`() {
        assertEquals("1.20", ProvisionPlatform.WATERFALL.downloadVersion("1.20"))
    }

    @Test
    fun `后端 Paper 与 Folia 保留完整版本不截断`() {
        assertEquals("1.20.1", ProvisionPlatform.PAPER.downloadVersion("1.20.1"))
        assertEquals("1.20.1", ProvisionPlatform.FOLIA.downloadVersion("1.20.1"))
    }

    @Test
    fun `Velocity 自有版本号原样保留`() {
        assertEquals("3.3.0-SNAPSHOT", ProvisionPlatform.VELOCITY.downloadVersion("3.3.0-SNAPSHOT"))
    }
}
