package top.wcpe.mc.testkit.provision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [BungeeCordJenkinsApi] 解析与 URL 拼接单元测试（FR-02）。
 *
 * 喂固定的 SpigotMC Jenkins 响应样本文本，校验最新成功构建号解析与下载 URL；零网络。
 */
class BungeeCordJenkinsApiTest {

    /** `job/BungeeCord/api/json?tree=lastSuccessfulBuild[number]` 样本。 */
    private val jobResponse = """{"lastSuccessfulBuild":{"number":1820}}"""

    @Test
    fun `解析最新成功构建号`() {
        assertEquals(1820, BungeeCordJenkinsApi.parseLastSuccessfulBuild(jobResponse))
    }

    @Test
    fun `拼构建产物下载 URL`() {
        val api = BungeeCordJenkinsApi(fetchText = { error("不应发网络") })
        assertEquals(
            "https://hub.spigotmc.org/jenkins/job/BungeeCord/1820/artifact/bootstrap/target/BungeeCord.jar",
            api.downloadUrl(1820),
        )
    }

    @Test
    fun `经注入替身解析走预期 Jenkins 端点`() {
        var requestedUrl: String? = null
        val api = BungeeCordJenkinsApi(fetchText = { url ->
            requestedUrl = url
            jobResponse
        })
        assertEquals(1820, api.lastSuccessfulBuild())
        assertTrue(requestedUrl!!.startsWith("https://hub.spigotmc.org/jenkins/job/BungeeCord/api/json"))
    }

    @Test
    fun `无成功构建抛中文错误`() {
        val ex = assertFailsWith<IllegalStateException> {
            BungeeCordJenkinsApi.parseLastSuccessfulBuild("""{"lastSuccessfulBuild":null}""")
        }
        assertTrue(ex.message!!.contains("lastSuccessfulBuild"), "应提示无成功构建：${ex.message}")
    }
}
