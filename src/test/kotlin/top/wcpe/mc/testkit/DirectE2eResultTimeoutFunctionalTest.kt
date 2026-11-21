package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 场景级结果等待上限 `resultTimeoutSeconds` 的执行期回归锚：直连 `e2e<Key>` 必须按场景声明的秒数等结果文件，
 * 而不是框架默认的 600 秒。
 *
 * 探针后端只占端口、始终不写结果文件。场景声明 3 秒上限时，任务应很快失败并报出这个秒数；
 * 若编排仍按 600 秒等，耗时断言与用例超时会让它失败，而不是静默变慢。
 */
class DirectE2eResultTimeoutFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val silentProbeJar: File by lazy { createSilentProbeJar() }

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile?.mkdirs() }.writeText(text)

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    @DisplayName("到了场景声明的结果等待上限仍无结果时，直连 e2e 按该秒数失败")
    fun directE2eFailsAtScenarioResultTimeout() {
        val port = allocatePort()
        write("settings.gradle.kts", """rootProject.name = "result-timeout-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") {
                    port = $port
                    env("PROBE_PORTS", "$port")
                    env("PROBE_EXIT_MILLIS", "240000")
                }
                scenario("slow") {
                    backend = "s1"
                    resultTimeoutSeconds = 3
                }
            }
            """.trimIndent(),
        )

        val startedAt = System.nanoTime()
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitGradleUserHome)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("MC_TESTKIT_E2E_PAPER_JAR" to silentProbeJar.absolutePath))
            .withArguments("e2eSlow")
            .buildAndFail()
        val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)

        assertEquals(TaskOutcome.FAILED, result.task(":e2eSlow")?.outcome, result.output)
        assertTrue(
            result.output.contains("在 3s 内未写出结果文件"),
            "失败信息应报出场景声明的 3 秒上限\n---- 输出 ----\n${result.output}",
        )
        assertTrue(elapsedSeconds < 200, "应按 3 秒上限失败，实际耗时 ${elapsedSeconds}s")
    }

    /** 生成含 `Main-Class` 的最小探针 jar（由它扮演迟迟不收尾的服务端，无需下载）。 */
    private fun createSilentProbeJar(): File {
        val classPath = listOf(codeSourceFile(SilentProbeMain::class.java), codeSourceFile(Unit::class.java))
            .map { it.toURI().toString() }
            .joinToString(" ")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = SilentProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        val target = File("build/silent-probe-${System.nanoTime()}.jar").canonicalFile.apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream(), manifest).use { }
        return target
    }

    private fun codeSourceFile(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())

    private fun allocatePort(): Int {
        for (candidate in 27300..27800) {
            try {
                ServerSocket(candidate).use { return it.localPort }
            } catch (ignored: java.io.IOException) {
                // 端口被占用，试下一个
            }
        }
        error("无法在 27300-27800 内分配到空闲端口")
    }
}

/** 测试用探针：只占端口、始终不写结果文件，到 `PROBE_EXIT_MILLIS` 才退出。 */
object SilentProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sockets = System.getenv("PROBE_PORTS").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { ServerSocket(it) }
        try {
            Thread.sleep(System.getenv("PROBE_EXIT_MILLIS")?.toLongOrNull() ?: 500L)
        } finally {
            sockets.forEach(ServerSocket::close)
        }
    }
}
