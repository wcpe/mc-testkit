package top.wcpe.mc.testkit.task

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.MavenServerProbeMain
import top.wcpe.mc.testkit.bot.ProcessLedger
import java.io.File
import java.net.ServerSocket
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * 直连场景的前台后端也要登记进程台账：构建被强杀时它会比构建活得久，之后的停任务只能靠台账找到它。
 *
 * 用探针 jar（[MavenServerProbeMain]）充当服务端：写出 PASS 结果后自停，不需要真实 Paper。
 */
@Timeout(180)
class DirectBackendProcessFunctionalTest {
    @TempDir
    lateinit var dir: File

    @Test
    @DisplayName("直连场景跑完：后端登记在进程台账里，运行目录里的 pid 文件已删")
    fun directBackendIsRecordedInLedgerAndItsPidFileRemoved() {
        val port = allocatePort()
        File(dir, "settings.gradle").writeText("rootProject.name='direct-backend'\n")
        File(dir, "build.gradle").writeText(
            """
            plugins { id 'top.wcpe.mc-testkit' }
            mcTestkit {
              backend('s1') { s ->
                s.port = $port
                s.env('PROBE_PORTS', '$port')
                s.env('PROBE_EXIT_MILLIS', '500')
              }
              scenario('smoke') { s -> s.backend = 's1' }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("MC_TESTKIT_E2E_PAPER_JAR" to probeServerJar().absolutePath))
            .withArguments("e2eSmoke", "--offline", "--configuration-cache", "--console=plain")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":e2eSmoke")?.outcome, result.output.takeLast(2000))
        val record = ProcessLedger.records(File(dir, "build/mc-testkit/results")).singleOrNull { it.key == "backend/s1" }
        assertNotNull(record, "直连后端应登记进程台账：${result.output.takeLast(2000)}")
        assertEquals(port, record.port)
        assertFalse(File(dir, "build/mc-testkit/run-s1/s1.pid").exists(), "后端结束后 pid 文件应删除")
    }

    /** 探针服务端 jar：清单指向测试类与 Kotlin 标准库所在位置，自身不带任何类。 */
    private fun probeServerJar(): File {
        val classPath = listOf(MavenServerProbeMain::class.java, Unit::class.java)
            .joinToString(" ") { File(it.protectionDomain.codeSource.location.toURI()).toURI().toString() }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = MavenServerProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        return File(dir, "probe-server.jar").also { jar -> JarOutputStream(jar.outputStream(), manifest).use { } }
    }

    /** 在保留段内挑一个当前空闲的端口，避开内核动态分配池与常见业务端口。 */
    private fun allocatePort(): Int {
        for (candidate in 26700..27200) {
            try {
                ServerSocket(candidate).use { return it.localPort }
            } catch (ignored: java.io.IOException) {
                // 被占就试下一个
            }
        }
        error("无法在 26700-27200 内分配到空闲端口")
    }
}
