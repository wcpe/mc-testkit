package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 进程台账（[ProcessLedger]）的读写与身份核对单测（进程生命周期与收尾 高风险区）。
 *
 * 重点两件：① 台账落结果目录、可反复登记与注销（幂等、不无限增长）；② [ProcessRecord.matchesLiveProcess]
 * 能挡住 pid 复用——它决定收尾会不会误杀无关进程，是台账路径的安全底线。
 *
 * 子进程用 JVM 自身（[currentJavaExecutable]）派生并按源文件启动，**不依赖 node**。
 */
class ProcessLedgerTest {

    @TempDir
    lateinit var resultsDir: File

    private fun currentJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return File(File(javaHome, "bin"), exe).absolutePath
    }

    /** 派生一个长睡子进程（命令行 / 启动时刻都由运行中的进程自身决定，正是台账要抓的东西）。 */
    private fun spawnLongLivedChild(workDir: File, name: String = "LedgerSleeper"): Process {
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
        return pb.start()
    }

    @Test
    @DisplayName("登记的记录应可读回且字段完整")
    fun recordIsReadableBack() {
        ProcessLedger.record(resultsDir, "backend/s1", 4242L, 25565)

        val records = ProcessLedger.records(resultsDir)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("backend/s1", record.key)
        assertEquals(4242L, record.pid)
        assertEquals(25565, record.port)
    }

    @Test
    @DisplayName("同一 key 重复登记应覆盖为最新一轮而非新增记录")
    fun recordIsIdempotentPerKey() {
        ProcessLedger.record(resultsDir, "backend/s1", 1L, 25565)
        ProcessLedger.record(resultsDir, "backend/s1", 2L, 25566)

        val records = ProcessLedger.records(resultsDir)
        assertEquals(1, records.size, "同一 key 应只留一条")
        assertEquals(2L, records.single().pid, "应覆盖为最新一轮的 pid")
        assertEquals(25566, records.single().port)
    }

    @Test
    @DisplayName("不同 key 的记录应各自保留")
    fun differentKeysCoexist() {
        ProcessLedger.record(resultsDir, "backend/s1", 1L, 25565)
        ProcessLedger.record(resultsDir, "proxy/wf", 2L, 25577)

        val keys = ProcessLedger.records(resultsDir).map { it.key }.sorted()
        assertEquals(listOf("backend/s1", "proxy/wf"), keys)
    }

    @Test
    @DisplayName("注销后该 key 不应再出现在台账里且不影响其它记录")
    fun unrecordRemovesOnlyThatKey() {
        ProcessLedger.record(resultsDir, "backend/s1", 1L, 25565)
        ProcessLedger.record(resultsDir, "proxy/wf", 2L, 25577)

        ProcessLedger.unrecord(resultsDir, "backend/s1")

        assertEquals(listOf("proxy/wf"), ProcessLedger.records(resultsDir).map { it.key })
    }

    @Test
    @DisplayName("台账文件缺失时应返回空表而非报错")
    fun missingLedgerYieldsEmpty() {
        assertTrue(ProcessLedger.records(resultsDir).isEmpty(), "无台账文件应返回空表")
    }

    @Test
    @DisplayName("台账文件损坏时应返回空表而非报错")
    fun corruptedLedgerYieldsEmpty() {
        ProcessLedger.file(resultsDir).writeText("这不是合法的 properties\u0000内容")

        assertTrue(ProcessLedger.records(resultsDir).isEmpty(), "损坏台账应安全退化为空表")
    }

    @Test
    @DisplayName("指向已退出进程的记录应判为不存活")
    fun deadProcessIsNotAlive() {
        // 极不可能存在的 pid；若偶然存在则跳过（避免误判）
        val unlikelyPid = 2_000_000_000L
        if (ProcessHandle.of(unlikelyPid).isPresent) {
            return
        }
        ProcessLedger.record(resultsDir, "backend/s1", unlikelyPid, 25565)

        val record = ProcessLedger.records(resultsDir).single()
        assertFalse(record.isAlive(), "不存在的进程应判为不存活")
        assertFalse(record.matchesLiveProcess(), "不存在的进程绝不可作为收尾对象")
    }

    @Test
    @DisplayName("登记的存活进程应判定匹配（命令行与启动时刻都对得上）")
    fun liveProcessMatches() {
        val child = spawnLongLivedChild(resultsDir)
        try {
            ProcessLedger.record(resultsDir, "backend/s1", child.pid(), 25565)

            val record = ProcessLedger.records(resultsDir).single()
            assertTrue(record.isAlive(), "子进程应存活")
            assertTrue(record.matchesLiveProcess(), "同一进程实例应判定匹配")
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("pid 指向无关进程时应判定不匹配（防 pid 复用误杀）")
    fun unrelatedProcessIsRejected() {
        val child = spawnLongLivedChild(resultsDir)
        try {
            // 登记后篡改命令行，模拟「原进程已退出、该 pid 被无关进程占用」
            ProcessLedger.record(resultsDir, "backend/s1", child.pid(), 25565)
            val file = ProcessLedger.file(resultsDir)
            val tampered = file.readText().replace(Regex("(?m)^(backend/s1\\.commandLine)=.*$"), "$1=/usr/bin/完全不相干的进程")
            file.writeText(tampered)

            assertFalse(
                ProcessLedger.records(resultsDir).single().matchesLiveProcess(),
                "命令行不符时必须判定不匹配（收尾将拒杀，避免误伤无关进程）",
            )
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("同一 pid 换了一茬进程（启动时刻不同）时应判定不匹配")
    fun restartedProcessWithSameCommandIsRejected() {
        val child = spawnLongLivedChild(resultsDir)
        try {
            ProcessLedger.record(resultsDir, "backend/s1", child.pid(), 25565)
            // 篡改启动时刻，模拟「pid 复用 + 命令行恰好一模一样」的极端情形
            val file = ProcessLedger.file(resultsDir)
            val tampered = file.readText().replace(Regex("(?m)^(backend/s1\\.startedAtMillis)=.*$"), "$1=1")
            file.writeText(tampered)

            assertFalse(
                ProcessLedger.records(resultsDir).single().matchesLiveProcess(),
                "启动时刻不符时必须判定不匹配",
            )
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("无命令行的记录应保留但不可匹配（绝不可作为收尾对象）")
    fun recordWithoutCommandLineIsUnmatchable() {
        val file = ProcessLedger.file(resultsDir)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            backend/s1.pid=4242
            backend/s1.port=25565
            backend/s1.commandLine=
            backend/s1.startedAtMillis=1
            """.trimIndent(),
        )

        val record = ProcessLedger.records(resultsDir).singleOrNull()
        assertTrue(record != null, "记录可保留用于人工排查（含 key 与端口）")
        assertFalse(record!!.matchesLiveProcess(), "无从核对身份时必须判定不匹配")
    }
}
