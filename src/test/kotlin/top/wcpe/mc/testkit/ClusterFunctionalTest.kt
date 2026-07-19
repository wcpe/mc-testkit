package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 集群编排（FR-10）以消费者视角驱动的集成测试：集群场景注册 `e2e<Key>Cluster` / `stop<Key>Cluster`，
 * 非法集群配置期中文报错（不联网 / 不起进程，真实跑通属实机维度）。
 */
class ClusterFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    @DisplayName("集群场景配置完成后应注册执行与收尾任务")
    fun registerClusterLifecycleTasks() {
        write("settings.gradle.kts", """rootProject.name = "cluster-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                backend("s2") { platform = paper; version = "1.20.1"; port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("crossServer") {
                    backends("s1", "s2")
                    via = "wf"
                    bot { username = "Switcher"; action = "cross-server" }
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
        assertTrue(result.output.contains("e2eCrossServerCluster"), "应注册集群任务 e2eCrossServerCluster")
        assertTrue(result.output.contains("stopCrossServerCluster"), "应注册集群收尾任务 stopCrossServerCluster")
    }

    @Test
    @DisplayName("集群场景缺少代理引用时应在配置期中文报错")
    fun rejectClusterScenarioWithoutProxy() {
        write("settings.gradle.kts", """rootProject.name = "bad-cluster"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { port = 25565 }
                backend("s2") { port = 25566 }
                scenario("x") { backends("s1", "s2") }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(
            result.output.contains("必须经代理") || result.output.contains("via"),
            "集群场景缺 via 应在配置期抛中文错误",
        )
    }
}
