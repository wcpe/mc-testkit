package top.wcpe.mc.testkit.verify

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import top.wcpe.mc.testkit.task.clearPreviousScenarioResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 结果判定单元测试（机器人驱动与结果判定）。
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

    @Test
    @DisplayName("启动场景前必须删除上一次结果，避免旧 PASS 被误判")
    fun clearsPreviousScenarioResult() {
        val dir = tempResultsDir()
        writeResult(dir, "repeat", "status=PASS\nmessage=旧结果\n")

        clearPreviousScenarioResult(dir, "repeat")

        assertTrue(!File(dir, McTestkitResultFile.fileName("repeat")).exists())
    }

    @Test
    @DisplayName("结果文件不是合法 UTF-8 时按宽容方式解码，status 仍可判定且构建不崩")
    fun lenientlyDecodesNonUtf8ResultFile() {
        // 用 GBK 写「失败原因」——消费方桩在 Windows 上用平台默认编码时就是这个形态。
        // status 是 ASCII，任何单字节编码解码出来都一样，故判定必须仍然可靠。
        val dir = tempResultsDir()
        val gbkMessage = "世界未加载".toByteArray(charset("GBK"))
        val content = "status=PASS\nmessage=".toByteArray(Charsets.US_ASCII) + gbkMessage
        File(dir, McTestkitResultFile.fileName("gbk")).writeBytes(content)

        val result = ResultReader.read(dir, "gbk")

        assertEquals(McTestkitResultFile.PASS, result.status, "status 是 ASCII，宽容解码后仍应判定为 PASS")
        assertTrue(
            result.message.any { it == '\uFFFD' },
            "非 UTF-8 字节应被替换成替换符，而不是让读取崩掉；实际 message=${result.message}",
        )
    }

    @Test
    @DisplayName("非 UTF-8 的 FAIL 结果仍按失败处理，不让编码问题把失败洗成通过")
    fun lenientDecodingDoesNotTurnFailIntoPass() {
        val dir = tempResultsDir()
        val content = "status=FAIL\nmessage=".toByteArray(Charsets.US_ASCII) +
            "挂了".toByteArray(charset("GBK"))
        File(dir, McTestkitResultFile.fileName("gbkfail")).writeBytes(content)

        val exception = assertFailsWith<IllegalStateException> {
            ResultReader.read(dir, "gbkfail")
        }

        assertTrue("gbkfail" in exception.message!!, "报错应指出场景名：${exception.message}")
    }
}
