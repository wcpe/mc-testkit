package top.wcpe.mc.testkit.verify

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 结果判定单元测试（FR-06）。
 *
 * 验证「只认结果文件」：PASS 返回结论、FAIL 与文件缺失抛中文错误。
 * 用临时目录写 `<scenario>.properties`，不连任何外部依赖。
 */
class ResultReaderTest {

    private fun tempResultsDir(): File = Files.createTempDirectory("mc-testkit-result").toFile()

    private fun writeResult(dir: File, scenario: String, content: String) {
        File(dir, McTestkitResultFile.fileName(scenario)).writeText(content, Charsets.UTF_8)
    }

    @Test
    @DisplayName("状态为 PASS 时应返回包含消息的通过结论")
    fun returnsPassResultWithMessage() {
        val dir = tempResultsDir()
        writeResult(dir, "smoke", "status=PASS\nmessage=一切正常\n")

        val result = ResultReader.read(dir, "smoke")

        assertEquals(McTestkitResultFile.PASS, result.status)
        assertEquals("一切正常", result.message)
        assertTrue(result.isPass)
    }

    @Test
    @DisplayName("状态为 FAIL 时应抛出包含消息的中文错误")
    fun throwsChineseErrorForFailResultWithMessage() {
        val dir = tempResultsDir()
        writeResult(dir, "buySuccess", "status=FAIL\nmessage=余额不足\n")

        val ex = assertFailsWith<IllegalStateException> {
            ResultReader.read(dir, "buySuccess")
        }
        // 失败原因（message）须出现在中文报错里，便于定位
        assertTrue(ex.message!!.contains("buySuccess"), "报错应含场景名：${ex.message}")
        assertTrue(ex.message!!.contains("余额不足"), "报错应含失败原因：${ex.message}")
    }

    @Test
    @DisplayName("结果文件缺失时应抛出指明路径的中文错误")
    fun throwsChineseErrorWithPathForMissingResultFile() {
        val dir = tempResultsDir()

        val ex = assertFailsWith<IllegalStateException> {
            ResultReader.read(dir, "missing")
        }
        val expectedPath = File(dir, McTestkitResultFile.fileName("missing")).absolutePath
        assertTrue(ex.message!!.contains(expectedPath), "报错应指明结果文件路径：${ex.message}")
    }

    @Test
    @DisplayName("状态缺失或未知时应按失败处理并抛出中文错误")
    fun treatsMissingOrUnknownStatusAsFailure() {
        val dir = tempResultsDir()
        // 没有 status 键：视为非 PASS
        writeResult(dir, "noStatus", "message=没写状态\n")

        val ex = assertFailsWith<IllegalStateException> {
            ResultReader.read(dir, "noStatus")
        }
        assertTrue(ex.message!!.contains("noStatus"), "报错应含场景名：${ex.message}")
    }

    @Test
    @DisplayName("消息缺失但状态为 PASS 时应返回通过结论")
    fun returnsPassResultWhenMessageMissing() {
        val dir = tempResultsDir()
        writeResult(dir, "onlyStatus", "status=PASS\n")

        val result = ResultReader.read(dir, "onlyStatus")

        assertEquals(McTestkitResultFile.PASS, result.status)
        assertEquals("", result.message)
    }
}
