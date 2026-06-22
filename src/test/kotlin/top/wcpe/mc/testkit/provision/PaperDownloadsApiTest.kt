package top.wcpe.mc.testkit.provision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PaperDownloadsApi] 解析与 URL 拼接单元测试（FR-02）。
 *
 * 喂固定的 PaperMC v2 响应样本文本，校验最新构建号 / 下载产物 / 下载 URL 解析；
 * 取文本用注入替身（零网络）。样本字段对齐 PaperMC `projects/<p>/versions/<v>` 与
 * `.../builds/<b>` 的真实结构（精简到本模块读取的字段）。
 */
class PaperDownloadsApiTest {

    /** `projects/paper/versions/1.20.1` 样本（builds 升序，最后一个为最新）。 */
    private val versionResponse = """
        {
          "project_id": "paper",
          "project_name": "Paper",
          "version": "1.20.1",
          "builds": [1, 2, 195, 196]
        }
    """.trimIndent()

    /** `projects/paper/versions/1.20.1/builds/196` 样本。 */
    private val buildResponse = """
        {
          "project_id": "paper",
          "version": "1.20.1",
          "build": 196,
          "downloads": {
            "application": {
              "name": "paper-1.20.1-196.jar",
              "sha256": "abc123def456"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `解析最新构建号取数组末位`() {
        assertEquals(196, PaperDownloadsApi.parseLatestBuild(versionResponse))
    }

    @Test
    fun `解析下载产物名与 sha256`() {
        val download = PaperDownloadsApi.parseDownload(buildResponse)
        assertEquals("paper-1.20.1-196.jar", download.name)
        assertEquals("abc123def456", download.sha256)
    }

    @Test
    fun `拼下载 URL 对齐 PaperMC 路径形态`() {
        val api = PaperDownloadsApi(fetchText = { error("不应发网络") })
        val download = PaperDownload("paper-1.20.1-196.jar", "abc123def456")
        val url = api.downloadUrl("paper", "1.20.1", 196, download)
        assertEquals(
            "https://api.papermc.io/v2/projects/paper/versions/1.20.1/builds/196/downloads/paper-1.20.1-196.jar",
            url,
        )
    }

    @Test
    fun `经注入替身解析最新构建走预期端点路径`() {
        var requestedUrl: String? = null
        val api = PaperDownloadsApi(fetchText = { url ->
            requestedUrl = url
            versionResponse
        })
        assertEquals(196, api.latestBuild("paper", "1.20.1"))
        assertEquals("https://api.papermc.io/v2/projects/paper/versions/1.20.1", requestedUrl)
    }

    @Test
    fun `空构建列表抛中文错误`() {
        val ex = assertFailsWith<IllegalStateException> {
            PaperDownloadsApi.parseLatestBuild("""{"builds":[]}""")
        }
        assertTrue(ex.message!!.contains("builds 为空"), "应提示 builds 为空：${ex.message}")
    }

    @Test
    fun `缺少 application 下载抛中文错误`() {
        val ex = assertFailsWith<IllegalStateException> {
            PaperDownloadsApi.parseDownload("""{"downloads":{}}""")
        }
        assertTrue(ex.message!!.contains("application"), "应提示缺少 application：${ex.message}")
    }
}
