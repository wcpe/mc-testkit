package top.wcpe.mc.testkit.bot

import org.junit.jupiter.api.DisplayName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 输出字节预算的守卫测试（磁盘事故的防复发）。
 *
 * ## 测什么、不测什么
 *
 * 断言对象一律是**真实文件字节数**与**真实子进程存活性**，不是数学计数器、
 * 不是 mock 流。理由：这次事故的形态是「`Redirect.appendTo(File)` 内核直写、
 * JVM 不经过」——任何只测计数器的用例都能在没接上管道的情况下全绿。
 *
 * ## 不真吐大日志
 *
 * 每例用可注入的小 quota（KiB 量级）与**存活极短的子进程**，单例额外磁盘 < 2 MiB、
 * 单例耗时 < 10 s。否则守卫自己就成了再现事故的东西。
 *
 * ## 子进程用 JVM 自身
 *
 * 与 [ProcessReaperTest] 同口径：用 `java <单源文件>` 派生，不依赖 node，
 * 无 Node 的 CI 也能跑。
 */
@org.junit.jupiter.api.Timeout(10)
class OutputBudgetTest {
    private val supervisedBudgets = mutableSetOf<OutputBudget>()

    @org.junit.jupiter.api.AfterEach fun closeOwners() {
        supervisedBudgets.forEach { it.supervisor.close() }
    }

    private fun tempDir(): File = Files.createTempDirectory("mc-testkit-budget").toFile()

    private fun currentJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return File(File(javaHome, "bin"), if (isWindows) "java.exe" else "java").absolutePath
    }

    /**
     * 派生一个「按行不停输出」的子进程，输出总字节由 [totalBytes] 控制，输出完就退出。
     *
     * 用 8192 字节的行长——单个超过缓冲区大小的长行也是事故特征（单个巨大 JSON 数组），
     * 所以这里刻意不换行太频繁。
     */
    private fun spawnChattyChild(workDir: File, totalBytes: Int, newlineEvery: Int = 8192): Process {
        val src = File(workDir, "Chatty.java")
        src.writeText(
            """
            public class Chatty {
                public static void main(String[] args) throws Exception {
                    int total = Integer.parseInt(args[0]);
                    int every = Integer.parseInt(args[1]);
                    byte[] chunk = new byte[1024];
                    for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) 'x';
                    java.io.PrintStream out = System.out;
                    int written = 0;
                    while (written < total) {
                        int n = Math.min(chunk.length, total - written);
                        out.write(chunk, 0, n);
                        written += n;
                        if (every > 0 && written % every < chunk.length) out.println();
                    }
                    out.flush();
                }
            }
            """.trimIndent(),
        )
        val pb = ProcessBuilder(currentJavaExecutable(), src.absolutePath, totalBytes.toString(), newlineEvery.toString())
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /** 把子进程的输出泵进 [logFile]，在当前线程跑完。 */
    private fun pumpTo(
        process: Process,
        logFile: File,
        budget: OutputBudget,
        label: String = "test-bot",
    ): BoundedProcessOutputPump {
        val pump = BoundedProcessOutputPump(label = label, input = process.inputStream, logFile = logFile, budget = budget)
        val handle = BotProcessHandle(process, pump, logFile)
        supervisedBudgets += budget
        budget.supervisor.register(handle, 8000)
        pump.run()
        return pump
    }

    @Test
    @DisplayName("配置校验：0 / 负数 / 超上界一律拒绝，不得解释成「无限」")
    fun invalidLimitsAreRejectedNotTreatedAsInfinite() {
        // 0 与负数都必须是「拒绝」，不是「无限」——事故里就是无上限
        val failures = listOf<() -> Unit>(
            { OutputBudget(perSinkLimit = 0, runLimit = 1024) },
            { OutputBudget(perSinkLimit = -1, runLimit = 1024) },
            { OutputBudget(perSinkLimit = 1024, runLimit = 0) },
            { OutputBudget(perSinkLimit = 1024, runLimit = -5) },
            { OutputBudget(perSinkLimit = OutputBudget.MAX_LIMIT + 1, runLimit = OutputBudget.MAX_LIMIT + 1) },
            // 单输出上限大于整 run 上限也是配置矛盾，不应静默接受
            { OutputBudget(perSinkLimit = 2048, runLimit = 1024) },
        )
        failures.forEachIndexed { index, build ->
            var thrown: Throwable? = null
            try {
                build()
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue(thrown is IllegalArgumentException, "第 ${index + 1} 个非法配置应当抛 IllegalArgumentException，实际：$thrown")
        }
    }

    @Test
    @DisplayName("超长无换行输出只截断该日志：文件不超 quota、写出 per-sink 标记，构建不判失败，子进程照常跑完")
    fun hugeSingleLineIsTruncatedAtLimit() {
        val dir = tempDir()
        val logFile = File(dir, "bot-huge.log")
        val quota = 64L * 1024
        // 整段不换行（单个超长行）——readLine 会在这里失控
        val child = spawnChattyChild(dir, totalBytes = (quota * 2).toInt(), newlineEvery = 0)
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        try {
            val pump = pumpTo(child, logFile, budget)
            assertTrue(child.waitFor(3, TimeUnit.SECONDS), "截断后管道照常被抽干，子进程应当自己跑完")
            assertEquals(0, child.exitValue(), "子进程不应被杀，退出码应为 0")

            val size = logFile.length()
            assertTrue(size <= quota, "落盘 $size 字节超过了 quota=$quota（标记行也计入单日志上限）")
            assertTrue(pump.isTruncated(), "该日志应当标记为已截断")
            val text = logFile.readText(Charsets.UTF_8)
            assertTrue(text.contains("[OUTPUT_LIMIT]"), "日志里应当有一行 [OUTPUT_LIMIT] 标记")
            // 必须断言「是单日志上限拦下的」：只断言有标记时，把单日志上限整段删掉、由整次构建上限写出
            // 同样的标记，用例照样绿。
            assertTrue(text.contains("limit=per-sink"), "应当由单日志上限拦下（limit=per-sink），实际日志：${text.takeLast(300)}")
            assertNull(budget.failureReason(), "单个日志截断不得判整次构建失败")
            assertEquals(1, budget.truncatedSinks(), "截断次数应当记 1")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("stdout 与 stderr 合计受同一 quota：两路混合输出也不会突破上限")
    fun stdoutAndStderrShareTheSameQuota() {
        val dir = tempDir()
        val logFile = File(dir, "bot-both.log")
        val quota = 32L * 1024

        val src = File(dir, "Both.java")
        src.writeText(
            """
            public class Both {
                public static void main(String[] args) throws Exception {
                    byte[] chunk = new byte[1024];
                    java.util.Arrays.fill(chunk, (byte) 'y');
                    for (int i = 0; i < 64; i++) {
                        System.out.write(chunk, 0, chunk.length);
                        System.err.write(chunk, 0, chunk.length);
                    }
                    System.out.flush();
                    System.err.flush();
                }
            }
            """.trimIndent(),
        )
        val pb = ProcessBuilder(currentJavaExecutable(), src.absolutePath)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        val child = pb.start()
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        try {
            pumpTo(child, logFile, budget)
            child.waitFor(3, TimeUnit.SECONDS)

            val size = logFile.length()
            assertTrue(size <= quota, "两路合计 $size 字节超过了 quota=$quota（stderr 不得绕过限额）")
            assertEquals(1, budget.truncatedSinks(), "两路合计超过单日志上限时应当截断该日志")
            assertNull(budget.failureReason(), "单日志截断不得判整次构建失败")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("多个日志合计受整次构建上限：各自不超单日志上限但合计超限时判失败并停写")
    fun multipleSinksShareRunLimit() {
        val dir = tempDir()
        val perSink = 64L * 1024
        val runLimit = 96L * 1024
        val budget = OutputBudget(perSinkLimit = perSink, runLimit = runLimit)

        val logs = mutableListOf<File>()
        repeat(3) { index ->
            if (budget.failureReason() != null) return@repeat
            val logFile = File(dir, "bot-multi-$index.log")
            logs += logFile
            val child = spawnChattyChild(dir, totalBytes = (perSink * 3 / 4).toInt(), newlineEvery = 4096)
            try {
                pumpTo(child, logFile, budget, label = "multi-$index")
                child.waitFor(3, TimeUnit.SECONDS)
            } finally {
                child.destroyForcibly()
            }
        }

        val totalWritten = logs.sumOf { it.length() }
        assertTrue(totalWritten <= runLimit, "三个日志合计 $totalWritten 字节超过了整次构建上限 $runLimit")
        val reason = budget.failureReason()
        assertTrue(reason.orEmpty().contains("limit=run"), "合计超过整次构建上限必须判失败并点名 limit=run，实际：$reason")
    }

    @Test
    @DisplayName("同一 run 内再次 acquire 不重置已用字节")
    fun reacquireDoesNotResetRunUsage() {
        val budget = OutputBudget(perSinkLimit = 1024, runLimit = 4096)
        val first = budget.acquire("a")
        val buffer = ByteArray(600) { 'z'.code.toByte() }
        assertEquals(600, first.write(ByteArrayOutputStream(), buffer, 600))
        assertEquals(600, budget.usedBytes())

        // 新 sink 是新的单 sink 计数，但 run 已用字节延续
        val second = budget.acquire("b")
        assertEquals(0, second.usedBytes(), "新 sink 自己的计数从 0 开始")
        assertEquals(600, budget.usedBytes(), "run 已用字节不得因再次 acquire 而重置")
    }

    @Test
    @DisplayName("不同 run（不同 OutputBudget 实例）互不影响")
    fun separateRunsAreIsolated() {
        val runA = OutputBudget(perSinkLimit = 1024, runLimit = 2048)
        val runB = OutputBudget(perSinkLimit = 1024, runLimit = 2048)
        val buffer = ByteArray(500) { 'a'.code.toByte() }

        runA.acquire("a").write(ByteArrayOutputStream(), buffer, 500)
        assertEquals(500, runA.usedBytes())
        assertEquals(0, runB.usedBytes(), "另一个 run 的已用字节必须是 0")
    }

    @Test
    @DisplayName("截断后照常抽干管道：子进程自己跑完退出，之后收尾 stop 不判失败，文件仍在界内")
    fun truncatedChildKeepsRunningAndStopsCleanly() {
        val dir = tempDir()
        val logFile = File(dir, "bot-reap.log")
        val quota = 16L * 1024
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        val child = spawnChattyChild(dir, totalBytes = 128 * 1024, newlineEvery = 4096)
        val handle = BotProcessHandle(child, BoundedProcessOutputPump("reap", child.inputStream, logFile, budget), logFile)
        val pumpThread = Thread({ handle.pump.run() }, "test-pump-reap").apply { isDaemon = true }
        handle.pumpThread = pumpThread
        supervisedBudgets += budget
        budget.supervisor.register(handle, 8000)
        pumpThread.start()
        try {
            assertTrue(child.waitFor(8, TimeUnit.SECONDS), "截断后管道照常被抽干，子进程应当自己跑完")
            assertEquals(0, child.exitValue(), "子进程不应被杀，退出码应为 0")
            assertTrue(handle.awaitPump(3), "子进程退出后泵应当读到 EOF 结束")
            assertTrue(handle.isTruncated(), "该日志应当已截断")

            handle.stop()
            assertNull(budget.failureReason(), "单日志截断与正常收尾都不得判失败")
            assertTrue(logFile.length() <= quota, "文件仍应在单日志上限内（标记行也计入）")
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("单日志截断可查询但不算失败；整次构建超限才判失败并点名触发者")
    fun truncationIsObservableAndOnlyRunOverflowFails() {
        val budget = OutputBudget(perSinkLimit = 512, runLimit = 2048)
        assertNull(budget.failureReason(), "初始不应失败")
        assertEquals(0, budget.truncatedSinks(), "初始不应有截断")

        val sink = budget.acquire("bot-a")
        sink.write(ByteArrayOutputStream(), ByteArray(600) { 'x'.code.toByte() }, 600)
        assertTrue(sink.isTruncated(), "写过单日志上限后该日志必须标记为截断")
        assertEquals(1, budget.truncatedSinks(), "截断次数必须可读到")
        assertNull(budget.failureReason(), "单日志截断不得判整次构建失败")

        val tight = OutputBudget(perSinkLimit = 1024, runLimit = 1024)
        tight.acquire("bot-b").write(ByteArrayOutputStream(), ByteArray(1000) { 'x'.code.toByte() }, 1000)
        val reason = tight.failureReason()
        assertTrue(reason != null && reason.contains("limit=run"), "整次构建超限必须判失败并点名 limit=run，实际：$reason")
        assertFailsWith<IllegalStateException>("失败后健康检查必须抛出") { tight.checkHealthy() }
    }

    @Test
    @DisplayName("一个日志截断不影响其他日志继续写")
    fun oneTruncatedSinkDoesNotAffectOthers() {
        val budget = OutputBudget(perSinkLimit = 256, runLimit = 4096)
        budget.acquire("first").write(ByteArrayOutputStream(), ByteArray(400) { 'y'.code.toByte() }, 400)
        assertEquals(1, budget.truncatedSinks(), "第一个日志应当被截断")

        val wrote = budget.acquire("second").write(ByteArrayOutputStream(), ByteArray(10) { 'z'.code.toByte() }, 10)
        assertEquals(10, wrote, "其他日志不受影响，应当照常写入")
        assertNull(budget.failureReason(), "单日志截断不得让整次构建失败")
    }

    @Test
    @DisplayName("日志数量不设上限：大规模压测的数百个 bot 各自一个日志也不失败")
    fun sinkCountIsUnbounded() {
        val budget = OutputBudget(perSinkLimit = 1024, runLimit = 1024L * 1024)
        repeat(1000) { budget.acquire("bot-$it").write(ByteArrayOutputStream(), ByteArray(8), 8) }
        assertNull(budget.failureReason(), "日志数量本身不得触发失败")
        assertEquals(8000L, budget.usedBytes(), "每个日志的用量都应计入整次构建")
    }

    @Test
    @DisplayName("某个日志写盘卡住时，判失败与查用量不被它拖住")
    fun blockedDiskWriteDoesNotHoldTheBudget() {
        val budget = OutputBudget(perSinkLimit = 4096, runLimit = 65536)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stuck = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                release.await()
            }
        }
        val writer = Thread { budget.acquire("stuck").write(stuck, ByteArray(100), 100) }.apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS), "写线程必须已经卡在写盘里")
            val failer = Thread { budget.fail("TIMEOUT log=other.log") }.apply { start() }
            failer.join(1000)
            assertFalse(failer.isAlive, "写盘卡住时 fail 不得等它")
            assertEquals("TIMEOUT log=other.log", budget.failureReason(), "失败原因应当立即可读")
            assertEquals(100L, budget.usedBytes(), "用量在写盘前已记账，查询不得等写盘")
        } finally {
            release.countDown()
            writer.join(1000)
        }
    }

    @Test
    @DisplayName("超过单日志上限后泵仍把管道读到 EOF，只是不再落盘")
    fun pumpDrainsToEofAfterTruncation() {
        val budget = OutputBudget(perSinkLimit = 4096, runLimit = 65536)
        val input = ByteArrayInputStream(ByteArray(100_000) { 'q'.code.toByte() })
        val logFile = File(tempDir(), "drain.log")
        val pump = BoundedProcessOutputPump("drain", input, logFile, budget)
        pump.run()
        assertTrue(pump.isFinished(), "泵应当读到 EOF 后结束")
        assertEquals(0, input.available(), "管道里的输出必须被读空，子进程才不会卡在写满的管道上")
        assertTrue(pump.isTruncated(), "该日志应当已截断")
        assertTrue(logFile.length() <= 4096, "落盘字节不得超过单日志上限，实际 ${logFile.length()}")
    }

    @Test
    @DisplayName("整次构建判了超期等其他失败后日志照常落盘：被停掉的进程最后的输出不丢")
    fun outputIsStillWrittenAfterOtherFailures() {
        val budget = OutputBudget(perSinkLimit = 65536, runLimit = 1024L * 1024)
        budget.fail("TIMEOUT log=other.log")
        val input = ByteArrayInputStream(ByteArray(1000) { 'q'.code.toByte() })
        val logFile = File(tempDir(), "last-words.log")
        val pump = BoundedProcessOutputPump("last-words", input, logFile, budget)
        pump.run()
        assertEquals(1000L, pump.writtenBytes(), "上限之内的输出必须完整落盘")
        assertEquals(1000L, logFile.length(), "日志文件应当包含全部输出")
        assertEquals("TIMEOUT log=other.log", budget.failureReason(), "第一个失败原因保持不变")
    }

    @Test
    @DisplayName("未超限时内容完整写出，不得误伤正常输出")
    fun normalOutputIsWrittenFully() {
        val dir = tempDir()
        val logFile = File(dir, "bot-normal.log")
        val quota = 256L * 1024
        val child = spawnChattyChild(dir, totalBytes = (quota / 2).toInt(), newlineEvery = 4096)
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        try {
            val pump = pumpTo(child, logFile, budget)
            child.waitFor(3, TimeUnit.SECONDS)

            assertFalse(pump.isTruncated(), "正常输出不应被截断")
            assertNull(budget.failureReason(), "预算不应失败")
            // 不能断言「等于 totalBytes」：子进程按 newlineEvery 插入了换行，
            // 而 System.out.println 在 Windows 上写出 CRLF——那是子进程多写的字节，
            // 不是泵少写或多写。这里断言的是「泵一字不漏地透传了它对管道读到的一切」：
            // 落盘字节 >= 子进程正文量，且严格小于 quota（说明没被误判超限）。
            val size = logFile.length()
            assertTrue(size >= quota / 2, "落盘 $size 字节少于子进程正文量 ${quota / 2}——泵丢字节了")
            assertTrue(size < quota, "落盘 $size 字节已达 quota=$quota，但本用例的输入本不该触发限额")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("输出上限先读系统属性、再读 MC_TESTKIT_E2E_ 前缀的环境变量，无前缀的旧名不生效")
    fun limitsComeFromPropertyThenPrefixedEnvironment() {
        val env = mapOf(
            "MC_TESTKIT_E2E_BOT_LOG_LIMIT_BYTES" to "4096",
            "MC_TESTKIT_E2E_BOT_LOG_RUN_LIMIT_BYTES" to "65536",
        )
        val fromEnv = OutputBudget.fromLookup({ null }, env::get)
        assertEquals(4096L, fromEnv.perSinkLimit, "单文件上限必须取自环境变量")
        assertEquals(65536L, fromEnv.runLimit, "构建上限必须取自环境变量")
        val fromProperty = OutputBudget.fromLookup(mapOf("mc.testkit.bot.log.limit.bytes" to "8192")::get, env::get)
        assertEquals(8192L, fromProperty.perSinkLimit, "系统属性必须盖过环境变量")
        val legacy = mapOf("MC_TESTKIT_BOT_LOG_LIMIT_BYTES" to "1", "MC_TESTKIT_BOT_LOG_RUNLIMIT_BYTES" to "1")
        val defaults = OutputBudget.fromLookup({ null }, legacy::get)
        assertEquals(OutputBudget.DEFAULT_PER_SINK_LIMIT, defaults.perSinkLimit, "无前缀的旧名不得生效")
        assertEquals(OutputBudget.DEFAULT_RUN_LIMIT, defaults.runLimit, "无前缀的旧名不得生效")
        val inverted = mapOf("MC_TESTKIT_E2E_BOT_LOG_LIMIT_BYTES" to "1048576", "MC_TESTKIT_E2E_BOT_LOG_RUN_LIMIT_BYTES" to "65536")
        assertFailsWith<IllegalArgumentException>("单文件上限大于构建上限必须拒绝") {
            OutputBudget.fromLookup({ null }, inverted::get)
        }
    }
}
