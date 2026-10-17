package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 依赖插件**产物来源**（`dependencies { plugin(from = …) }`，ADR-0026）的 TestKit 集成测试。
 *
 * 覆盖 ADR-0026 的对外行为：
 * 1. 产物来源被框架**自动接线**：产物任务进入 prepare 的任务图，且先于 prepare 产出，
 *    消费方无需手写任何 `dependsOn`；
 * 2. 与字符串形式 `plugin("…")` **可混用**（依赖插件可以有多个，来源形式各自独立）。
 *
 * 只验证**配置期与任务图**契约：用 `--dry-run` 触发配置，不真跑 prepare
 * （不下载服务端、不起服）——真实注入属实机维度。
 */
class PluginArtifactSourceFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** 依赖插件产物任务的落点文件名：fixture 里显式钉死，便于在输出里定位。 */
    private val harnessJarFileName = "consumer-e2e-harness.jar"

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments.toList() + "--stacktrace")

    /**
     * 写入「用 `plugin(from = …)` 声明桩插件产物来源」的消费者工程。
     *
     * 桩产物用 `Jar` 任务手工构造（`destinationDirectory` + `archiveFileName` 显式钉死），
     * 与真实消费方「桩是独立子工程、由其 jar 任务产出」的形态一致。
     */
    private fun writePluginArtifactSourceConsumer() {
        write("settings.gradle.kts", """rootProject.name = "plugin-artifact-source-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                id("java")
                id("top.wcpe.mc-testkit")
            }

            // 模拟「桩插件」产物：真实消费方这里是独立子工程的 jar 任务。
            tasks.register<Jar>("e2eHarnessJar") {
                archiveFileName.set("$harnessJarFileName")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
            }

            mcTestkit {
                backend("s1") {
                    platform = paper
                    version = "1.20.1"
                    port = 25565
                }
                scenario("smoke")
                dependencies {
                    // ADR-0026 推荐形态：传携带任务信息的 TaskProvider，框架据此自动接线
                    plugin(from = tasks.named("e2eHarnessJar"))
                    // 与字符串形式混用：另一个依赖插件仍按路径声明
                    plugin("build/libs/another-dependency.jar")
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    @DisplayName("声明依赖插件产物来源后 prepare 任务图应包含该产物任务且先于 prepare 产出")
    fun wirePluginArtifactSourceTaskBeforePrepare() {
        writePluginArtifactSourceConsumer()

        // dry-run：只构任务图、按依赖顺序打印将执行的任务，不执行任务体（不下载、不起服）
        val output = runner("prepareE2eSmoke", "--dry-run").build().output

        val artifactIdx = output.indexOf(":e2eHarnessJar ")
        val prepareIdx = output.indexOf(":prepareE2eSmoke ")

        assertTrue(
            artifactIdx >= 0,
            "产物任务 :e2eHarnessJar 应进入任务图（声明来源即自动接线）\n---- 输出 ----\n$output",
        )
        assertTrue(
            prepareIdx >= 0,
            "dry-run 应列出 :prepareE2eSmoke\n---- 输出 ----\n$output",
        )
        assertTrue(
            artifactIdx < prepareIdx,
            "产物任务应先于 prepareE2eSmoke 产出\n---- 输出 ----\n$output",
        )
    }

    @Test
    @DisplayName("产物来源与路径形式混用时配置期应成功且两种声明都保留")
    fun acceptPluginArtifactSourceAlongsidePathForm() {
        writePluginArtifactSourceConsumer()

        // help 触发完整配置期校验；混用不应产生任何互斥错误。
        // 若配置失败，GradleRunner.build() 会抛异常，测试即失败——这本身就是「混用被接受」的证据。
        val output = runner("help").build().output

        assertTrue(
            "BUILD SUCCESSFUL" in output,
            "混用两种形式应配置成功\n---- 输出 ----\n$output",
        )
    }
}
