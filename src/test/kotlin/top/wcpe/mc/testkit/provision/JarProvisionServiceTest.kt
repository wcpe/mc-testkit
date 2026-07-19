package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JarProvisionService] 下载完整性校验与缓存移入单元测试（FR-02；review J2 / sha256 守卫）。
 *
 * 用注入的 paperApi（固定 JSON）+ download（写已知内容）替身，不打网络：验证 sha256 不匹配抛中文错误且
 * 不留缓存文件；下载成功则原子移入缓存且内容完整（每用例独立缓存根，避免串扰）。
 */
class JarProvisionServiceTest {

    private val paper = ProvisionPlatform.fromId("paper")

    private fun freshCache(): JarCache = JarCache(File("build/provision-svc-test-${System.nanoTime()}"))

    /** paperApi 替身：builds/latest 响应给 id=1，builds/1 响应给指定 sha256。 */
    private fun paperApiWithSha(sha: String): PaperDownloadsApi = PaperDownloadsApi(
        fetchText = { url ->
            if (url.endsWith("/builds/1")) {
                """{"downloads":{"server:default":{"name":"paper.jar","checksums":{"sha256":"$sha"},"url":"https://example.test/paper.jar"}}}"""
            } else {
                """{"id":1,"channel":"STABLE"}"""
            }
        },
    )

    @Test
    @DisplayName("sha256 不匹配时应抛出中文错误且不留下缓存文件")
    fun rejectSha256MismatchWithoutLeavingCacheFile() {
        val cache = freshCache()
        // 声明的 sha256 与实际下载内容不符，触发完整性校验失败
        val service = JarProvisionService(
            cache = cache,
            paperApi = paperApiWithSha("00ff00ff"),
            download = { _, dest, _ -> dest.writeText("被篡改 / 损坏的内容") },
        )
        val ex = assertFailsWith<IllegalStateException> { service.resolve(paper, "1.20.1") }
        assertTrue(ex.message!!.contains("sha256 校验失败"), "应为中文 sha256 校验错误：${ex.message}")
        assertFalse(cache.jarFile(paper, "1.20.1", 1).exists(), "校验失败不应把产物留进缓存")
    }

    @Test
    @DisplayName("下载成功时应原子移入缓存并保持内容完整")
    fun moveSuccessfulDownloadIntoCacheWithIntactContent() {
        val cache = freshCache()
        val content = "完整且校验通过的 jar 内容"
        // 用同一 sha256 扩展算出期望值，保证与实现的 hash 格式一致
        val tmp = File.createTempFile("sha-", ".bin").apply {
            writeText(content)
            deleteOnExit()
        }
        val service = JarProvisionService(
            cache = cache,
            paperApi = paperApiWithSha(tmp.sha256()),
            download = { _, dest, _ -> dest.writeText(content) },
        )
        val resolved = service.resolve(paper, "1.20.1")
        assertTrue(resolved.isFile, "应返回缓存中的完整 jar")
        assertEquals(content, resolved.readText(), "缓存内容应与下载内容一致（原子移入，无损坏）")
    }
}
