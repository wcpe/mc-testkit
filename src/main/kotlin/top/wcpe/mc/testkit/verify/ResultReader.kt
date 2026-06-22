package top.wcpe.mc.testkit.verify

import top.wcpe.mc.testkit.contract.McTestkitResultFile
import java.io.File
import java.util.Properties

/**
 * 一次场景的判定结论（结果文件解析所得，纯数据）。
 *
 * @property status 结果状态（[McTestkitResultFile.PASS] / [McTestkitResultFile.FAIL] 或桩写出的其它值）。
 * @property message 结论说明（失败原因写这里；缺省为空串）。
 */
data class ScenarioResult(
    val status: String,
    val message: String,
) {
    /** 是否判定通过。 */
    val isPass: Boolean get() = status == McTestkitResultFile.PASS
}

/**
 * 结果文件读取与判定（FR-06）。
 *
 * **只认结果文件**：编排 / 任务不绕过结果文件自行判 PASS（架构不变量真源、NFR 正确性优先）。
 * 读 `<scenario>.properties`（[McTestkitResultFile]）：文件缺失或 `status≠PASS` 抛**中文**错误，
 * 否则返回 [ScenarioResult]。是纯函数，便于穷举单测。
 */
object ResultReader {

    /**
     * 读取某场景的结果文件并判定。
     *
     * @param resultsDir 结果文件所在目录（桩按约定写入此处）。
     * @param scenario 场景名（决定文件名 `<scenario>.properties`）。
     * @return 判定通过时的结论（含 message）。
     * @throws IllegalStateException 结果文件缺失，或 `status≠PASS`（中文说明原因 / 路径）。
     */
    fun read(resultsDir: File, scenario: String): ScenarioResult {
        val resultFile = File(resultsDir, McTestkitResultFile.fileName(scenario))
        if (!resultFile.exists()) {
            error("未生成 E2E 结果文件：${resultFile.absolutePath}（场景 $scenario 的桩未写出结果，请检查桩是否正常运行）")
        }

        // 用 UTF-8 Reader 加载：Properties.load(InputStream) 按 ISO-8859-1 解码会把中文 message 弄乱，
        // 桩按 UTF-8 写结果文件（API.md：配置文件 UTF-8），故这里必须以 UTF-8 读。
        val properties = Properties().apply {
            resultFile.reader(Charsets.UTF_8).use { load(it) }
        }
        val status = properties.getProperty(McTestkitResultFile.STATUS_KEY).orEmpty()
        val message = properties.getProperty(McTestkitResultFile.MESSAGE_KEY).orEmpty()

        if (status != McTestkitResultFile.PASS) {
            val reason = message.ifBlank { "结果文件未标记 ${McTestkitResultFile.PASS}（status=${status.ifBlank { "<空>" }}）" }
            error("E2E 场景 $scenario 失败：$reason")
        }
        return ScenarioResult(status = status, message = message)
    }
}
