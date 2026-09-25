package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 控制台命令转发（[startConsoleCommandPump]）单元测试。
 *
 * 覆盖修的这一处：serve 挂住后终端敲的命令要能到达子进程 stdin（此前完全没接线，敲 `stop` 无反应）。
 * 用内存流即可穷举各结束路径，不起真实服务端（真实起服属实机维度）。
 */
class ConsoleCommandPumpTest {

    private fun awaitPump(pump: Thread, timeoutMs: Long = 5000) {
        pump.join(timeoutMs)
        assertFalse(pump.isAlive, "转发线程应在输入结束后自行退出（不残留线程）")
    }

    @Test
    @DisplayName("应逐行转发命令并保持输入顺序")
    @Timeout(30)
    fun forwardCommandsLineByLineInOrder() {
        val source = ByteArrayInputStream("say hello\nlist\nstop\n".toByteArray(StandardCharsets.UTF_8))
        val sink = ByteArrayOutputStream()

        val pump = startConsoleCommandPump(source = source, sink = sink)
        awaitPump(pump)

        assertEquals("say hello\nlist\nstop\n", sink.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    @DisplayName("命令应以换行结尾投递，使服务端按整条命令解析")
    @Timeout(30)
    fun terminateEachCommandWithNewline() {
        // 输入末行不带换行：仍应补一个换行，否则服务端会一直等这条命令的结束符
        val source = ByteArrayInputStream("stop".toByteArray(StandardCharsets.UTF_8))
        val sink = ByteArrayOutputStream()

        val pump = startConsoleCommandPump(source = source, sink = sink)
        awaitPump(pump)

        assertEquals("stop\n", sink.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    @DisplayName("命令应即时 flush，不攒在缓冲区里等后续输入")
    @Timeout(30)
    fun flushCommandImmediatelyWithoutWaitingForMoreInput() {
        // 模拟交互终端：先给一行命令，随后**保持流不结束**（就像终端还开着等下一句）。
        // 此时命令若已到达，就证明每行都 flush 了（否则会攒在编码器缓冲里直到流关闭）。
        val arrived = CountDownLatch(1)
        val received = ByteArrayOutputStream()
        val source = BlockingAfterFirstLineInputStream("say hi\n")

        // 「写入即转存、但不关流」的 sink：可见性不依赖关流
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                received.write(b)
                arrived.countDown()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                received.write(b, off, len)
                arrived.countDown()
            }
        }

        val pump = startConsoleCommandPump(source = source, sink = sink)
        try {
            // 输入流仍未结束，命令就应已到达（这正是 flush 与否的判据）
            assertTrue(arrived.await(10, TimeUnit.SECONDS), "命令应即时到达，不等输入流结束")
            assertEquals("say hi\n", received.toString(StandardCharsets.UTF_8.name()))
        } finally {
            pump.interrupt()
            source.release()
        }
    }

    @Test
    @DisplayName("空输入（无终端 / CI）应安静结束，不报错")
    @Timeout(30)
    fun finishQuietlyOnEmptyInputWithoutTerminal() {
        val warnings = mutableListOf<String>()
        // 空输入流 = EOF：`gradlew serveDev < /dev/null`、CI 等无终端场景
        val pump = startConsoleCommandPump(
            source = ByteArrayInputStream(ByteArray(0)),
            sink = ByteArrayOutputStream(),
            logger = warnings::add,
        )
        awaitPump(pump)

        assertTrue(warnings.isEmpty(), "无终端不是错误，不应告警：$warnings")
    }

    @Test
    @DisplayName("目标进程已退出时的写入失败应视为正常收尾，不告警")
    @Timeout(30)
    fun treatWriteFailureAfterTargetExitAsNormalCleanup() {
        val warnings = mutableListOf<String>()
        val closedSink = object : OutputStream() {
            override fun write(b: Int): Unit = throw IOException("Stream closed")
        }

        val pump = startConsoleCommandPump(
            source = ByteArrayInputStream("stop\n".toByteArray(StandardCharsets.UTF_8)),
            sink = closedSink,
            logger = warnings::add,
            // 目标已退出：这正是收尾路径（与 JDK 关掉已死进程 stdin 的行为一致）
            targetAlive = { false },
        )
        awaitPump(pump)

        assertTrue(warnings.isEmpty(), "目标已退出的写入失败属正常收尾，不应告警：$warnings")
    }

    @Test
    @DisplayName("目标仍存活时的写入失败应中文告警，不静默吞掉")
    @Timeout(30)
    fun warnWhenWriteFailsWhileTargetStillAlive() {
        val warnings = mutableListOf<String>()
        val brokenSink = object : OutputStream() {
            override fun write(b: Int): Unit = throw IOException("Broken pipe")
        }

        val pump = startConsoleCommandPump(
            source = ByteArrayInputStream("say hi\n".toByteArray(StandardCharsets.UTF_8)),
            sink = brokenSink,
            logger = warnings::add,
            // 目标仍活着：此时写不进去是真问题（值得告警）
            targetAlive = { true },
        )
        awaitPump(pump)

        assertEquals(1, warnings.size, "目标存活时写入失败应告警一次：$warnings")
        assertTrue(warnings.single().contains("控制台命令转发中断"), "告警应为中文且点明场景：${warnings.single()}")
    }

    @Test
    @DisplayName("目标退出后应立即停止转发，不再投递后续命令")
    @Timeout(30)
    fun stopForwardingOnceTargetExits() {
        val sink = ByteArrayOutputStream()
        val targetAlive = AtomicBoolean(true)
        // 输入足够长，确保线程会在中途观察到「目标已退出」
        val source = ByteArrayInputStream("cmd1\ncmd2\ncmd3\n".toByteArray(StandardCharsets.UTF_8))

        val pump = startConsoleCommandPump(
            source = source,
            sink = sink,
            targetAlive = { targetAlive.get() },
        )
        // 让线程跑起来后立刻标记目标退出
        targetAlive.set(false)
        awaitPump(pump)

        val delivered = sink.toString(StandardCharsets.UTF_8.name()).lines().filter { it.isNotEmpty() }
        assertTrue(delivered.size < 3, "目标退出后不应继续投递全部命令，实际投递：$delivered")
    }

    @Test
    @DisplayName("线程应为守护线程，不阻塞 JVM 退出")
    @Timeout(30)
    fun runPumpAsDaemonThread() {
        val started = CountDownLatch(1)
        val pump = startConsoleCommandPump(
            source = ByteArrayInputStream("x\n".toByteArray(StandardCharsets.UTF_8)),
            sink = ByteArrayOutputStream(),
        )
        started.countDown()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(pump.isDaemon, "转发线程必须为 daemon（否则 serve 收尾后 JVM 无法退出）")
        assertEquals(CONSOLE_PUMP_THREAD_NAME, pump.name)
    }

    @Test
    @DisplayName("多行命令应原样保序投递，含中文与空格参数")
    @Timeout(30)
    fun preserveCommandContentIncludingChineseAndSpaces() {
        val source = ByteArrayInputStream("say 你好 世界\nop 玩家一\n".toByteArray(StandardCharsets.UTF_8))
        val sink = ByteArrayOutputStream()

        val pump = startConsoleCommandPump(source = source, sink = sink)
        awaitPump(pump)

        assertEquals("say 你好 世界\nop 玩家一\n", sink.toString(StandardCharsets.UTF_8.name()))
    }
}

/**
 * 先给出首行内容、随后**阻塞不结束**的输入流，模拟"命令已敲入、但终端会话还开着"。
 *
 * 用 [release] 放行结束，避免测试挂死。用于验证转发线程每行即时 flush（而非攒到流关闭）。
 */
private class BlockingAfterFirstLineInputStream(private val firstLine: String) : java.io.InputStream() {
    private val firstBytes = firstLine.toByteArray(StandardCharsets.UTF_8)
    private var offset = 0
    private val closed = CountDownLatch(1)

    /** 放行流结束（返回 EOF），供测试收尾。 */
    fun release() {
        closed.countDown()
    }

    override fun read(): Int {
        if (offset < firstBytes.size) return firstBytes[offset++].toInt() and 0xFF
        // 首行给完后阻塞，直到测试放行 —— 借此断言"命令在流结束前就已到达"
        closed.await()
        return -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (offset >= firstBytes.size) {
            closed.await()
            return -1
        }
        val count = minOf(len, firstBytes.size - offset)
        System.arraycopy(firstBytes, offset, b, off, count)
        offset += count
        return count
    }
}
