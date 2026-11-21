package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 上轮结果文件清理的契约（判定可靠性：防「旧 PASS 被当成本轮通过」的假通过）。
 *
 * 假通过是本仓库最难发现的缺陷形态——它不报错、不告警，只是在日志里打一行「通过」。
 * 故这里既钉住「能删就删」，也钉住「删不掉必须抛」这个容易被人顺手改成 `runCatching` 的口径。
 */
class StaleResultCleanupTest {

    @TempDir
    lateinit var resultsDir: File

    private fun resultFile(scenario: String): File =
        File(resultsDir, McTestkitResultFile.fileName(scenario))

    @Test
    @DisplayName("上轮结果文件存在时应被删除")
    fun deletesExistingResultFile() {
        val file = resultFile("smoke")
        file.parentFile?.mkdirs()
        file.writeText("status=PASS\nmessage=上一轮的旧结论\n")
        assertTrue(file.exists(), "前置条件：文件应已写出")

        clearPreviousScenarioResult(resultsDir, "smoke")

        assertFalse(file.exists(), "起服前必须清掉上轮结果，否则轮询会读到旧 PASS")
    }

    @Test
    @DisplayName("上轮结果文件不存在时静默通过，不抛错")
    fun missingResultFileIsNoop() {
        clearPreviousScenarioResult(resultsDir, "never-ran")

        assertFalse(resultFile("never-ran").exists(), "不存在的文件不该被凭空创建")
    }

    @Test
    @DisplayName("删除失败时必须抛中文错误，不能静默放过旧结果")
    fun deleteFailureThrowsChineseError() {
        // 用非空目录冒充「删不掉的文件」：File.delete() 对非空目录必然返回 false，
        // 这与 Windows 上文件被句柄占用时的表现一致，且跨平台稳定可复现。
        val undeletable = resultFile("locked")
        File(undeletable, "child.txt").apply { parentFile?.mkdirs() }.writeText("占用者")

        val exception = assertFailsWith<GradleException> {
            clearPreviousScenarioResult(resultsDir, "locked")
        }

        assertTrue(
            exception.message!!.contains("locked"),
            "报错应指出是哪个场景的结果文件：${exception.message}",
        )
        assertTrue(
            exception.message!!.contains("误判通过"),
            "报错应说清后果（轮询会误判通过），否则用户会以为删不掉也无所谓：${exception.message}",
        )
        assertTrue(undeletable.exists(), "删除失败时不应假装删掉了")
    }

    @Test
    @DisplayName("直连与经代理共用同一结果文件：清理后两者都不会读到对方写下的旧结论")
    fun sharedResultFileBetweenDirectAndViaProxyIsCleared() {
        // 二者的结果文件名都只由场景名决定（不含 via 维度），这正是 issue 描述的触发条件
        val file = resultFile("buy")
        file.parentFile?.mkdirs()
        file.writeText("status=PASS\n")
        clearPreviousScenarioResult(resultsDir, "buy")
        assertFalse(file.exists(), "第一个入口写下的结果必须在第二个入口起服前被清掉")

        // 第二个入口跑完后再次清理：模拟同一命令里两个入口依次执行
        file.writeText("status=PASS\n")
        clearPreviousScenarioResult(resultsDir, "buy")
        assertFalse(file.exists(), "第二个入口起服前同样必须清掉")
    }

    @Test
    @DisplayName("按文件名清理的通用入口：压测 per-server 结果的落点也能清，报错带出场景与服名")
    fun deleteStaleResultReportsDescription() {
        val perServer = File(resultsDir, "stress-s1.properties")
        perServer.parentFile?.mkdirs()
        perServer.writeText("status=PASS\n")

        deleteStaleResult(perServer, "压测场景「stress」的服「s1」")
        assertFalse(perServer.exists(), "per-server 结果同样要清")

        val locked = File(resultsDir, "stress-s2.properties")
        File(locked, "child.txt").apply { parentFile?.mkdirs() }.writeText("占用者")
        val exception = assertFailsWith<GradleException> {
            deleteStaleResult(locked, "压测场景「stress」的服「s2」")
        }
        assertTrue("s2" in exception.message!!, "报错应指出哪个服：${exception.message}")
        assertTrue("stress" in exception.message!!, "报错应指出哪个场景：${exception.message}")
    }

    @Test
    @DisplayName("删不掉的是文件本身（而非目录）时同样抛错")
    fun readOnlyFileAlsoThrows() {
        val file = resultFile("readonly")
        file.parentFile?.mkdirs()
        file.writeText("status=PASS\n")
        // 只读文件在 Unix 下 delete() 仍可能成功（取决于父目录权限），故仅在设置成功且确实删不掉时断言
        val markedReadOnly = file.setReadOnly()
        assumeTrue(markedReadOnly, "平台不支持置只读，跳过该用例")

        val before = file.exists()
        try {
            clearPreviousScenarioResult(resultsDir, "readonly")
            // 若平台下只读文件仍被删除，说明清理生效，同样正确
            assertEquals(false, file.exists(), "清理后文件不应存在")
        } catch (ex: GradleException) {
            assertTrue(before, "抛错时应说明文件确实还在")
            assertTrue("误判通过" in ex.message!!, "报错口径应与目录场景一致：${ex.message}")
        } finally {
            file.setWritable(true)
        }
    }
}
