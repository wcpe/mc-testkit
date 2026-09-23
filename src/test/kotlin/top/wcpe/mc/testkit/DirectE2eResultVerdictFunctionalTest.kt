package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 直连 `e2e<Key>` 的结果判定回归锚（机器人驱动与结果判定）。
 *
 * 架构不变量：**编排只认结果文件判 PASS/FAIL**，绝不因「文件写出了」就判通过。本测试用同一个探针 jar
 * （经 env 切换写 `PASS` / `FAIL`）覆盖判定两个方向——尤其 FAIL 方向：桩写 `status=FAIL` 时
 * `e2e<Key>` **必须失败**。
 *
 * 之所以单列一类：既有 e2e 功能测试全部只覆盖 PASS 路径，一旦判定被旁路（历史事故：接线时误删
 * `verifyScenarioResult`）不会有任何测试报警，属最危险的「假绿」盲区。
 */
class DirectE2eResultVerdictFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** 探针 jar 落点（服务端 jar 走既有 `*_JAR` 逃生口指向它）；整个类共享一个。 */
    private val probeJar: File by lazy { createProbeJar() }

    private fun file(relativePath: String): File = File(projectDir, relativePath).apply { parentFile?.mkdirs() }

    private fun write(name: String, text: String) = file(name).writeText(text)

    @Test
    @DisplayName("桩写 FAIL 时直连 e2e 必须失败并报出桩给的原因")
    fun failVerdictMakesDirectE2eFail() {
        writeConsumerBuild(port = allocatePort(), probeStatus = "FAIL", probeMessage = "业务断言未通过-sentinel")

        val result = runner("e2eSmoke").buildAndFail()

        // 判定不得旁路：任务本身必须 FAILED，而不是「成功但输出里恰好有字样」
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":e2eSmoke")?.outcome,
            "桩写 FAIL 必须让 e2eSmoke 任务失败（实际 outcome=${result.task(":e2eSmoke")?.outcome}）\n" +
                "---- 输出 ----\n${result.output}",
        )
        assertTrue(
            result.output.contains("业务断言未通过-sentinel"),
            "失败应把桩写的 message 报出来\n---- 输出 ----\n${result.output}",
        )
    }

    @Test
    @DisplayName("桩写 PASS 时直连 e2e 应成功（反向对照，证明前一项非恒真）")
    fun passVerdictMakesDirectE2eSucceed() {
        writeConsumerBuild(port = allocatePort(), probeStatus = "PASS", probeMessage = "业务断言通过-sentinel")

        val result = runner("e2eSmoke").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":e2eSmoke")?.outcome, result.output)
        assertTrue(
            result.output.contains("业务断言通过-sentinel"),
            "通过时应打印桩写的 message\n---- 输出 ----\n${result.output}",
        )
    }

    /** 最小消费方工程：服务端 jar 走既有 `*_JAR` 逃生口（探针 jar），探针按 env 写指定 status。 */
    private fun writeConsumerBuild(port: Int, probeStatus: String, probeMessage: String) {
        write("settings.gradle.kts", """rootProject.name = "verdict-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") {
                    port = $port
                    env("PROBE_PORTS", "$port")
                    env("PROBE_EXIT_MILLIS", "15000")
                    env("PROBE_RESULT_STATUS", "$probeStatus")
                    env("PROBE_RESULT_MESSAGE", "$probeMessage")
                }
                scenario("smoke") { backend = "s1" }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(File("build/verdict-test-kit-${System.nanoTime()}").canonicalFile)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("MC_TESTKIT_E2E_PAPER_JAR" to probeJar.absolutePath))
            .withArguments(arguments.toList())

    /** 生成含 `Main-Class` 的最小探针 jar（真实服务端由它扮演，无需下载）。 */
    private fun createProbeJar(): File {
        val classPath = listOf(codeSourceFile(VerdictProbeMain::class.java), codeSourceFile(Unit::class.java))
            .map { it.toURI().toString() }
            .joinToString(" ")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = VerdictProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        val target = File("build/verdict-probe-${System.nanoTime()}.jar").canonicalFile.apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream(), manifest).use { }
        return target
    }

    private fun codeSourceFile(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())

    private fun allocatePort(): Int {
        for (candidate in 26700..27200) {
            try {
                ServerSocket(candidate).use { return it.localPort }
            } catch (ignored: java.io.IOException) {
                // 端口被占用，试下一个
            }
        }
        error("无法在 26700-27200 内分配到空闲端口")
    }
}

/** 测试用探针：按 env 写指定 status/message 的结果文件，监听端口后自停。 */
object VerdictProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sockets = System.getenv("PROBE_PORTS").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { ServerSocket(it) }
        try {
            writeResultFile()
            Thread.sleep(System.getenv("PROBE_EXIT_MILLIS")?.toLongOrNull() ?: 500L)
        } finally {
            sockets.forEach(ServerSocket::close)
        }
    }

    private fun writeResultFile() {
        val resultPath = System.getenv("MC_TESTKIT_E2E_RESULT_FILE")?.takeIf(String::isNotBlank) ?: return
        val status = System.getenv("PROBE_RESULT_STATUS")?.takeIf(String::isNotBlank) ?: "PASS"
        val message = System.getenv("PROBE_RESULT_MESSAGE").orEmpty()
        val path = Path.of(resultPath)
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            "status=$status\nmessage=$message\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
