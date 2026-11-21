package top.wcpe.mc.testkit.contract

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pid 文件契约（[McTestkitPidFile]）。
 *
 * 这个文件是**跨轮次残留物**，而 OS 会把死进程的 pid 回收给新进程。契约两侧的行为都要钉住：
 * 写的时候记下足以识别身份的信息，读的时候能用它区分「还是那个进程」与「pid 已被复用」。
 */
class McTestkitPidFileTest {

    @TempDir
    lateinit var dir: File

    private fun pidFile(name: String = "x.pid") = File(dir, name)

    @Test
    @DisplayName("写入后首行是 pid、次行是启动时刻，二者都能被读回")
    fun writeThenReadRoundTrip() {
        val file = pidFile()
        val self = ProcessHandle.current().pid()

        McTestkitPidFile.write(file, self)

        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(self.toString(), lines.first(), "首行必须是 pid")
        assertEquals(2, lines.size, "次行应记录启动时刻")
        assertEquals(self, McTestkitPidFile.readPid(file), "readPid 应读回首行的 pid")
        assertEquals(
            McTestkitPidFile.startedAtMillis(self),
            McTestkitPidFile.readStartedAtMillis(file),
            "readStartedAtMillis 应与活进程的启动时刻一致",
        )
    }

    @Test
    @DisplayName("父目录不存在时应自动创建")
    fun writeCreatesParentDirectories() {
        val file = File(dir, "nested/deeper/x.pid")

        McTestkitPidFile.write(file, ProcessHandle.current().pid())

        assertTrue(file.isFile, "父目录应被自动创建，文件应写出：${file.absolutePath}")
    }

    @Test
    @DisplayName("旧格式（只有 pid 一行）仍能读出 pid，但启动时刻为 null")
    fun legacySingleLineFileIsStillReadable() {
        val file = pidFile()
        file.writeText("12345")

        assertEquals(12345L, McTestkitPidFile.readPid(file), "旧文件的 pid 必须仍能读出")
        assertNull(McTestkitPidFile.readStartedAtMillis(file), "旧文件没有启动时刻，应返回 null")
    }

    @Test
    @DisplayName("空文件、非数字首行、文件不存在都返回 null，不抛异常")
    fun invalidInputsReturnNullInsteadOfThrowing() {
        val empty = pidFile("empty.pid").apply { writeText("") }
        val garbage = pidFile("garbage.pid").apply { writeText("not-a-pid") }
        val nonPositive = pidFile("neg.pid").apply { writeText("-1\n123") }
        val missing = pidFile("missing.pid")

        assertNull(McTestkitPidFile.readPid(empty), "空文件应返回 null")
        assertNull(McTestkitPidFile.readPid(garbage), "非数字首行应返回 null")
        assertNull(McTestkitPidFile.readPid(nonPositive), "非正数 pid 应返回 null（0 与负数不是合法进程号）")
        assertNull(McTestkitPidFile.readPid(missing), "文件不存在应返回 null")
    }

    @Test
    @DisplayName("verify：pid 已不存在时为 Gone")
    fun verifyReportsGoneForDeadPid() {
        val file = pidFile().apply { writeText("999999999\n123") }

        assertEquals(
            McTestkitPidFile.Verdict.Gone,
            McTestkitPidFile.verify(file, 999999999L),
            "不存在的 pid 应判定为 Gone",
        )
    }

    @Test
    @DisplayName("verify：启动时刻一致时为 Same，不一致时为 Reused")
    fun verifyDistinguishesSameFromReused() {
        val self = ProcessHandle.current().pid()
        val actual = requireNotNull(McTestkitPidFile.startedAtMillis(self)) {
            "测试前提：当前平台的 ProcessHandle 必须能取到启动时刻（取不到就无法验证身份核对）"
        }
        val same = pidFile("same.pid").apply { writeText("$self\n$actual") }
        val reused = pidFile("reused.pid").apply { writeText("$self\n${actual - 1000}") }

        val sameVerdict = McTestkitPidFile.verify(same, self)
        assertTrue(
            sameVerdict is McTestkitPidFile.Verdict.Same,
            "启动时刻一致应判定为同一个进程，实际：$sameVerdict",
        )
        assertEquals(actual, sameVerdict.startedAtMillis, "Same 应带出双方一致的启动时刻")

        val verdict = McTestkitPidFile.verify(reused, self)
        assertTrue(verdict is McTestkitPidFile.Verdict.Reused, "启动时刻不符应判定为 pid 复用，实际：$verdict")
        assertNotEquals(
            verdict.expected,
            verdict.actual,
            "Reused 应同时带出记录值与实际值，供报错说明",
        )
    }

    @Test
    @DisplayName("verify：Same 带出已解析的句柄，收尾方据此复用而非二次解析 pid")
    fun verifyCarriesResolvedHandle() {
        // 二次 ProcessHandle.of(pid) 会在两次解析之间留下窗口：原进程退出、pid 被无关进程占用，
        // 于是刚排除的误杀风险又回来了。判定结果必须带上已解析并确认存活的句柄。
        val self = ProcessHandle.current().pid()
        val actual = requireNotNull(McTestkitPidFile.startedAtMillis(self)) {
            "测试前提：当前平台的 ProcessHandle 必须能取到启动时刻"
        }
        val file = pidFile("handle.pid").apply { writeText("$self\n$actual") }

        val verdict = McTestkitPidFile.verify(file, self)

        assertTrue(verdict is McTestkitPidFile.Verdict.Same, "实际：$verdict")
        assertEquals(self, verdict.handle.pid(), "带回的句柄应指向同一个进程")
        assertTrue(verdict.handle.isAlive, "带回的句柄应仍然存活")
    }

    @Test
    @DisplayName("verify：记录里没有启动时刻时为 Unverifiable，由调用方决定方向")
    fun verifyReportsUnverifiableForLegacyFile() {
        val file = pidFile().apply { writeText(ProcessHandle.current().pid().toString()) }
        val pid = ProcessHandle.current().pid()

        val verdict = McTestkitPidFile.verify(file, pid)

        assertTrue(
            verdict is McTestkitPidFile.Verdict.Unverifiable,
            "旧格式无法核对身份，应明确返回 Unverifiable 而不是猜一个结论，实际：$verdict",
        )
        assertEquals(pid, verdict.handle.pid(), "Unverifiable 也应带出已解析的句柄，供放行收尾时复用")
        assertFalse(
            verdict.collectionFailed,
            "旧版本遗留的单行文件不是采集失败，不该被误标为「这台机器校验没生效」",
        )
    }

    @Test
    @DisplayName("verify：次行是取不到标记时標为 collectionFailed（区别于旧文件遗留）")
    fun verifyDistinguishesCollectionFailureFromLegacyFile() {
        val pid = ProcessHandle.current().pid()
        // 新写法在取不到启动时刻时写这个标记：与旧版单行文件必须可区分
        val file = pidFile().apply { writeText("$pid\n${McTestkitPidFile.START_UNKNOWN_MARKER}") }

        val verdict = McTestkitPidFile.verify(file, pid)

        assertTrue(verdict is McTestkitPidFile.Verdict.Unverifiable, "实际：$verdict")
        assertTrue(
            verdict.collectionFailed,
            "明确写了取不到标记，应告警「这台机器上身份校验没生效」，而不是当成旧文件静默混过去",
        )
    }

    @Test
    @DisplayName("write：取不到启动时刻时写标记而不是省略，避免静默退化成旧格式")
    fun writeMarksUnknownWhenStartInstantUnavailable() {
        // 用一个几乎不可能存在的 pid 触发「取不到启动时刻」：startedAtMillis 返回 null
        val impossiblePid = Long.MAX_VALUE
        val file = pidFile("unknown.pid")

        McTestkitPidFile.write(file, impossiblePid)

        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(impossiblePid.toString(), lines[0], "首行永远是 pid，收尾能力不能丢")
        assertEquals(
            McTestkitPidFile.START_UNKNOWN_MARKER,
            lines.getOrNull(1),
            "取不到启动时刻时必须写标记：省略会让它和旧版本单行文件无法区分",
        )
        assertTrue(McTestkitPidFile.hasUnknownStartMarker(file), "标记应能被读出")
    }

    @Test
    @DisplayName("write：能取到启动时刻时写真实时间戳，且不写标记")
    fun writeRecordsRealStartInstantWhenAvailable() {
        val self = ProcessHandle.current().pid()
        val file = pidFile("real.pid")

        McTestkitPidFile.write(file, self)

        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(self.toString(), lines[0])
        assertNotNull(lines.getOrNull(1)?.toLongOrNull(), "次行应是毫秒时间戳")
        assertFalse(McTestkitPidFile.hasUnknownStartMarker(file), "正常采集不该带取不到标记")
    }
}
