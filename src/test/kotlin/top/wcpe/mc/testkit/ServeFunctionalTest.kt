package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 持久手测（serve，FR-17，ADR-0011）以消费者视角驱动的集成测试：serve 声明注册 `serve<Key>` /
 * `stop<Key>Serve`，非法 serve 配置期中文报错（不联网 / 不起进程，真实起服挂住属实机维度）。
 */
class ServeFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun `serve 声明注册 serveX 与 stopXServe 任务`() {
        write("settings.gradle.kts", """rootProject.name = "serve-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1") }
                serve("dev") {
                    backend = "s1"
                    via = "wf"
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--all", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
        assertTrue(result.output.contains("serveDev"), "应注册 serve 任务 serveDev")
        assertTrue(result.output.contains("stopDevServe"), "应注册 serve 收尾任务 stopDevServe")
    }

    @Test
    fun `serve 直连默认后端注册 serveX 任务`() {
        write("settings.gradle.kts", """rootProject.name = "serve-default"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { port = 25565 }
                serve("sandbox")
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--all")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
        assertTrue(result.output.contains("serveSandbox"), "应注册 serveSandbox 任务（直连默认后端）")
    }

    @Test
    fun `serve 引用不存在后端配置期中文报错`() {
        write("settings.gradle.kts", """rootProject.name = "bad-serve"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { port = 25565 }
                serve("dev") { backend = "nope" }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(
            result.output.contains("serve") && result.output.contains("不存在"),
            "serve 引用不存在后端应在配置期抛中文错误",
        )
    }
}
