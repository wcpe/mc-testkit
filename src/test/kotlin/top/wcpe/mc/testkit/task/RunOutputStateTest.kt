package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.bot.ProcessIdentity
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `output-run.state` 接手规则的回归锚。
 *
 * 一次构建失败不得连坐后续构建（实测过：矩阵里一格 TIMEOUT 后，其余各格全在准备阶段被拒）；
 * 但上一次被中断、收尾无法确认，或记录在案的进程仍存活时必须继续拒绝。
 */
class RunOutputStateTest {
    @TempDir
    lateinit var dir: File

    private val run = "5b32bb74-9a96-4c70-84d8-b8dea2844319"
    private val survivor = ProcessIdentity(4242, 1_795_244_000_000)
    private val nothingAlive: (ProcessIdentity) -> Boolean = { false }

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
    @DisplayName("记录在案的进程仍存活：拒绝，并指出是哪个进程")
    fun liveSurvivorIsRefused() {
        val record = RunOutputState.closed(run, 0, "RUN_FAIL cleanup-deadline", listOf(ProcessIdentity(7, 1), survivor))
        val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(record) { it == survivor })
        assertEquals("state=RUN_FAIL run=$run live=4242@1795244000000", prior.detail, "只列出仍存活的进程")
    }

    @Test
    @DisplayName("上一次被中断（停在 RUNNING）：拒绝")
    fun interruptedRunIsRefused() {
        val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(RunOutputState.running(run), nothingAlive))
        assertEquals("state=RUNNING run=$run", prior.detail)
    }

    @Test
    @DisplayName("收尾无法确认（泵没排空或存活进程超过上限）：记为 unknown 并拒绝")
    fun unverifiableCleanupIsRefused() {
        val tooMany = List(RunOutputState.MAX_LIVE + 1) { ProcessIdentity(it + 1L, 1) }
        listOf(null, tooMany).forEach { survivors ->
            val record = RunOutputState.closed(run, 0, "RUN_FAIL cleanup-deadline", survivors)
            assertTrue(record.endsWith("live=unknown\n"), record)
            assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(record, nothingAlive))
        }
    }

    @Test
    @DisplayName("没有 live 记录的旧格式失败、读不懂或超长的状态：拒绝")
    fun legacyUnreadableOrOversizedStateIsRefused() {
        val legacy = "RUN_FAIL\n$run\nbytes=0\nTIMEOUT log=bot-perf.log\n"
        val prior = assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(legacy, nothingAlive))
        assertEquals("state=RUN_FAIL run=$run live=unrecorded", prior.detail)
        listOf("CLOSED\n$run\n", "RUN_FAIL\n$run\nbytes=0\nx\nlive=4242@\n", "RUN_FAIL\n$run\nbytes=0\nx\nlive=0@1\n").forEach {
            assertIs<RunOutputState.Prior.Refused>(RunOutputState.inspect(it, nothingAlive), it)
        }
        val oversized = File(dir, RunOutputState.FILE_NAME).apply { writeText("CLOSED_OK\n" + "x".repeat(8192)) }
        assertEquals(RunOutputState.Prior.Refused("state=oversized"), RunOutputState.read(oversized))
    }

    @Test
    @DisplayName("被改成 Windows 换行的状态文件仍能读懂")
    fun crlfStateIsReadable() {
        val record = RunOutputState.closed(run, 0, "TIMEOUT log=bot-perf.log", emptyList()).replace("\n", "\r\n")
        val file = File(dir, RunOutputState.FILE_NAME).apply { writeText(record) }
        assertEquals(RunOutputState.Prior.ContainedFailure(run, "TIMEOUT log=bot-perf.log"), RunOutputState.read(file))
    }
}
