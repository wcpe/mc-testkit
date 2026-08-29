package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VelocityPrepareRuntimeFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `Velocity 场景准备目录已启用 modern forwarding 并写入共享密钥`() {
        file("backend-template/config/paper-global.yml").writeText(
            """
            proxies:
              velocity:
                enabled: false
                online-mode: false
                secret: ''
            """.trimIndent() + "\n",
        )
        file("backend-template/server.properties").writeText("server-port=25565\n")
        val paperJar = file("paper-sentinel.jar")
        val pluginJar = file("plugin-sentinel.jar")
        writeConsumerBuild(pluginJar)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("MC_TESTKIT_E2E_PAPER_JAR" to paperJar.absolutePath))
            .withArguments("prepareE2eVelocityPrepareSentinel", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareE2eVelocityPrepareSentinel")?.outcome)
        val runtimeConfig = file("build/mc-testkit/run/config/paper-global.yml").readText()
        assertTrue(Regex("(?m)^\\s*enabled:\\s*true\\s*$").containsMatchIn(runtimeConfig), runtimeConfig)
        assertTrue(runtimeConfig.contains(McTestkitDefaults.VELOCITY_FORWARDING_SECRET), runtimeConfig)
    }

    private fun writeConsumerBuild(pluginJar: File) {
        file("settings.gradle.kts").writeText("rootProject.name = \"velocity-prepare-sentinel\"\n")
        file("build.gradle.kts").writeText(
            """
            plugins { id("top.wcpe.mc-testkit") }

            mcTestkit {
                backend("paper-backend") {
                    platform = paper
                    version = "1.20.1"
                    templateDirectory("backend-template")
                }
                proxy("velocity-proxy") {
                    platform = velocity
                    routesTo("paper-backend")
                }
                scenario("velocity-prepare-sentinel") {
                    backend = "paper-backend"
                    via = "velocity-proxy"
                }
                dependencies {
                    pluginUnderTest = "${pluginJar.invariantSeparatorsPath}"
                }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun file(path: String): File = File(projectDir, path).apply {
        parentFile?.mkdirs()
        if (!exists()) createNewFile()
    }
}
