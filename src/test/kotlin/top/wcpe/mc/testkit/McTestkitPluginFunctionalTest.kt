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
 * 以真实消费者视角驱动插件（Gradle TestKit，集成测试）。
 *
 * 验证：消费方 `plugins { id("top.wcpe.mc-testkit") }` 应用插件、用 `mcTestkit { }` DSL
 * 声明完整拓扑（后端/代理/路由/场景/机器人/依赖），配置期不报错、`help` 成功。
 */
class McTestkitPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    @DisplayName("消费者应用插件并配置完整 DSL 后应成功执行 help")
    fun configureConsumerProjectSuccessfully() {
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
                dependencies {
                    pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    @DisplayName("工程评估完成后再应用插件应显式失败而非静默丢弃声明")
    fun applyAfterProjectEvaluatedFailsLoudly() {
        write("settings.gradle.kts", """rootProject.name = "late-apply-consumer"""")
        write(
            "build.gradle.kts",
            """
            import top.wcpe.mc.testkit.dsl.McTestkitExtension

            plugins {
                java
                // apply false：先把插件类加载进构建脚本类路径，但不在 plugins 块立即应用
                id("top.wcpe.mc-testkit") apply false
            }

            // 晚挂载：等到工程评估完成才 apply，并在 apply 之后声明 DSL。
            // 这是本框架**刻意不支持**的用法（见 CHANGELOG「已知限制」）：
            // 装配依赖 project.afterEvaluate 等 mcTestkit { } 求值完，而晚挂载时
            // 声明必然发生在装配之后——即便不崩，声明也会被丢弃。
            gradle.projectsEvaluated {
                project.pluginManager.apply("top.wcpe.mc-testkit")
                val ext = project.extensions.getByType(McTestkitExtension::class.java)
                ext.backend("s1", { port = 25565 })
                ext.scenario("smoke", { backend = "s1" })
            }
            """.trimIndent(),
        )

        // 断言「显式失败」而不是「静默继续」：崩溃信息与框架有关、可归因，
        // 而静默丢弃声明会让消费方以为配置生效，直到跑 E2E 才发现没跑起来。
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--stacktrace")
            .buildAndFail()

        assertTrue(
            result.output.contains("Cannot run Project.afterEvaluate(Action) when the project is already evaluated"),
            "晚挂载应以 Gradle 的 afterEvaluate 时机错误显式失败，实际输出：\n${result.output}",
        )
    }
}
