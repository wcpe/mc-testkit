package top.wcpe.mc.testkit.bot

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 进程收尾单元测试（机器人驱动与结果判定 高风险区：进程生命周期 / 收尾 / 跨平台）。
 *
 * 重点覆盖各分支：pid 文件缺失 / 内容非法 / 进程已退出均安全 no-op；
 * 对一个真实自存活子进程能温和退出（必要时强杀）回收并删除 pid 文件。
 * 子进程用 JVM 自身（[currentJavaExecutable]）派生，**不依赖 node**，保证无 Node 的 CI 也能跑收尾分支。
 */
class ProcessReaperTest {

    private fun tempDir(): File = Files.createTempDirectory("mc-testkit-reaper").toFile()

    /** 当前运行 JVM 的 java 可执行（跨平台：Windows 为 java.exe）。 */
    private fun currentJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val exe = if (isWindows) "java.exe" else "java"
        return File(File(javaHome, "bin"), exe).absolutePath
    }

    /**
     * 派生一个会存活一段时间的子进程（睡 5 分钟，远超测试），返回其 [Process]。
     *
     * 用 JDK 11+ 单源文件启动器 `java Sleeper.java`（跨平台、无需先编译、不依赖 node）：
     * 把一个最小的长睡程序写到临时目录再运行，测试随后主动收尾它。
     */
    private fun spawnLongLivedChild(workDir: File): Process {
        val sleeperSource = File(workDir, "Sleeper.java")
        sleeperSource.writeText(
            """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(300000L);
                }
            }
            """.trimIndent(),
        )
        val pb = ProcessBuilder(currentJavaExecutable(), sleeperSource.absolutePath)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(workDir, "sleeper.log")))
        return pb.start()
    }

    @Test
    @DisplayName("PID 文件缺失时应安全忽略且不抛异常")
    fun missingPidFileIsIgnoredSafely() {
        val dir = tempDir()
        val pidFile = File(dir, "absent.pid")
        assertFalse(pidFile.exists())

        // 不应抛异常
        stopProcessByPidFile(pidFile)

        assertFalse(pidFile.exists(), "缺失的 pid 文件不应被创建")
    }

    @Test
    @DisplayName("PID 文件内容非法时应安全忽略并删除文件")
    fun invalidPidFileIsDeletedSafely() {
        val dir = tempDir()
        val pidFile = File(dir, "garbage.pid")
        pidFile.writeText("not-a-number\n")

        stopProcessByPidFile(pidFile)

        assertFalse(pidFile.exists(), "非法内容的 pid 文件应被清理")
    }

    @Test
    @DisplayName("PID 文件指向不存在的进程时应安全忽略并删除文件")
    fun stalePidFileIsDeletedSafely() {
        val dir = tempDir()
        val pidFile = File(dir, "stale.pid")
        // 选一个极不可能存在的 pid；若偶然存在则跳过断言（避免误杀）
        val unlikelyPid = 2_000_000_000L
        if (ProcessHandle.of(unlikelyPid).isPresent) {
            return
        }
        pidFile.writeText(unlikelyPid.toString())

        stopProcessByPidFile(pidFile)

        assertFalse(pidFile.exists(), "陈旧 pid 文件应被清理")
    }

    @Test
    @DisplayName("收尾真实存活子进程后应结束进程并删除 PID 文件")
    fun liveChildProcessIsStoppedAndPidFileDeleted() {
        val dir = tempDir()
        val pidFile = File(dir, "child.pid")
        val child = spawnLongLivedChild(dir)
        try {
            pidFile.writeText(child.pid().toString())
            assertTrue(child.isAlive, "子进程启动后应存活")

            stopProcessByPidFile(pidFile)

            // 收尾后子进程应在合理时间内结束
            val exited = child.waitFor(20, TimeUnit.SECONDS)
            assertTrue(exited, "子进程应被收尾结束")
            assertFalse(child.isAlive, "收尾后子进程不应再存活")
            assertFalse(pidFile.exists(), "收尾后 pid 文件应被删除")
        } finally {
            // 兜底：测试失败时也不留孤儿进程
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("收尾进程时应通过注入的日志回调记录进程")
    fun stoppedProcessIsReportedThroughLogger() {
        val dir = tempDir()
        val pidFile = File(dir, "logged.pid")
        val child = spawnLongLivedChild(dir)
        val logs = mutableListOf<String>()
        try {
            pidFile.writeText(child.pid().toString())

            stopProcessByPidFile(pidFile) { line -> logs += line }

            child.waitFor(20, TimeUnit.SECONDS)
            assertTrue(logs.any { it.contains(child.pid().toString()) }, "日志应记录被结束的 pid：$logs")
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
