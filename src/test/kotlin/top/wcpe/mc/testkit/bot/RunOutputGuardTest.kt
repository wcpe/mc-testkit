package top.wcpe.mc.testkit.bot

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.dsl.ScenarioHookRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(10)
class RunOutputGuardTest {
    @TempDir
    lateinit var dir: File
    private val owned = mutableListOf<Process>()
    private var began = 0L

    @org.junit.jupiter.api.BeforeEach
    fun begin() {
        began = System.nanoTime()
    }

    @org.junit.jupiter.api.AfterEach
    fun metrics(info: org.junit.jupiter.api.TestInfo) {
        val alive = owned.count { it.isAlive }
        val target = System.getProperty("guard.evidence")?.let(::File)
        if (target != null) {
            target.mkdirs()
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            File(target, info.testMethod.get().name + ".metrics.txt").writeText(
                "files=${files.size}\nbytes=${files.sumOf { it.length() }}\nelapsedMs=${(System.nanoTime() - began) / 1000000}\nchildrenAlive=$alive\n" +
                    files.joinToString("\n") { it.name + "=" + it.length() },
            )
        }
        owned.forEach { if (it.isAlive) it.destroyForcibly() }
        assertEquals(0, alive, "用例正常收尾后不得留下存活的子进程")
    }

    private fun child(mode: String, bytes: Int = 0): Process {
        require(bytes in 0..131072)
        val p = ProcessBuilder(
            javaCommand(),
            "-cp",
            fixtureClasspath(),
            GuardChild::class.java.name,
            mode,
            bytes.toString(),
        )
            .redirectErrorStream(true).start()
        owned += p
        return p
    }

    private fun start(p: Process, log: File, budget: OutputBudget, timeout: Long = 3000): BotProcessHandle {
        val pump = BoundedProcessOutputPump(log.name, p.inputStream, log, budget)
        val h = BotProcessHandle(p, pump, log)
        h.pumpThread = Thread({ pump.run() }, "guard-test-pump").apply { isDaemon = true }
        budget.supervisor.register(h, timeout)
        h.pumpThread!!.start()
        return h
    }

    private fun waitDone(h: BotProcessHandle) {
        assertTrue(h.process.waitFor(4, TimeUnit.SECONDS), "子进程必须在外部期限内退出")
        assertTrue(h.awaitPump(2), "泵必须在外部期限内结束")
    }

    private fun evidence(name: String, budget: OutputBudget, handles: List<BotProcessHandle>, started: Long) {
        val bytes = handles.sumOf { it.logFile.length() }
        assertTrue(bytes <= budget.runLimit, "全部日志合计 $bytes 字节不得超过整次构建上限 ${budget.runLimit}")
        assertTrue(handles.none { it.process.isAlive }, "留证时受监管进程必须全部已退出")
        val target = System.getProperty("guard.evidence")?.let(::File) ?: return
        target.mkdirs()
        File(
            target,
            "$name.txt",
        ).writeText("bytes=$bytes\nelapsedMs=${(System.nanoTime() - started) / 1000000}\nchildrenAlive=0\npumpsAlive=${handles.count { it.pumpThread?.isAlive == true }}\nfailure=${budget.failureReason()}\n")
    }

    @Test
    @Timeout(2)
    @DisplayName("正常跑完的进程退出期限监控，同一份监管仍继续管下一个进程")
    fun completedChildDoesNotExpireAndOwnerStillSupervisesNextChild() {
        val begin = System.nanoTime()
        val b = OutputBudget(8192, 32768)
        val h = start(child("burst", 1024), File(dir, "completed.log"), b, 600)
        try {
            assertTrue(h.process.waitFor(400, TimeUnit.MILLISECONDS), "有限输出的子进程必须在期限前退出")
            h.pumpThread!!.join(200)
            assertTrue(h.pump.isFinished(), "子进程退出后泵应当已读完")
            assertFalse(h.pumpThread!!.isAlive, "泵线程应当已结束")
            assertEquals(0, h.process.exitValue(), "子进程应当正常退出")
            assertEquals(1024, h.logFile.length(), "未超限的输出应当完整落盘")
            val used = b.usedBytes()
            // 越过期限之前，真实退出与真实管道读完都已确立。
            val beyondDeadline = begin + TimeUnit.MILLISECONDS.toNanos(800)
            while (System.nanoTime() < beyondDeadline) Thread.sleep(10)
            assertNull(b.failureReason(), "完整跑完的子进程必须退出期限监控")
            assertEquals(used, b.usedBytes(), "退出监控不得退还已用额度")
            val next = start(child("short-wait"), File(dir, "next.log"), b, 150)
            assertTrue(next.process.waitFor(700, TimeUnit.MILLISECONDS), "超期的子进程必须被停掉")
            next.pumpThread!!.join(200)
            assertTrue(b.failureReason()!!.startsWith("TIMEOUT"), "同一份监管必须仍在管其他子进程")
            b.supervisor.close()
            evidence("completed-owner", b, listOf(h, next), begin)
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @Timeout(2)
    @DisplayName("根进程已退出但观察到的子孙进程还活着：不退出监管，判失败并收掉子孙进程")
    fun exitedRootWithObservedLiveDescendantIsNotRetired() {
        val begin = System.nanoTime()
        val b = OutputBudget(8192, 32768)
        val h = start(child("short-descendant"), File(dir, "held-pipe.log"), b, 700)
        val descendants = mutableListOf<ProcessHandle>()
        try {
            val seenDeadline = begin + TimeUnit.MILLISECONDS.toNanos(600)
            while (descendants.isEmpty() && System.nanoTime() < seenDeadline) {
                h.process.descendants().use { it.forEach { p -> descendants += p } }
                Thread.sleep(10)
            }
            assertTrue(descendants.isNotEmpty(), "必须观察到一个真实的子孙进程")
            assertTrue(h.process.waitFor(700, TimeUnit.MILLISECONDS), "根进程应当自己退出")
            assertEquals(0, h.process.exitValue(), "根进程应当正常退出")
            h.pumpThread!!.join(700)
            assertFalse(h.pumpThread!!.isAlive, "子孙进程被收掉后泵应当读到 EOF")
            val reason = assertNotNull(b.failureReason(), "握着管道的子孙进程必须继续受监管")
            assertTrue(reason.startsWith("TIMEOUT") || reason.contains("inherited-pipe"), "失败原因应当是超期或管道被继承，实际：$reason")
            b.supervisor.close()
            assertTrue(descendants.none { it.isAlive }, "收尾后子孙进程必须已结束")
            evidence("held-pipe-owner", b, listOf(h), begin)
        } finally {
            b.supervisor.close()
            descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("stdout/stderr 混合的超长输出只截断该日志：子进程自己跑完，构建不判失败")
    fun mixedStreamOverflowTruncatesOnlyThatLog() {
        val start = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("burst", 128 * 1024), File(dir, "single.log"), b)
        try {
            waitDone(h)
            assertEquals(0, h.process.exitValue(), "截断后管道照常被抽干，子进程应当正常退出")
            assertTrue(h.logFile.length() <= 64 * 1024, "日志不得超过单日志上限")
            assertTrue(h.logFile.readText().contains("[OUTPUT_LIMIT] limit=per-sink"), "日志末尾应当有单日志上限的截断标记")
            assertTrue(h.isTruncated(), "该日志应当标记为已截断")
            assertNull(b.failureReason(), "单日志截断不得判整次构建失败")
            b.checkHealthy()
            evidence("single", b, listOf(h), start)
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("并发启动的进程共用整次构建上限，超限判失败；另一次构建的预算互不影响")
    fun concurrentLaunchesShareRunCapAndOtherRunIsIndependent() {
        val start = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val hs = (1..3).map { start(child("burst", 40 * 1024), File(dir, "multi-$it.log"), b) }
        try {
            hs.forEach(::waitDone)
            assertTrue(b.failureReason().orEmpty().contains("limit=run"), "合计超过整次构建上限必须判失败，实际：${b.failureReason()}")
            assertTrue(hs.all { it.logFile.length() < 64 * 1024 }, "每个日志都不得超过单日志上限")
            val separate = OutputBudget(64 * 1024, 96 * 1024)
            val good = start(child("burst", 1024), File(dir, "other.log"), separate)
            try {
                waitDone(good)
                assertNull(separate.failureReason(), "另一次构建不受这次失败影响")
                assertEquals(1024, good.logFile.length(), "另一次构建的输出应当完整落盘")
            } finally {
                separate.supervisor.close()
            }
            evidence("concurrent", b, hs, start)
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("后启动的进程不重置已用额度：前后合计超过整次构建上限即判失败")
    fun subsequentLaunchDoesNotResetUsage() {
        val started = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h1 = start(child("burst", 48 * 1024), File(dir, "backend-a.log"), b)
        try {
            waitDone(h1)
            val h2 = start(child("burst", 48 * 1024), File(dir, "backend-b.log"), b)
            waitDone(h2)
            assertTrue(b.failureReason().orEmpty().contains("limit=run"), "前后合计超限必须点名 limit=run，实际：${b.failureReason()}")
            evidence("sequential", b, listOf(h1, h2), started)
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("写盘失败与超期都是不可撤销的失败")
    fun ioFailureAndTimeoutAreStickyFailures() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val bad = File(dir, "directory.log").apply { mkdirs() }
        val h = start(child("sleep"), bad, b)
        try {
            waitDone(h)
            assertTrue(b.failureReason()!!.startsWith("IOFAIL"), "日志打不开必须判 IOFAIL，实际：${b.failureReason()}")
        } finally {
            b.supervisor.close()
        }
        val timeout = OutputBudget(64 * 1024, 96 * 1024)
        val t = start(child("sleep"), File(dir, "timeout.log"), timeout, 200)
        try {
            waitDone(t)
            assertTrue(timeout.failureReason()!!.startsWith("TIMEOUT"), "超过寿命上限必须判 TIMEOUT，实际：${timeout.failureReason()}")
        } finally {
            timeout.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("等泵的期限必须有界：0 与超大值都拒绝")
    fun legacyAwaitCannotBecomeAnInfiniteJoin() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("burst", 0), File(dir, "empty.log"), b)
        try {
            assertFailsWith<IllegalArgumentException>("期限 0 必须拒绝") { h.awaitPump(0) }
            assertFailsWith<IllegalArgumentException>("超大期限必须拒绝") { h.awaitPump(Long.MAX_VALUE) }
            waitDone(h)
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @DisplayName("子进程非零退出不能被导演写出的 PASS 盖过")
    fun abnormalExitCannotBeOverriddenByDirectorPass() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("fail"), File(dir, "fighter.log"), b)
        try {
            waitDone(h)
            // 监控线程还没采样到它时，stop 也必须检查已结束的根进程。
            h.stop()
            File(dir, "director.properties").writeText("status=PASS")
            assertFailsWith<IllegalStateException>("非零退出必须让健康检查失败") { b.checkHealthy() }
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @DisplayName("构建结束时仍握着继承管道的子孙进程会被收掉，收尾确认无存活")
    fun inheritedPipeDescendantIsReapedOnClose() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("descendant"), File(dir, "descendant.log"), b)
        val descendants = mutableListOf<ProcessHandle>()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (descendants.isEmpty() && System.nanoTime() < deadline) {
                h.process.descendants().use { s -> s.forEach { descendants += it } }
                Thread.sleep(20)
            }
            assertTrue(descendants.isNotEmpty(), "必须观察到夹具起的子孙进程")
            b.supervisor.close()
            assertFalse(h.process.isAlive, "收尾后根进程必须已结束")
            assertTrue(descendants.none { it.isAlive }, "收尾后子孙进程必须已结束")
            assertTrue(h.awaitPump(1), "管道写端全部关闭后泵必须结束")
            assertTrue(b.failureReason()!!.contains("detached-child"), "构建结束时还在跑的进程必须判失败，实际：${b.failureReason()}")
            assertEquals(emptyList(), b.supervisor.survivors(), "收尾后必须确认根进程与子孙进程都已结束")
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
            descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("根进程退出后子孙进程还握着管道：判失败并收掉子孙进程，泵不会一直卡着")
    fun exitedRootCannotLeaveAnInheritedPipeBlocked() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("descendant"), File(dir, "orphan-pipe.log"), b)
        val seen = mutableListOf<ProcessHandle>()
        try {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800)
            while (seen.isEmpty() && System.nanoTime() < until) {
                h.process.descendants().use { s -> s.forEach { seen += it } }
                Thread.sleep(20)
            }
            assertTrue(seen.isNotEmpty(), "必须观察到夹具起的子孙进程")
            waitDone(h)
            assertTrue(b.failureReason()!!.contains("inherited-pipe"), "必须判管道被继承，实际：${b.failureReason()}")
            b.supervisor.close()
            assertTrue(seen.none { it.isAlive }, "收尾后子孙进程必须已结束")
            assertTrue(h.awaitPump(1), "泵必须已结束")
        } finally {
            b.supervisor.close()
            seen.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    @DisplayName("一次构建登记数百个进程不触发数量上限，收尾确认全部结束")
    fun hundredsOfOwnedProcessesHaveNoCountLimit() {
        val b = OutputBudget(64 * 1024, 1024 * 1024)
        val windows = System.getProperty("os.name").startsWith("Windows")
        val command = if (windows) listOf("cmd.exe", "/c", "exit", "0") else listOf("true")
        val handles = (1..140).map { i ->
            val p = ProcessBuilder(command).redirectErrorStream(true).start().also { owned += it }
            start(p, File(dir, "many-$i.log"), b, 8000)
        }
        try {
            handles.forEach { assertTrue(it.process.waitFor(4, TimeUnit.SECONDS), "短命进程应当自己退出") }
            handles.forEach { assertTrue(it.awaitPump(2), "每个进程的泵都应当读到 EOF") }
            assertNull(b.failureReason(), "进程数量本身不得触发失败，实际：${b.failureReason()}")
            b.supervisor.close()
            assertEquals(emptyList(), b.supervisor.survivors(), "收尾后必须确认全部进程都已结束")
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @DisplayName("收尾前存活记录未知，收尾后确认受监管进程都已结束")
    fun closeConfirmsEveryOwnedProcessGone() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        assertNull(b.supervisor.survivors(), "还没收尾时不能声称已确认")
        val h = start(child("sleep"), File(dir, "survivor.log"), b)
        try {
            b.supervisor.close()
            assertFalse(h.process.isAlive, "收尾后受监管进程必须已结束")
            assertEquals(emptyList(), b.supervisor.survivors(), "收尾必须等到受监管进程真正退出")
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @DisplayName("进程身份只认同一个进程：启动时刻不符或进程退出后都不再算存活")
    fun processIdentityMatchesOnlyTheSameLiveProcess() {
        val p = child("sleep")
        val id = assertNotNull(ProcessIdentity.of(p.toHandle()), "刚启动的进程必须取得到启动时刻")
        assertTrue(id.isAlive(), "刚启动的进程必须算存活")
        assertFalse(id.copy(startMillis = id.startMillis + 1).isAlive(), "pid 相同但启动时刻不同，不是同一个进程")
        assertEquals(id, ProcessIdentity.parse(id.toString()), "文本形式必须能原样读回")
        p.destroyForcibly()
        assertTrue(p.waitFor(4, TimeUnit.SECONDS), "强杀后进程必须退出")
        assertFalse(id.isAlive(), "已退出的进程不再算存活")
    }

    @Test
    @DisplayName("钩子进程没有预算就拒绝启动；有预算时与 bot 共用同一份预算，超长输出只截断")
    fun hookCannotFallBackToUnlimitedAndUsesSameBudget() {
        assertFailsWith<IllegalArgumentException>("没有预算必须拒绝启动") {
            ScenarioHookRuntime.startBackground(
                listOf("unused"),
                emptyMap(),
                dir,
                File(dir, "null.log"),
                null,
            )
        }
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val cmd = listOf(javaCommand(), "-cp", fixtureClasspath(), GuardChild::class.java.name, "burst", "131072")
        val p = ScenarioHookRuntime.startBackground(cmd, emptyMap(), dir, File(dir, "hook.log"), b)
        try {
            assertTrue(p.waitFor(4, TimeUnit.SECONDS), "截断后钩子进程应当自己跑完")
            assertTrue(File(dir, "hook.log").length() <= 64 * 1024, "钩子日志不得超过单日志上限")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (b.truncatedSinks() == 0 && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(1, b.truncatedSinks(), "钩子日志应当计入同一份预算的截断次数")
            assertNull(b.failureReason(), "单日志截断不得判整次构建失败")
        } finally {
            b.supervisor.close()
            if (p.isAlive) p.destroyForcibly()
        }
    }

    @Test
    @DisplayName("本次构建已判失败后普通钩子进程拒绝启动，收尾钩子进程照样启动并落盘")
    fun cleanupProcessStartsAfterRunFailure() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        b.fail("TIMEOUT log=other.log")
        val cmd = listOf(javaCommand(), "-cp", fixtureClasspath(), GuardChild::class.java.name, "burst", "1024")
        assertFailsWith<IllegalStateException>("已判失败时普通钩子进程必须拒绝启动") {
            ScenarioHookRuntime.startBackground(cmd, emptyMap(), dir, File(dir, "refused.log"), b)
        }
        val p = ScenarioHookRuntime.startBackground(cmd, emptyMap(), dir, File(dir, "cleanup.log"), b, cleanup = true)
        owned += p
        try {
            assertTrue(p.waitFor(4, TimeUnit.SECONDS), "收尾进程应当自己跑完")
            assertEquals(0, p.exitValue(), "收尾进程应当正常退出")
            val log = File(dir, "cleanup.log")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (log.length() < 1024 && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(1024, log.length(), "收尾进程的输出应当照常落盘")
            assertEquals("TIMEOUT log=other.log", b.failureReason(), "第一个失败原因保持不变")
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @DisplayName("输出上限配置的非法值一律拒绝，不退回默认值")
    fun invalidConfigIsRejected() {
        val key = "mc.testkit.bot.log.limit.bytes"
        val old = System.getProperty(key)
        try {
            listOf(
                "0",
                "-1",
                "NaN",
                "Infinity",
                "1.5",
                "9007199254740992",
                "true",
                "null",
                "",
                " ",
                "\"1024\"",
            ).forEach {
                System.setProperty(key, it)
                assertFailsWith<IllegalArgumentException>("非法值「$it」必须拒绝") { OutputBudget.fromConfig() }
            }
        } finally {
            if (old == null) System.clearProperty(key) else System.setProperty(key, old)
        }
    }
}

internal fun javaCommand(): String = File(
    System.getProperty("java.home"),
    "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
).absolutePath

internal fun fixtureClasspath(): String =
    GuardChild::class.java.protectionDomain.codeSource.location.toURI().let { File(it).absolutePath } +
        File.pathSeparator + kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()
            .let { File(it).absolutePath }

object GuardChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "burst" -> {
                val total = args[1].toInt()
                require(total in 0..131072)
                val chunk = ByteArray(1024) { 120 }
                repeat(total / 1024) { if (it % 2 == 0) System.out.write(chunk) else System.err.write(chunk) }
                System.out.flush()
                System.err.flush()
            }

            "short-wait" -> Thread.sleep(1500)
            "short-descendant" -> {
                ProcessBuilder(
                    javaCommand(),
                    "-cp",
                    fixtureClasspath(),
                    GuardChild::class.java.name,
                    "short-wait",
                    "0",
                ).inheritIO().start()
                Thread.sleep(300)
                kotlin.system.exitProcess(0)
            }
            "sleep" -> Thread.sleep(5000)
            "long-sleep" -> Thread.sleep(60000)
            "tree" -> {
                ProcessBuilder(javaCommand(), "-cp", fixtureClasspath(), GuardChild::class.java.name, "long-sleep")
                    .inheritIO()
                    .start()
                Thread.sleep(60000)
            }
            "fail" -> kotlin.system.exitProcess(7)
            "descendant" -> {
                ProcessBuilder(
                    javaCommand(),
                    "-cp",
                    fixtureClasspath(),
                    GuardChild::class.java.name,
                    "sleep",
                    "0",
                ).inheritIO().start()
                Thread.sleep(1000)
                // 子孙进程还握着继承来的 stdout 管道时退出。
                kotlin.system.exitProcess(0)
            }
        }
    }
}
