package top.wcpe.mc.testkit.provision

import com.pty4j.PtyProcess
import com.pty4j.WinSize
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** pty4j 跨平台启动、尺寸调整与真实探测测试。 */
class PseudoTerminalTest {

    @Test
    @DisplayName("builder 应返回 PtyProcess 并应用默认窗口尺寸")
    @Timeout(30)
    fun builderStartsPtyProcessWithInitialSize() {
        assumeTrue(PseudoTerminal.isAvailable(), "当前环境没有可用的 pty4j 原生后端")
        val process = startSleepProcess()
        try {
            assertTrue(process.javaClass.name.contains("PtyProcess"), "pty4j 应返回具体 PtyProcess 实现")
            assertEquals(WinSize(PseudoTerminal.DEFAULT_COLS, PseudoTerminal.DEFAULT_ROWS), process.winSize)
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("resize 应使用 WinSize 更新跨平台伪终端尺寸")
    @Timeout(30)
    fun resizeUpdatesPtyWindowSize() {
        assumeTrue(PseudoTerminal.isAvailable(), "当前环境没有可用的 pty4j 原生后端")
        val process = startSleepProcess()
        try {
            assertTrue(PseudoTerminal.resize(process, rows = 33, cols = 121))
            assertEquals(WinSize(121, 33), process.winSize)
            assertFalse(PseudoTerminal.resize(process, rows = 0, cols = 121))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("可用性探测失败时应记录中文日志并返回不可用")
    @Timeout(30)
    fun availabilityFailureIsReported() {
        val messages = mutableListOf<String>()
        val available = PseudoTerminal.isAvailable(messages::add) {
            throw IllegalStateException("原生后端不可用")
        }
        assertFalse(available)
        assertTrue(messages.single().contains("不可用"), "失败日志应说明回退：${messages.single()}")
    }

    @Test
    @DisplayName("可用性探测应依据退出码与 version 输出判定")
    @Timeout(30)
    fun availabilityUsesProbeResult() {
        assertTrue(
            PseudoTerminal.isAvailable { PseudoTerminal.CommandResult(0, "openjdk version 17") },
        )
        assertFalse(
            PseudoTerminal.isAvailable { PseudoTerminal.CommandResult(1, "探测失败") },
        )
        assertFalse(
            PseudoTerminal.isAvailable { PseudoTerminal.CommandResult(0, "") },
        )
    }

    @Test
    @DisplayName("真实 pty4j 探测应成功或给出中文回退日志")
    @Timeout(60)
    fun realProbeReportsAvailability() {
        val messages = mutableListOf<String>()
        val available = PseudoTerminal.isAvailable(messages::add)
        assertTrue(available || messages.any { it.contains("pty4j") || it.contains("PTY") })
    }

    private fun startSleepProcess(): PtyProcess {
        val classpath = System.getProperty("java.class.path")
        return PseudoTerminal.start(
            command = listOf(javaExecutable(), "-cp", classpath, PtySleepProbeMain::class.java.name),
            directory = File(System.getProperty("java.io.tmpdir")),
            environment = System.getenv(),
        )
    }
}

/** 为尺寸测试提供一个跨平台、可控时长的子进程。 */
object PtySleepProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(30_000)
    }
}
