package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ServerLauncher] 启动助手单元测试（FR-02）。
 *
 * 不连真实服务端 / 代理（那属 FR-08 实机维度）。这里用一个**自带 main 的极小可运行 jar**
 * （现造一个会立即退出的 JVM 程序）验证：以子进程 `java -jar` 启动、写 pid 文件、日志落盘、
 * cwd 为运行目录、进程能正常起止。覆盖跨平台进程启动 / pid 落盘这一高风险区的正常路径。
 */
class ServerLauncherTest {

    @Test
    @DisplayName("解析 pid 文件时应使用服务键命名")
    fun resolvePidFileUsingServerKey() {
        val dir = File("build/test-run-dir")
        assertEquals(File(dir, "s1.pid"), provisionPidFile(dir, "s1"))
    }

    @Test
    @DisplayName("解析 Java 可执行文件时应返回有效名称")
    fun resolveExistingJavaExecutable() {
        val exe = javaExecutable()
        // 要么是 java.home/bin 下存在的绝对路径，要么回退为裸名 java/java.exe
        assertTrue(exe.endsWith("java") || exe.endsWith("java.exe"), "应为 java 可执行：$exe")
    }

    @Test
    @DisplayName("启动不存在的 jar 时应抛出中文错误")
    fun rejectMissingJarWithChineseError() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ServerLauncher.launch(
                jar = File("build/no-such.jar"),
                runDirectory = File("build/test-run-x"),
                key = "s1",
            )
        }
        assertTrue(ex.message!!.contains("不存在"), "应提示 jar 不存在：${ex.message}")
    }

    @Test
    @DisplayName("启动可运行 jar 时应写入 pid 文件与日志")
    fun launchRunnableJarAndWritePidAndLog() {
        val workDir = File("build/test-launch-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "node1",
            serverArgs = listOf("ok"),
        )
        try {
            // pid 文件写入且内容为该进程 pid
            val pidFile = provisionPidFile(workDir, "node1")
            assertTrue(pidFile.isFile, "pid 文件应写入：${pidFile.absolutePath}")
            assertEquals(process.pid().toString(), pidFile.readText().trim())

            // 进程应能在合理时间内自行退出（极小程序立即退出）
            val exited = process.waitFor(30, TimeUnit.SECONDS)
            assertTrue(exited, "极小程序应已退出")
            assertEquals(0, process.exitValue())

            // 日志文件落在运行目录、且捕获到程序输出
            val logFile = File(workDir, "node1.log")
            assertTrue(logFile.isFile, "日志文件应落在运行目录：${logFile.absolutePath}")
            assertTrue(logFile.readText().contains("MC_TESTKIT_LAUNCH_OK"), "应捕获到子进程输出")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("记录启动日志时不应泄露环境变量值")
    fun avoidLoggingEnvironmentVariableValues() {
        val workDir = File("build/test-launch-env-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))
        val messages = mutableListOf<String>()
        val environmentValue = "environment-value-sentinel"

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "node-env",
            environment = mapOf("NODE_ENV_SENTINEL" to environmentValue),
            logger = messages::add,
        )
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertTrue(messages.isNotEmpty())
            assertTrue(messages.none { environmentValue in it }, "ServerLauncher 日志不得打印环境变量值")
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * 现造一个会立即退出的可运行 jar：Manifest 的 `Main-Class` 指向本测试模块已编译的辅助入口
     * [LaunchProbeMain]，并把其 .class 打入 jar。
     *
     * [LaunchProbeMain] 是 Kotlin 代码，运行时需 kotlin-stdlib；故同时把 stdlib 与本测试模块的
     * classes 目录经 `Class-Path` 清单项接进来（从入口类与某 Kotlin 类的代码源定位），使
     * `java -jar` 能加载到入口与其依赖，独立子进程内正常退出（退出码 0）。
     */
    private fun createImmediateExitJar(target: File): File {
        val entryClassRoot = codeSourceFile(LaunchProbeMain::class.java)
        val kotlinStdlibJar = codeSourceFile(Unit::class.java)
        val classPath = listOf(entryClassRoot, kotlinStdlibJar)
            .map { it.toURI().toString() }
            .joinToString(" ")

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = LaunchProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { /* 仅清单即可，入口经 Class-Path 加载 */ }
        return target
    }

    /** 定位某类的代码源（.class 所在的目录或 jar 文件）。 */
    private fun codeSourceFile(clazz: Class<*>): File =
        File(clazz.protectionDomain.codeSource.location.toURI())
}

/**
 * 测试辅助入口：作为临时可运行 jar 的 `Main-Class`。
 *
 * 打印约定字符串后立即退出（退出码 0），供 [ServerLauncherTest] 验证子进程启动 / 日志捕获 / 收尾。
 */
object LaunchProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("MC_TESTKIT_LAUNCH_OK args=${args.joinToString(",")}")
    }
}
