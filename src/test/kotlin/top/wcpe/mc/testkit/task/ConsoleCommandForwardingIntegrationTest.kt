package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import top.wcpe.mc.testkit.provision.ServerLauncher
import top.wcpe.mc.testkit.provision.provisionPidFile
import java.io.ByteArrayInputStream
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 控制台命令转发与**真实子进程**的集成测试：验证 `serve` 那条路径真的能把命令送进服务端 stdin。
 *
 * 用 [ServerLauncher] 起一个「读 stdin、把收到的命令写进日志」的探针 JVM，再经
 * [startConsoleCommandPump] 投递命令——即 serve 任务体用的同一套原语与同一套启动配置
 * （stdout 落 `<key>.log`、stdin 不重定向）。断言命令确实到达子进程，闭合「修好了」的证据链。
 *
 * 不做真实服务端起服（需联网下载 jar，属实机维度）。
 */
class ConsoleCommandForwardingIntegrationTest {

    @Test
    @DisplayName("经 ServerLauncher 起的子进程应收到转发的控制台命令")
    @Timeout(120)
    fun deliverCommandToLaunchedServerProcess() {
        val workDir = File("build/test-console-pump-${System.nanoTime()}").apply { mkdirs() }
        val jar = createConsoleEchoJar(workDir)

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "console-probe",
        )
        try {
            // 走 serve 同款原语：终端输入 → 子进程 stdin
            val pump = startConsoleCommandPump(
                source = ByteArrayInputStream("say HELLO_CONSOLE_CMD\n".toByteArray()),
                sink = process.outputStream,
                targetAlive = { process.isAlive },
            )
            pump.join(30_000)

            // 等子进程把收到的命令回显到日志（stdout 落 <key>.log）
            val logFile = File(workDir, "console-probe.log")
            val received = waitForLogContent(logFile, "GOT_CMD=say HELLO_CONSOLE_CMD", timeoutMs = 30_000)
            assertTrue(received, "子进程应收到转发的控制台命令。日志：${logFile.takeIf(File::exists)?.readText()}")

            // pid 文件应已落盘（收尾依赖它）
            assertTrue(provisionPidFile(workDir, "console-probe").isFile, "pid 文件应写入")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
        assertEquals(0, process.exitValue().let { if (it == 143 || it == 137) 0 else it }, "进程应被干净收尾")
    }

    /** 轮询日志文件，直到出现期望内容或超时。 */
    private fun waitForLogContent(logFile: File, expected: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (logFile.exists() && logFile.readText().contains(expected)) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * 造一个可运行的「控制台探针」jar：入口读 stdin 逐行回显到 stdout（stdout 由启动器落日志文件）。
     *
     * 与 [top.wcpe.mc.testkit.provision.ServerLauncherTest] 同款做法：入口类是 Kotlin 代码，
     * 故经 `Class-Path` 把测试模块 classes 与 kotlin-stdlib 接进独立子进程。
     */
    private fun createConsoleEchoJar(workDir: File): File {
        val target = File(workDir, "console-probe.jar")
        val classPath = listOf(codeSourceFile(ConsoleEchoProbeMain::class.java), codeSourceFile(Unit::class.java))
            .map { it.toURI().toString() }
            .joinToString(" ")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = ConsoleEchoProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { output ->
            val resourceName = ConsoleEchoProbeMain::class.java.name.replace('.', '/') + ".class"
            val bytes = ConsoleEchoProbeMain::class.java.classLoader.getResourceAsStream(resourceName)!!.use { it.readBytes() }
            output.putNextEntry(JarEntry(resourceName))
            output.write(bytes)
            output.closeEntry()
        }
        return target
    }

    /** 定位某类的代码源（.class 所在的目录或 jar 文件）。 */
    private fun codeSourceFile(clazz: Class<*>): File =
        File(clazz.protectionDomain.codeSource.location.toURI())
}

/**
 * 控制台探针入口：模拟服务端控制台——从 stdin 逐行读命令，回显到 stdout（由启动器落日志文件）。
 *
 * 读到 `stop` 或 stdin 结束即退出，供 [ConsoleCommandForwardingIntegrationTest] 断言命令确实到达子进程。
 */
object ConsoleEchoProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("CONSOLE_PROBE_READY")
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            println("GOT_CMD=$line")
            if (line == "stop") break
        }
        println("CONSOLE_PROBE_EXIT")
    }
}
