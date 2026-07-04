package top.wcpe.mc.testkit.provision

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [WaterfallModuleProvisioner] 回归测试：Waterfall 运行期模块不能再依赖已 sunset 的 PaperMC v2 自下载。 */
class WaterfallModuleProvisionerTest {

    @Test
    fun `预下载 Waterfall 模块到代理运行目录 modules`() {
        val runDir = File("build/waterfall-modules-test-${System.nanoTime()}")
        val contents = mapOf(
            "https://example.test/cmd_server.jar" to "cmd_server 模块内容",
            "https://example.test/cmd_alert.jar" to "cmd_alert 模块内容",
        )
        val api = PaperDownloadsApi(fetchText = { url ->
            when {
                url.endsWith("/builds") -> """[{"id":578,"channel":"STABLE"}]"""
                url.endsWith("/builds/578") -> buildResponse(contents)
                else -> error("不应请求：$url")
            }
        })
        val downloaded = mutableListOf<String>()
        val provisioner = WaterfallModuleProvisioner(
            api = api,
            download = { url, dest, _ ->
                downloaded += url
                dest.writeText(contents.getValue(url))
            },
        )

        provisioner.provision(version = "1.20", runDirectory = runDir)

        assertEquals(setOf("https://example.test/cmd_server.jar", "https://example.test/cmd_alert.jar"), downloaded.toSet())
        assertTrue(runDir.resolve("modules/cmd_server.jar").isFile, "应预置 /server 命令模块，避免 Waterfall 运行期请求旧 v2 API")
        assertTrue(runDir.resolve("modules/cmd_alert.jar").isFile, "应预置 Fill 响应中的其它模块，避免启动期重复失败重试")
    }

    private fun buildResponse(contents: Map<String, String>): String = """
        {
          "downloads": {
            "server:default": {
              "name": "waterfall-1.20-578.jar",
              "checksums": { "sha256": "server" },
              "url": "https://example.test/waterfall.jar"
            },
            "module:cmd_server": {
              "name": "cmd_server-1.20-578.jar",
              "checksums": { "sha256": "${shaOf(contents.getValue("https://example.test/cmd_server.jar"))}" },
              "url": "https://example.test/cmd_server.jar"
            },
            "module:cmd_alert": {
              "name": "cmd_alert-1.20-578.jar",
              "checksums": { "sha256": "${shaOf(contents.getValue("https://example.test/cmd_alert.jar"))}" },
              "url": "https://example.test/cmd_alert.jar"
            }
          }
        }
    """.trimIndent()

    private fun shaOf(content: String): String {
        val file = File.createTempFile("waterfall-module-sha", ".txt")
        file.writeText(content)
        return file.sha256().also { file.delete() }
    }
}
