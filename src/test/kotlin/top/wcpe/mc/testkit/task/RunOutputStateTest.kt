package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.bot.ProcessIdentity
import top.wcpe.mc.testkit.task.RunOutputState.Remedy
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `output-run.state` 接手规则的回归锚。
 *
 * 一次构建失败不得连坐后续构建（实测过：矩阵里一格 TIMEOUT 后，其余各格全在准备阶段被拒），没收尾就结束的构建
 * 也不得让后续构建永远卡住；但持有记录的构建还在跑、收尾无法确认，或上一次的进程仍存活时必须继续拒绝，
 * 并指出怎么恢复。
 */
class RunOutputStateTest {
    @TempDir
    lateinit var dir: File

    private val run = "5b32bb74-9a96-4c70-84d8-b8dea2844319"
    private val survivor = ProcessIdentity(4242, 1_795_244_000_000)
    private val nothingAlive: (ProcessIdentity) -> Boolean = { false }
    private val notCurrentJvm: (ProcessIdentity) -> Boolean = { false }

    @Test
    @DisplayName("没有状态文件或文件为空：视为全新接手")
    fun missingOrEmptyStateIsFresh() {
        assertEquals(RunOutputState.Prior.None, RunOutputState.read(File(dir, RunOutputState.FILE_NAME)))
        assertEquals(RunOutputState.Prior.None, RunOutputState.inspect("", nothingAlive))
    }

    @Test
    @DisplayName("上一次成功收尾：接手")
    fun closedOkIsAccepted() {
        val record = RunOutputState.closed(run, 1024, null, emptyList())
        assertEquals("CLOSED_OK\n$run\nbytes=1024\n\nlive=\n", record)
        assertEquals(RunOutputState.Prior.ClosedOk, RunOutputState.inspect(record, nothingAlive))
    }

    @Test
    @DisplayName("上一次失败但受监管进程都已确认结束：接手，不连坐")
    fun containedFailureIsAccepted() {
        val record = RunOutputState.closed(run, 4096, "TIMEOUT log=bot-perf.log", emptyList())
        assertEquals(
            RunOutputState.Prior.ContainedFailure(run, "TIMEOUT log=bot-perf.log"),
            RunOutputState.inspect(record, nothingAlive),
        )
    }

    @Test
    @DisplayName("收尾期限到时仍存活的进程如今已结束：接手")
    fun survivorsThatHaveSinceExitedAreAccepted() {
        val record = RunOutputState.closed(run, 0, "RUN_FAIL cleanup-deadline", listOf(survivor))
        assertTrue(record.endsWith("live=4242@1795244000000\n"), record)
        assertIs<RunOutputState.Prior.ContainedFailure>(RunOutputState.inspect(record, nothingAlive))
    }

    @Test
    @DisplayName("记录在案的进程仍存活：拒绝，指出是哪个进程，指向 stop 任务")
    fun liveSurvivorIsRefused() {
        val record = RunOutputState.closed(run, 0, "RUN_FAIL cleanup-deadline", listOf(ProcessIdentity(7, 1), survivor))
        val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(record, isAlive = { it == survivor }))
        assertEquals("state=RUN_FAIL run=$run live=4242@1795244000000", prior.detail, "只列出仍存活的进程")
        assertEquals(Remedy.STOP_PROCESSES, prior.remedy)
    }

    @Test
    @DisplayName("构建进行中的记录带上持有它的构建 JVM；取不到启动时刻时留空")
    fun runningRecordNamesItsOwner() {
        assertEquals("RUNNING\n$run\nowner=4242@1795244000000\nhb=1\n", RunOutputState.running(run, survivor))
        assertEquals("RUNNING\n$run\nowner=\nhb=1\n", RunOutputState.running(run, null))
    }

    @Test
    @DisplayName("持有记录的构建还在跑：拒绝，等它结束，不去查台账")
    fun liveOwnerIsWaitedFor() {
        val prior = assertIs<RunOutputState.Prior.Refused>(
            RunOutputState.inspect(
                RunOutputState.running(run, survivor),
                isAlive = { it == survivor },
                isCurrentJvm = notCurrentJvm,
                liveWriters = { error("持有者还在跑时不该查台账") },
            ),
        )
        assertEquals(RunOutputState.Prior.Refused("state=RUNNING run=$run owner=4242@1795244000000", Remedy.WAIT_OWNER), prior)
    }

    @Test
    @DisplayName("持有者 pid 活着但心跳已过期（daemon 空闲、构建早亡）：按持有者结束处理")
    fun staleHeartbeatOwnerIsTreatedAsEnded() {
        val record = RunOutputState.running(run, survivor)
        assertEquals(
            RunOutputState.Prior.Abandoned(run),
            RunOutputState.inspect(
                record,
                isAlive = { it == survivor },
                isCurrentJvm = notCurrentJvm,
                heartbeatFresh = { false },
            ),
            "带 hb 标记的记录心跳过期时，哪怕 owner pid（daemon）还活着也按持有者结束处理",
        )
    }

    @Test
    @DisplayName("持有者 pid 活着且心跳新鲜：仍按持有者存活等待")
    fun freshHeartbeatOwnerIsStillWaitedFor() {
        val prior = assertIs<RunOutputState.Prior.Refused>(
            RunOutputState.inspect(
                RunOutputState.running(run, survivor),
                isAlive = { it == survivor },
                isCurrentJvm = notCurrentJvm,
                heartbeatFresh = { true },
                liveWriters = { error("持有者还在跑时不该查台账") },
            ),
        )
        assertEquals(Remedy.WAIT_OWNER, prior.remedy)
    }

    @Test
    @DisplayName("无 hb 标记的旧版记录不受心跳影响：只看 pid 存活（保守行为不回退）")
    fun legacyRecordWithoutHeartbeatMarkerKeepsPidOnlyBehavior() {
        val legacy = "RUNNING\n$run\nowner=4242@1795244000000\n"
        val prior = assertIs<RunOutputState.Prior.Refused>(
            RunOutputState.inspect(
                legacy,
                isAlive = { it == survivor },
                isCurrentJvm = notCurrentJvm,
                heartbeatFresh = { false },
                liveWriters = { error("旧版记录无心跳语义，持有者活着就该等") },
            ),
        )
        assertEquals(Remedy.WAIT_OWNER, prior.remedy)
    }

    @Test
    @DisplayName("没收尾就结束的构建（持有者已退出，或就是当前 JVM）且台账里没有存活的 bot 与钩子：接手")
    fun abandonedRunWithoutLiveWritersIsAccepted() {
        val record = RunOutputState.running(run, survivor)
        assertEquals(
            RunOutputState.Prior.Abandoned(run),
            RunOutputState.inspect(record, isAlive = nothingAlive, isCurrentJvm = notCurrentJvm),
        )
        assertEquals(
            RunOutputState.Prior.Abandoned(run),
            RunOutputState.inspect(record, isAlive = { true }, isCurrentJvm = { it == survivor }),
            "守护进程一次只跑一个构建：持有者是自己，那次构建必定已经结束",
        )
    }

    @Test
    @DisplayName("没收尾就结束的构建在台账里留下了存活的 bot：拒绝，指向 stop 任务")
    fun abandonedRunWithLiveWritersIsRefused() {
        val file = File(dir, RunOutputState.FILE_NAME).apply { writeText(RunOutputState.running(run, survivor)) }
        val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.read(file) { listOf("bot/idle") })
        assertEquals(RunOutputState.Prior.Refused("state=RUNNING run=$run owner=ended ledger-live=1", Remedy.STOP_PROCESSES), prior)
    }

    @Test
    @DisplayName("旧版本写下的 RUNNING（没有持有者）或持有者读不懂：拒绝，只能手工确认后删掉记录")
    fun runningWithoutOwnerIsRefused() {
        listOf("RUNNING\n$run\n", "RUNNING\n$run\nowner=\n", "RUNNING\n$run\nowner=4242@x\n").forEach { record ->
            assertEquals(
                RunOutputState.Prior.Refused("state=RUNNING run=$run owner=unrecorded", Remedy.DELETE_STATE),
                RunOutputState.inspect(record, isAlive = nothingAlive, isCurrentJvm = notCurrentJvm),
                record,
            )
        }
    }

    @Test
    @DisplayName("收尾无法确认（泵没排空或存活进程超过上限）：记为 unknown 并拒绝")
    fun unverifiableCleanupIsRefused() {
        val tooMany = List(RunOutputState.MAX_LIVE + 1) { ProcessIdentity(it + 1L, 1) }
        listOf(null, tooMany).forEach { survivors ->
            val record = RunOutputState.closed(run, 0, "RUN_FAIL cleanup-deadline", survivors)
            assertTrue(record.endsWith("live=unknown\n"), record)
            val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(record, nothingAlive))
            assertEquals(Remedy.DELETE_STATE, prior.remedy)
        }
    }

    @Test
    @DisplayName("没有 live 记录的旧格式失败、读不懂或超长的状态：拒绝")
    fun legacyUnreadableOrOversizedStateIsRefused() {
        val legacy = "RUN_FAIL\n$run\nbytes=0\nTIMEOUT log=bot-perf.log\n"
        assertEquals(
            RunOutputState.Prior.Refused("state=RUN_FAIL run=$run live=unrecorded", Remedy.DELETE_STATE),
            RunOutputState.inspect(legacy, nothingAlive),
        )
        listOf("CLOSED\n$run\n", "RUN_FAIL\n$run\nbytes=0\nx\nlive=4242@\n", "RUN_FAIL\n$run\nbytes=0\nx\nlive=0@1\n").forEach {
            val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(it, nothingAlive), it)
            assertEquals(Remedy.DELETE_STATE, prior.remedy, it)
        }
        val oversized = File(dir, RunOutputState.FILE_NAME).apply { writeText("CLOSED_OK\n" + "x".repeat(8192)) }
        assertEquals(RunOutputState.Prior.Refused("state=oversized", Remedy.DELETE_STATE), RunOutputState.read(oversized))
    }

    @Test
    @DisplayName("被改成 Windows 换行的状态文件仍能读懂")
    fun crlfStateIsReadable() {
        val record = RunOutputState.closed(run, 0, "TIMEOUT log=bot-perf.log", emptyList()).replace("\n", "\r\n")
        val file = File(dir, RunOutputState.FILE_NAME).apply { writeText(record) }
        assertEquals(RunOutputState.Prior.ContainedFailure(run, "TIMEOUT log=bot-perf.log"), RunOutputState.read(file))
        file.writeText(RunOutputState.running(run, survivor).replace("\n", "\r\n"))
        assertEquals(RunOutputState.Prior.Abandoned(run), RunOutputState.read(file))
    }
}
