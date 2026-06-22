package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 压测编排（FR-11）以消费者视角驱动的集成测试：压测场景注册 `e2e<Key>Stress` / `stop<Key>Stress`，
 * 非法压测配置期中文报错（不联网 / 不起进程，真实跑通属实机维度）。
 */
class StressFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun `压测场景注册 e2eXStress 与 stopXStress 任务`() {
        write("settings.gradle.kts", """rootProject.name = "stress-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                backend("s2") { platform = paper; version = "1.20.1"; port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("continuous-stress") {
                    backends("s1", "s2")
                    via = "wf"
                    stress { botsPerServer = 4; durationSeconds = 30 }
                    bot { username = "Stress"; action = "continuous-stress" }
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
        assertTrue(result.output.contains("e2eContinuousStressStress"), "应注册压测任务 e2eContinuousStressStress")
        assertTrue(result.output.contains("stopContinuousStressStress"), "应注册压测收尾任务 stopContinuousStressStress")
    }

    @Test
    fun `压测 botsPerServer 非正配置期中文报错`() {
        write("settings.gradle.kts", """rootProject.name = "bad-stress"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { port = 25565 }
                scenario("load") {
                    backends("s1")
                    stress { botsPerServer = 0; durationSeconds = 30 }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(
            result.output.contains("botsPerServer") || result.output.contains("压测"),
            "压测 botsPerServer 非正应配置期中文报错",
        )
    }
}
