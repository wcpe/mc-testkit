package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
