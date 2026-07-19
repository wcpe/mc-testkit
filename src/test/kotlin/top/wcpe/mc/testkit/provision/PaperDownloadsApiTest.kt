package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PaperDownloadsApi] 解析与 URL 读取单元测试（FR-02）。
 *
 * 喂固定的 PaperMC Fill v3 响应样本文本，校验最新构建号 / 下载产物 / 下载 URL 解析；
 * 取文本用注入替身（零网络）。样本字段对齐 PaperMC `projects/<p>/versions/<v>/builds/latest` 与
 * `.../builds/<b>` 的真实结构（精简到本模块读取的字段）。
 */
class PaperDownloadsApiTest {

    /** `projects/paper/versions/1.20.1/builds/latest` 样本。 */
    private val latestBuildResponse = """
        {
          "id": 196,
          "channel": "STABLE"
        }
    """.trimIndent()

    /** `projects/paper/versions/1.20.1/builds/196` 样本。 */
    private val buildResponse = """
        {
          "id": 196,
          "downloads": {
            "server:default": {
              "name": "paper-1.20.1-196.jar",
              "checksums": {
                "sha256": "abc123def456"
              },
              "url": "https://fill-data.papermc.io/v1/objects/abc123def456/paper-1.20.1-196.jar"
            }
          }
        }
    """.trimIndent()

    @Test
    @DisplayName("解析最新构建对象时应返回构建 ID")
    fun parseLatestBuildId() {
        assertEquals(196, PaperDownloadsApi.parseLatestBuild(latestBuildResponse))
    }

    @Test
    @DisplayName("解析构建响应时应返回产物名、sha256 与下载 URL")
    fun parseDownloadMetadata() {
        val download = PaperDownloadsApi.parseDownload(buildResponse)
        assertEquals("paper-1.20.1-196.jar", download.name)
        assertEquals("abc123def456", download.sha256)
        assertEquals("https://fill-data.papermc.io/v1/objects/abc123def456/paper-1.20.1-196.jar", download.url)
    }

    @Test
    @DisplayName("获取下载 URL 时应返回 Fill 响应中的对象存储地址")
    fun returnObjectStorageUrlFromDownloadMetadata() {
        val api = PaperDownloadsApi(fetchText = { error("不应发网络") })
        val download = PaperDownload(
            name = "paper-1.20.1-196.jar",
            sha256 = "abc123def456",
            url = "https://fill-data.papermc.io/v1/objects/abc123def456/paper-1.20.1-196.jar",
        )
        val url = api.downloadUrl(download)
        assertEquals(
            "https://fill-data.papermc.io/v1/objects/abc123def456/paper-1.20.1-196.jar",
            url,
        )
    }

    @Test
    @DisplayName("获取最新构建时应请求 builds latest 端点")
    fun requestLatestBuildEndpointWhenFetchingBuild() {
        var requestedUrl: String? = null
        val api = PaperDownloadsApi(fetchText = { url ->
            requestedUrl = url
            latestBuildResponse
        })
        assertEquals(196, api.latestBuild("paper", "1.20.1"))
        assertEquals("https://fill.papermc.io/v3/projects/paper/versions/1.20.1/builds/latest", requestedUrl)
    }

    @Test
    @DisplayName("最新构建对象缺少 ID 时应抛出中文错误")
    fun rejectLatestBuildWithoutIdWithChineseError() {
        val ex = assertFailsWith<IllegalStateException> {
            PaperDownloadsApi.parseLatestBuild("""{"channel":"STABLE"}""")
        }
        assertTrue(ex.message!!.contains("id"), "应提示缺少 id：${ex.message}")
    }

    @Test
    @DisplayName("构建响应缺少服务端下载时应抛出中文错误")
    fun rejectMissingServerDownloadWithChineseError() {
        val ex = assertFailsWith<IllegalStateException> {
            PaperDownloadsApi.parseDownload("""{"downloads":{}}""")
        }
        assertTrue(ex.message!!.contains("server:default"), "应提示缺少 server 下载：${ex.message}")
    }
}
