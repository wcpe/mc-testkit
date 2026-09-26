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
 * 场景生命周期钩子的消费方视角功能测试（ADR-0020）。
 *
 * **为什么用 GradleRunner 而非 ProjectBuilder**：钩子任务在插件的 `afterEvaluate` 里注册，
 * `ProjectBuilder` 不跑该阶段，看不到任务——此前若用它测会得到「任务不存在」的假象。
 *
 * 覆盖两件容易出错的事：
 * 1. **钩子任务的接线**——`before<Key>Scenario` / `after<Key>Scenario` 按声明生成，且**不依赖不存在的任务**。
 *    此前实现让 before 钩子硬接 `prepareE2e<Key>`，而**集群场景不生成该任务**，导致集群场景一声明
 *    `beforeScenario` 就报 `Task with path 'prepareE2e…' not found`（真机消费 Lodestone 时暴露）。
 * 2. **形态兼容性校验**——`readyScenario` 只在集群场景可用，其他形态须配置期中文报错（不静默忽略）。
 */
class ScenarioHookFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    /** 写一个最小消费方工程；`dsl` 为 `mcTestkit { }` 的内容。 */
    private fun writeConsumer(dsl: String) {
        write("settings.gradle.kts", """rootProject.name = "hook-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }

            mcTestkit {
                backend("s1") { port = 25571 }
                backend("s2") { port = 25572 }
                proxy("wf") { port = 25577; routesTo("s1", "s2") }
            $dsl
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    @Test
    @DisplayName("集群场景声明钩子后生成 before/after 任务，且不依赖不存在的 prepare 任务")
    fun clusterScenarioHookTasksAreWiredWithoutPrepare() {
        writeConsumer(
            """
                scenario("full") {
                    backends("s1", "s2"); via = "wf"
                    beforeScenario { }
                    afterScenario { }
                }
            """.trimIndent(),
        )

        // 用 tasks 列出全部任务：若依赖图含不存在的任务，Gradle 在解析任务集时即失败
        val result = runner("tasks", "--all")
        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome, "任务列举应成功（依赖图可解析）")

        val output = result.output
        assertTrue(output.contains("beforeFullScenario"), "应生成场景前钩子任务")
        assertTrue(output.contains("afterFullScenario"), "应生成场景后钩子任务")
        // 集群场景不生成 prepareE2e<Key>：钩子若依赖它会解析失败（本用例的回归点）
        assertTrue(!output.contains("prepareE2eFull"), "集群场景不应出现 prepareE2eFull")
    }

    @Test
    @DisplayName("直连场景的钩子挂在 prepare 之后（该形态有 prepare 任务）")
    fun directScenarioHookDependsOnPrepare() {
        writeConsumer(
            """
                scenario("direct") {
                    backend = "s1"
                    beforeScenario { }
                }
            """.trimIndent(),
        )
        val result = runner("tasks", "--all")
        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
        val output = result.output
        assertTrue(output.contains("prepareE2eDirect"), "直连场景应生成 prepareE2eDirect")
        assertTrue(output.contains("beforeDirectScenario"), "应生成场景前钩子任务")
    }

    @Test
    @DisplayName("直连场景声明 readyScenario 时配置期报中文错误（该形态无「节点就绪」时刻）")
    fun directScenarioRejectsReadyHook() {
        writeConsumer(
            """
                scenario("direct") {
                    backend = "s1"
                    readyScenario { }
                }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks")
            .buildAndFail()
        val output = result.output
        assertTrue(output.contains("readyScenario"), "报错应指明 readyScenario，实为：${output.takeLast(600)}")
        assertTrue(output.contains("集群"), "报错应说明仅集群场景支持")
    }

    @Test
    @DisplayName("集群场景声明 readyScenario 不报错（该形态支持）")
    fun clusterScenarioAcceptsReadyHook() {
        writeConsumer(
            """
                scenario("full") {
                    backends("s1", "s2"); via = "wf"
                    readyScenario { }
                }
            """.trimIndent(),
        )
        val result = runner("tasks", "--all")
        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome, "集群形态应接受 readyScenario")
        assertTrue(result.output.contains("e2eFullCluster"), "集群场景任务应正常生成")
    }
}
