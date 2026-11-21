package top.wcpe.mc.testkit.bot

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        drainDeadlineMillis: Long = 2_000L,
    ): BoundedProcessOutputPump {
        val pump = BoundedProcessOutputPump(
            label = label,
            input = process.inputStream,
            logFile = logFile,
            budget = budget,
            drainDeadlineMillis = drainDeadlineMillis,
        )
        val handle = BotProcessHandle(process, pump, logFile)
        supervisedBudgets += budget
        budget.supervisor.register(handle, 3000)
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
    @DisplayName("超长无换行输出会被截断：真实文件不超 quota，且写出 OUTPUT_LIMIT 标记")
    fun hugeSingleLineIsTruncatedAtLimit() {
        val dir = tempDir()
        val logFile = File(dir, "bot-huge.log")
        val quota = 64L * 1024
        // 子进程吐 4 倍 quota，且**整段不换行**（单个超长行）——readLine 会在这里失控
        val child = spawnChattyChild(dir, totalBytes = (quota * 2).toInt(), newlineEvery = 0)
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        try {
            val pump = pumpTo(child, logFile, budget)
            child.waitFor(3, TimeUnit.SECONDS)

            val size = logFile.length()
            // 正文不超 quota；额外只允许有界标记行
            assertTrue(
                size <= quota + OutputBudget.MARKER_MAX_BYTES,
                "落盘 $size 字节超过了 quota=$quota + 标记上限 ${OutputBudget.MARKER_MAX_BYTES}",
            )
            assertTrue(pump.isOverrun(), "应当标记为超限")
            val text = logFile.readText(Charsets.UTF_8)
            assertTrue(text.contains("[OUTPUT_LIMIT]"), "日志里应当有一行 [OUTPUT_LIMIT] 标记")
            // 必须断言「是单 sink 限额拦下的」。只断言"有标记"不够：
            // 即使把单 sink 限额整段删掉，run 限额仍会写出同样的标记，用例照样绿——
            // 实测过这个变异，所以这里钉死触发者。
            assertTrue(
                text.contains("limit=per-sink"),
                "应当由单 sink 限额拦下（limit=per-sink），实际日志：${text.takeLast(300)}",
            )
            assertTrue(budget.isOverrun(), "预算应当被置为不可逆超限")
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
            assertTrue(
                size <= quota + OutputBudget.MARKER_MAX_BYTES,
                "两路合计 $size 字节超过了 quota=$quota（stderr 没有绕过限额）",
            )
            assertTrue(budget.isOverrun(), "两路合计超限时预算应当置位")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("多 sink 合计受 run 限额：各不超单 sink 但合超 run 时也必须停写")
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
        // 允许每个 sink 各有一行标记（最多 3 个）
        val allowed = runLimit + OutputBudget.MARKER_MAX_BYTES * logs.size
        assertTrue(
            totalWritten <= allowed,
            "三个 sink 合计 $totalWritten 字节超过了 run 限额 $runLimit + 标记预算 $allowed",
        )
        assertTrue(budget.isOverrun(), "合计超 run 限额时预算应当置位")
    }

    @Test
    @DisplayName("同一 run 内再次 acquire 不重置已用字节")
    fun reacquireDoesNotResetRunUsage() {
        val budget = OutputBudget(perSinkLimit = 1024, runLimit = 4096)
        val first = budget.acquire("a")
        val buffer = ByteArray(600) { 'z'.code.toByte() }
        val sink1 = java.io.ByteArrayOutputStream()
        assertEquals(600, first.write(sink1, buffer, 600))
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

        runA.acquire("a").write(java.io.ByteArrayOutputStream(), buffer, 500)
        assertEquals(500, runA.usedBytes())
        assertEquals(0, runB.usedBytes(), "另一个 run 的已用字节必须是 0")
    }

    @Test
    @DisplayName("超限后子进程能被收尾且不留存活进程")
    fun overrunChildIsStoppedAndReaped() {
        val dir = tempDir()
        val logFile = File(dir, "bot-reap.log")
        val quota = 16L * 1024
        val budget = OutputBudget(perSinkLimit = quota, runLimit = quota * 4)
        // 吐得很多，确保它在泵停下后仍然活着（验证收尾真的杀了它）
        val child = spawnChattyChild(dir, totalBytes = 128 * 1024, newlineEvery = 4096)
        val handle = BotProcessHandle(
            process = child,
            pump = BoundedProcessOutputPump(
                label = "reap",
                input = child.inputStream,
                logFile = logFile,
                budget = budget,
                drainDeadlineMillis = 500L,
            ),
            logFile = logFile,
        )
        val pumpThread = Thread({ handle.pump.run() }, "test-pump-reap")
        pumpThread.isDaemon = true
        handle.pumpThread = pumpThread
        supervisedBudgets += budget
        budget.supervisor.register(handle, 3000)
        pumpThread.start()
        try {
            // 等它吐过 quota（此时泵应已停止写正文）
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !handle.isOverrun()) {
                Thread.sleep(50)
            }
            assertTrue(handle.isOverrun(), "应当已超限")

            handle.stop()
            pumpThread.join(5_000)

            assertFalse(child.isAlive, "收尾后子进程不应再存活")
            assertTrue(logFile.length() <= quota + OutputBudget.MARKER_MAX_BYTES, "文件仍应在界内")
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("越限后预算可被外部读到：这是任务层把超限转 FAIL 的唯一依据")
    fun overrunIsObservableToTheTaskLayer() {
        // 事故真正的伤害不是「日志很大」，而是**判定链没有响**：
        // director 报 PASS 而 fighter 零输出。所以必须验证「超限这件事本身可被读到」，
        // 否则任务层无从转成失败。
        val budget = OutputBudget(perSinkLimit = 512, runLimit = 2048)
        assertFalse(budget.isOverrun(), "初始不应越限")
        assertEquals(null, budget.overrunReason(), "初始不应有越限说明")

        val sink = budget.acquire("bot-a")
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(600) { 'x'.code.toByte() }
        sink.write(out, chunk, 600)

        assertTrue(budget.isOverrun(), "写过 quota 之后预算必须置位")
        val reason = budget.overrunReason()
        assertTrue(reason != null && reason.contains("limit="), "越限说明必须点名触发者与限额，实际：$reason")
    }

    @Test
    @DisplayName("多个 sink 中任意一个越限，整 run 预算即置位（不是只看最后一个）")
    fun anySingleSinkOverrunMarksTheWholeRun() {
        val budget = OutputBudget(perSinkLimit = 256, runLimit = 4096)
        val chunk = ByteArray(400) { 'y'.code.toByte() }

        // 第一个 sink 越限
        budget.acquire("first").write(java.io.ByteArrayOutputStream(), chunk, 400)
        assertTrue(budget.isOverrun(), "第一个 sink 越限后 run 预算必须置位")

        // 第二个 sink 即便没写满，run 也已处于不可逆越限态
        val second = budget.acquire("second")
        val wrote = second.write(java.io.ByteArrayOutputStream(), ByteArray(10) { 'z'.code.toByte() }, 10)
        assertEquals(0, wrote, "run 已越限后，后续 sink 不得再写正文")
        assertTrue(budget.isOverrun(), "越限是不可逆状态")
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

            assertFalse(pump.isOverrun(), "正常输出不应被判超限")
            assertFalse(budget.isOverrun(), "预算不应被置位")
            // 不能断言「等于 totalBytes」：子进程按 newlineEvery 插入了换行，
            // 而 System.out.println 在 Windows 上写出 CRLF——那是子进程多写的字节，
            // 不是泵少写或多写。这里断言的是「泵一字不漏地透传了它对管道读到的一切」：
            // 落盘字节 >= 子进程正文量，且严格小于 quota（说明没被误判超限）。
            val size = logFile.length()
            assertTrue(
                size >= quota / 2,
                "落盘 $size 字节少于子进程正文量 ${quota / 2}——泵丢字节了",
            )
            assertTrue(
                size < quota,
                "落盘 $size 字节已达 quota=$quota，但本用例的输入本不该触发限额",
            )
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
