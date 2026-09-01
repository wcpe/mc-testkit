package top.wcpe.mc.testkit.task

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 任务自动编排的 TestKit 集成测试：以真实消费者视角驱动插件。
 *
 * 只验证**配置期**契约——任务按命名约定注册（[top.wcpe.mc.testkit.contract.McTestkitTaskNames]）、
 * `tasks`/`help` 成功、任务依赖关系存在、非法拓扑配置期中文报错。**不真跑**任何任务体
 * （不下载、不起服、不连服）——真实起服/起代理/起 bot 判定属 首个消费者验证 实机维度。
 */
class TaskOrchestrationFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    /** 写入一个声明了「单后端 + 代理 + 直连场景 + 经代理场景 + bot 场景」的完整消费者工程。 */
    private fun writeFullConsumer() {
        write("settings.gradle.kts", """rootProject.name = "consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                id("top.wcpe.mc-testkit")
            }

            mcTestkit {
                backend("s1") {
                    platform = paper
                    version = "1.20.1"
                    port = 25565
                }
                proxy("wf") {
                    platform = waterfall
                    port = 25577
                    routesTo("s1")
                }
                scenario("smoke")
                scenario("buySuccess") {
                    backend = "s1"
                    bot {
                        username = "BuyBot"
                        action = "buy-success"
                    }
                }
                scenario("buyViaProxy") {
                    backend = "s1"
                    via = "wf"
                    bot {
                        username = "ProxyBot"
                        action = "buy-via-proxy"
                    }
                }
                dependencies {
                    pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
                }
            }
            """.trimIndent(),
        )
    }

    /** 运行 `tasks --all` 拿到任务列表文本（不真跑 e2e 任务）。 */
    private fun runTasks(): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--all", "--stacktrace")
            .build()
            .output

    @Test
    @DisplayName("完整声明消费者工程后应按命名约定注册全部 E2E 任务且 tasks 执行成功")
    fun registerAllE2eTasksForCompleteConsumer() {
        writeFullConsumer()
        val output = runTasks()

        // 固定名任务（McTestkitTaskNames 常量）
        assertTrue("npmInstallE2eBot" in output, "应注册固定名任务 npmInstallE2eBot")
        assertTrue("syncE2eRuntimeCache" in output, "应注册固定名任务 syncE2eRuntimeCache")
        assertTrue("purgeE2eRuntimeCache" in output, "应注册固定名任务 purgeE2eRuntimeCache")

        // smoke：无 bot，仅 prepare + e2e
        assertTrue("prepareE2eSmoke" in output, "应注册 prepareE2eSmoke")
        assertTrue("e2eSmoke" in output, "应注册 e2eSmoke")

        // buySuccess：有 bot
        assertTrue("prepareE2eBuySuccess" in output, "应注册 prepareE2eBuySuccess")
        assertTrue("e2eBuySuccess" in output, "应注册 e2eBuySuccess")
        assertTrue("launchBuySuccessBot" in output, "应注册 launchBuySuccessBot")
        assertTrue("e2eBuySuccessWithBot" in output, "应注册 e2eBuySuccessWithBot")

        // buyViaProxy：经代理
        assertTrue("e2eBuyViaProxyViaWf" in output, "应注册经代理任务 e2eBuyViaProxyViaWf")
    }

    @Test
    @DisplayName("完整声明消费者工程后 help 任务应执行成功")
    fun runHelpSuccessfully() {
        writeFullConsumer()
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--stacktrace")
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    @DisplayName("E2E 任务应依赖对应的 prepare 任务并按顺序出现在任务图中")
    fun wireE2eTaskToPrepareTask() {
        writeFullConsumer()
        // dryRun：只构建任务图、按依赖顺序打印，不真正执行任务体（不下载、不起服）
        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2eSmoke", "--dry-run", "--stacktrace")
            .build()
            .output

        // dry-run 会按依赖拓扑顺序列出将执行的任务：prepareE2eSmoke 必须排在 e2eSmoke 之前
        val prepareIdx = output.indexOf(":prepareE2eSmoke ")
        val verifyIdx = output.indexOf(":e2eSmoke ")
        assertTrue(prepareIdx >= 0, "dry-run 应列出 :prepareE2eSmoke\n$output")
        assertTrue(verifyIdx >= 0, "dry-run 应列出 :e2eSmoke\n$output")
        assertTrue(prepareIdx < verifyIdx, "prepareE2eSmoke 应排在 e2eSmoke 之前\n$output")
    }

    @Test
    @DisplayName("withBot 任务应依赖 launch 与 verify 任务")
    fun wireWithBotTaskToLaunchAndVerifyTasks() {
        writeFullConsumer()
        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2eBuySuccessWithBot", "--dry-run", "--stacktrace")
            .build()
            .output

        assertTrue(":launchBuySuccessBot " in output, "withBot 应触发 launchBuySuccessBot\n$output")
        assertTrue(":e2eBuySuccess " in output, "withBot 应触发 e2eBuySuccess\n$output")
    }

    @Test
    @DisplayName("经代理场景任务应以停止代理任务收尾")
    fun finalizeProxyScenarioWithStopProxyTask() {
        writeFullConsumer()
        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2eBuyViaProxyViaWf", "--dry-run", "--stacktrace")
            .build()
            .output

        // 经代理任务必须 finalizedBy 一个停代理任务（收尾保证不残留占端口）
        assertTrue(
            "stopProxy" in output || "StopProxy" in output,
            "经代理任务应以停代理任务收尾（finalizedBy）\n$output",
        )
    }

    @Test
    @DisplayName("代理路由目标不存在时应在配置期输出中文错误")
    fun rejectMissingProxyRouteTargetDuringConfiguration() {
        write("settings.gradle.kts", """rootProject.name = "consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper }
                proxy("wf") {
                    platform = waterfall
                    routesTo("nope")
                }
                scenario("smoke")
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--stacktrace")
            .buildAndFail()

        assertTrue("路由目标后端" in result.output && "不存在" in result.output, result.output)
    }

    @Test
    @DisplayName("场景引用后端不存在时应在配置期输出中文错误")
    fun rejectMissingScenarioBackendDuringConfiguration() {
        write("settings.gradle.kts", """rootProject.name = "consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper }
                scenario("buy") {
                    backend = "missing"
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--stacktrace")
            .buildAndFail()

        assertTrue("场景" in result.output && "不存在" in result.output, result.output)
    }

    @Test
    @DisplayName("后端名称重复时应在配置期输出中文错误")
    fun rejectDuplicateBackendNamesDuringConfiguration() {
        write("settings.gradle.kts", """rootProject.name = "consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper }
                backend("s1") { platform = folia }
                scenario("smoke")
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--stacktrace")
            .buildAndFail()

        assertTrue("后端名重复" in result.output, result.output)
    }
}
