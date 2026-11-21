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
        assertEquals(0, alive, "normal test cleanup must leave no owned child")
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
        assertTrue(h.process.waitFor(4, TimeUnit.SECONDS), "child external deadline")
        assertTrue(h.awaitPump(2), "pump external deadline")
    }

    private fun evidence(name: String, budget: OutputBudget, handles: List<BotProcessHandle>, started: Long) {
        val bytes = handles.sumOf { it.logFile.length() }
        assertTrue(bytes <= budget.runLimit)
        assertTrue(handles.none { it.process.isAlive })
        val target = System.getProperty("guard.evidence")?.let(::File) ?: return
        target.mkdirs()
        File(
            target,
            "$name.txt",
        ).writeText("bytes=$bytes\nelapsedMs=${(System.nanoTime() - started) / 1000000}\nchildrenAlive=0\npumpsAlive=${handles.count { it.pumpThread?.isAlive == true }}\nfailure=${budget.failureReason()}\n")
    }

    @Test
    @Timeout(2)
    fun completedChildDoesNotExpireAndOwnerStillSupervisesNextChild() {
        val begin = System.nanoTime()
        val b = OutputBudget(8192, 32768)
        val h = start(child("burst", 1024), File(dir, "completed.log"), b, 600)
        try {
            assertTrue(h.process.waitFor(400, TimeUnit.MILLISECONDS), "finite child must exit before its deadline")
            h.pumpThread!!.join(200)
            assertTrue(h.pump.isFinished())
            assertFalse(h.pumpThread!!.isAlive)
            assertEquals(0, h.process.exitValue())
            assertEquals(1024, h.logFile.length())
            val used = b.usedBytes()
            // A real exit + a completed real pipe are established before crossing the deadline.
            val beyondDeadline = begin + TimeUnit.MILLISECONDS.toNanos(800)
            while (System.nanoTime() < beyondDeadline) Thread.sleep(10)
            assertNull(b.failureReason(), "a fully completed child must leave deadline monitoring")
            assertEquals(used, b.usedBytes(), "retirement must not refund output quota")
            val next = start(child("short-wait"), File(dir, "next.log"), b, 150)
            assertTrue(next.process.waitFor(700, TimeUnit.MILLISECONDS))
            next.pumpThread!!.join(200)
            assertTrue(b.failureReason()!!.startsWith("TIMEOUT"), "the shared owner must still monitor other children")
            b.supervisor.close()
            evidence("completed-owner", b, listOf(h, next), begin)
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    @Timeout(2)
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
            assertTrue(descendants.isNotEmpty(), "must observe a real owned descendant")
            assertTrue(h.process.waitFor(700, TimeUnit.MILLISECONDS))
            assertEquals(0, h.process.exitValue())
            h.pumpThread!!.join(700)
            assertFalse(h.pumpThread!!.isAlive)
            assertTrue(b.failureReason() != null, "a held pipe/live descendant must remain supervised")
            assertTrue(b.failureReason()!!.startsWith("TIMEOUT") || b.failureReason()!!.contains("inherited-pipe"))
            b.supervisor.close()
            assertTrue(descendants.none { it.isAlive })
            evidence("held-pipe-owner", b, listOf(h), begin)
        } finally {
            b.supervisor.close()
            descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    fun singleLongLineMixedStreamsStopsWithoutManualKill() {
        val start = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("burst", 128 * 1024), File(dir, "single.log"), b)
        try {
            waitDone(h)
            assertTrue(h.logFile.length() <= 64 * 1024)
            assertTrue(h.logFile.readText().contains("OUTPUT_LIMIT"))
            assertTrue(b.isOverrun())
            assertFailsWith<IllegalStateException> { b.checkHealthy() }
            evidence("single", b, listOf(h), start)
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    fun concurrentLaunchesShareRunCapAndOtherRunIsIndependent() {
        val start = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val hs = (1..3).map { start(child("burst", 40 * 1024), File(dir, "multi-$it.log"), b) }
        try {
            hs.forEach(::waitDone)
            assertTrue(b.isOverrun())
            assertTrue(hs.all { it.logFile.length() < 64 * 1024 })
            val separate = OutputBudget(64 * 1024, 96 * 1024)
            val good = start(child("burst", 1024), File(dir, "other.log"), separate)
            try {
                waitDone(good)
                assertNull(separate.failureReason())
                assertEquals(1024, good.logFile.length())
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
    fun subsequentLaunchDoesNotResetUsage() {
        val started = System.nanoTime()
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h1 = start(child("burst", 48 * 1024), File(dir, "backend-a.log"), b)
        try {
            waitDone(h1)
            val h2 = start(child("burst", 48 * 1024), File(dir, "backend-b.log"), b)
            waitDone(h2)
            assertTrue(b.isOverrun())
            assertTrue(b.overrunReason()!!.contains("limit=run"))
            evidence("sequential", b, listOf(h1, h2), started)
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    fun ioFailureAndTimeoutAreStickyFailures() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val bad = File(dir, "directory.log").apply { mkdirs() }
        val h = start(child("sleep"), bad, b)
        try {
            waitDone(h)
            assertTrue(b.failureReason()!!.startsWith("IOFAIL"))
        } finally {
            b.supervisor.close()
        }
        val timeout = OutputBudget(64 * 1024, 96 * 1024)
        val t = start(child("sleep"), File(dir, "timeout.log"), timeout, 200)
        try {
            waitDone(t)
            assertTrue(timeout.failureReason()!!.startsWith("TIMEOUT"))
        } finally {
            timeout.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    fun legacyAwaitCannotBecomeAnInfiniteJoin() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("burst", 0), File(dir, "empty.log"), b)
        try {
            assertFailsWith<IllegalArgumentException> { h.awaitPump(0) }
            assertFailsWith<IllegalArgumentException> { h.awaitPump(Long.MAX_VALUE) }
            waitDone(h)
        } finally {
            b.supervisor.close()
        }
    }

    @Test
    fun abnormalExitCannotBeOverriddenByDirectorPass() {
        val b = OutputBudget(64 * 1024, 96 * 1024)
        val h = start(child("fail"), File(dir, "fighter.log"), b)
        try {
            waitDone(h)
            // stop must inspect a completed root even when the monitor has not sampled it yet.
            h.stop()
            File(dir, "director.properties").writeText("status=PASS")
            assertFailsWith<IllegalStateException> { b.checkHealthy() }
        } finally {
            b.supervisor.close()
        }
    }

    @Test
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
            assertTrue(descendants.isNotEmpty(), "fixture descendant must be observed")
            b.supervisor.close()
            assertFalse(h.process.isAlive)
            assertTrue(descendants.none { it.isAlive })
            assertTrue(h.awaitPump(1))
            assertTrue(b.failureReason()!!.contains("detached-child"))
            assertEquals(emptyList(), b.supervisor.survivors(), "收尾后必须确认根进程与子孙进程都已结束")
        } finally {
            b.supervisor.close()
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
            descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
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
            assertTrue(seen.isNotEmpty())
            waitDone(h)
            assertTrue(b.failureReason()!!.contains("inherited-pipe"))
            b.supervisor.close()
            assertTrue(seen.none { it.isAlive })
            assertTrue(h.awaitPump(1))
        } finally {
            b.supervisor.close()
            seen.forEach { if (it.isAlive) it.destroyForcibly() }
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
            assertFalse(h.process.isAlive)
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
    fun hookCannotFallBackToUnlimitedAndUsesSameBudget() {
        assertFailsWith<IllegalArgumentException> {
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
            assertTrue(p.waitFor(4, TimeUnit.SECONDS))
            assertTrue(b.isOverrun())
            assertTrue(
                File(
                    dir,
                    "hook.log",
                ).length() <= 64 * 1024,
            )
        } finally {
            b.supervisor.close()
            if (p.isAlive) p.destroyForcibly()
        }
    }

    @Test
    fun invalidConfigIsRejectedAndSinkCountIsBounded() {
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
                assertFailsWith<IllegalArgumentException>(it) { OutputBudget.fromConfig() }
            }
        } finally {
            if (old == null) System.clearProperty(key) else System.setProperty(key, old)
        }
        val b = OutputBudget(1024, 32768)
        repeat(OutputBudget.MAX_SINKS) { b.acquire("s$it") }
        assertFailsWith<IllegalStateException> { b.acquire("overflow") }
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
            "fail" -> kotlin.system.exitProcess(7)
            "descendant" -> {
                val p = ProcessBuilder(
                    javaCommand(),
                    "-cp",
                    fixtureClasspath(),
                    GuardChild::class.java.name,
                    "sleep",
                    "0",
                ).inheritIO().start()
                Thread.sleep(1000)
                // Exit while the descendant still owns the inherited stdout pipe.
                kotlin.system.exitProcess(0)
            }
        }
    }
}
