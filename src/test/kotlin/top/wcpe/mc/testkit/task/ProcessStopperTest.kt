package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.bot.botPidFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收尾编排（[stopTrackedProcesses]）单测（进程生命周期与收尾 高风险区）。
 *
 * 覆盖两条路径的分工：**pid 文件**（当前一轮的快路径）与**进程台账**（跨轮次逃逸的兜底路径）。
 * 最关键的用例是「pid 文件已被删除、进程仍在跑」——那正是 `stop<Key>Serve` 收尾不掉孤儿进程的真实场景，
 * 台账路径必须能把它找回来；同时「身份不符」时必须拒杀，不能误伤无关进程。
 *
 * 子进程用 JVM 自身派生（不依赖 node），身份特征取入口脚本路径。
 */
class ProcessStopperTest {

    @TempDir
    lateinit var resultsDir: File

    private fun currentJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return File(File(javaHome, "bin"), exe).absolutePath
    }

    private fun spawnLongLivedChild(workDir: File, name: String = "StopperSleeper"): Pair<Process, String> {
        val source = File(workDir, "$name.java")
        source.writeText(
            """
            public class $name {
                public static void main(String[] args) throws Exception { Thread.sleep(300000L); }
            }
            """.trimIndent(),
        )
        val pb = ProcessBuilder(currentJavaExecutable(), source.absolutePath)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(workDir, "$name.log")))
        return pb.start() to source.absolutePath
    }

    private fun assertStops(process: Process) {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "进程应被收尾结束")
        assertTrue(!process.isAlive, "收尾后进程不应再存活")
    }

    @Test
    @DisplayName("pid 文件存在时应按 pid 收尾并删除该文件")
    fun pidFilePathStopsProcess() {
        val child = spawnLongLivedChild(resultsDir)
        val pidFile = botPidFile(resultsDir, "smoke")
        pidFile.writeText(child.first.pid().toString())
        try {
            val summary = stopTrackedProcesses(resultsDir, listOf(pidFile), logger = {})

            assertStops(child.first)
            assertEquals(1, summary.stopped)
            assertEquals(0, summary.pidFileMissing)
            assertTrue(!pidFile.exists(), "收尾后 pid 文件应被删除")
        } finally {
            child.first.destroyForcibly()
        }
    }

    @Test
    @DisplayName("pid 文件已被删除时台账应把逃逸进程收尾（本改动的核心场景）")
    fun ledgerRecoversEscapedProcessWithoutPidFile() {
        val child = spawnLongLivedChild(resultsDir)
        try {
            // 模拟真实逃逸：台账有记录（起服时写入），但 pid 文件已被下一轮起服 / 运行目录清理抹掉
            ProcessLedger.record(resultsDir, "backend/paper1201", child.first.pid(), 25565)
            val missingPidFile = botPidFile(resultsDir, "smoke")
            assertTrue(!missingPidFile.exists(), "前提：pid 文件不存在")

            val summary = stopTrackedProcesses(resultsDir, listOf(missingPidFile), logger = {})

            assertStops(child.first)
            assertEquals(1, summary.stopped, "应收到尾 1 个进程")
            assertEquals(1, summary.ledgerStopped, "且应记为台账兜底")
            assertEquals(1, summary.pidFileMissing, "pid 文件缺失应被计数")
        } finally {
            child.first.destroyForcibly()
        }
    }

    @Test
    @DisplayName("台账身份与存活进程不符时应拒杀并清理该记录（防 pid 复用误杀）")
    fun ledgerRefusesMismatchedIdentity() {
        val child = spawnLongLivedChild(resultsDir)
        try {
            ProcessLedger.record(resultsDir, "backend/paper1201", child.first.pid(), 25565)
            // 篡改命令行，模拟「原进程已退出、pid 被无关进程占用」
            val file = ProcessLedger.file(resultsDir)
            file.writeText(
                file.readText().replace(
                    Regex("(?m)^(backend/paper1201\\.commandLine)=.*$"),
                    "$1=/usr/bin/无关进程",
                ),
            )

            val summary = stopTrackedProcesses(resultsDir, emptyList(), logger = {})

            assertTrue(child.first.isAlive, "身份不符时不得杀进程")
            assertEquals(0, summary.stopped)
            assertEquals(1, summary.ledgerSkipped, "应记为跳过")
            assertTrue(ProcessLedger.records(resultsDir).isEmpty(), "陈旧记录应被清理")
        } finally {
            child.first.destroyForcibly()
            child.first.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("台账记录指向已退出进程时应清理记录且不计入收尾")
    fun ledgerCleansStaleRecordOfExitedProcess() {
        val unlikelyPid = 2_000_000_000L
        if (ProcessHandle.of(unlikelyPid).isPresent) {
            return
        }
        ProcessLedger.record(resultsDir, "backend/paper1201", unlikelyPid, 25565)

        val summary = stopTrackedProcesses(resultsDir, emptyList(), logger = {})

        assertEquals(0, summary.stopped)
        assertTrue(ProcessLedger.records(resultsDir).isEmpty(), "已退出进程的记录应被清理")
    }

    @Test
    @DisplayName("无 pid 文件且无台账时应安全 no-op")
    fun emptyStateIsSafeNoOp() {
        val summary = stopTrackedProcesses(resultsDir, listOf(botPidFile(resultsDir, "absent")), logger = {})

        assertEquals(0, summary.stopped)
        assertEquals(1, summary.pidFileMissing)
        assertEquals(0, summary.ledgerStopped)
        assertEquals(0, summary.ledgerSkipped)
    }

    @Test
    @DisplayName("多个 pid 文件应逐个收尾并汇总计数")
    fun multiplePidFilesAreAggregated() {
        val first = spawnLongLivedChild(resultsDir, "StopperSleeperA")
        val second = spawnLongLivedChild(resultsDir, "StopperSleeperB")
        val firstPidFile = botPidFile(resultsDir, "bot-a")
        val secondPidFile = botPidFile(resultsDir, "bot-b")
        firstPidFile.writeText(first.first.pid().toString())
        secondPidFile.writeText(second.first.pid().toString())
        try {
            val summary = stopTrackedProcesses(resultsDir, listOf(firstPidFile, secondPidFile), logger = {})

            assertStops(first.first)
            assertStops(second.first)
            assertEquals(2, summary.stopped)
        } finally {
            first.first.destroyForcibly()
            second.first.destroyForcibly()
        }
    }

    @Test
    @DisplayName("端口复验应识别出仍在监听的端口")
    fun portsStillOccupiedDetectsListeningPort() {
        val socket = java.net.ServerSocket(0)
        try {
            val occupied = portsStillOccupied(listOf(socket.localPort))
            assertEquals(listOf(socket.localPort), occupied)
        } finally {
            socket.close()
        }
    }

    @Test
    @DisplayName("端口复验对未监听端口应返回空")
    fun portsStillOccupiedIgnoresFreePort() {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()

        assertTrue(portsStillOccupied(listOf(port)).isEmpty(), "未监听的端口不应被判为占用")
    }
}
