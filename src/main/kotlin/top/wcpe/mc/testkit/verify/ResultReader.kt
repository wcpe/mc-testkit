package top.wcpe.mc.testkit.verify

import top.wcpe.mc.testkit.contract.McTestkitResultFile
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
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
 * 结果文件读取与判定（机器人驱动与结果判定）。
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

        val properties = loadProperties(resultFile, scenario)
        val status = properties.getProperty(McTestkitResultFile.STATUS_KEY).orEmpty()
        val message = properties.getProperty(McTestkitResultFile.MESSAGE_KEY).orEmpty()

        if (status != McTestkitResultFile.PASS) {
            val reason = message.ifBlank { "结果文件未标记 ${McTestkitResultFile.PASS}（status=${status.ifBlank { "<空>" }}）" }
            error("E2E 场景 $scenario 失败：$reason")
        }
        return ScenarioResult(status = status, message = message)
    }

    /**
     * 读结果文件，UTF-8 解码。
     *
     * 用 UTF-8 Reader 加载：`Properties.load(InputStream)` 按 ISO-8859-1 解码会把中文 message 弄乱，
     * 而桩按 UTF-8 写结果文件（API.md：配置文件 UTF-8）。
     *
     * ## 为什么严格解码失败时要降级而不是直接抛
     *
     * 严格 UTF-8 在遇到非法字节时抛 `MalformedInputException`——那是个英文异常，既没有文件路径、
     * 也看不出是编码问题，最终只表现为「读结果文件时构建崩了」。而消费方桩若用平台默认编码
     * （Windows 上是 GBK）写 message，就会精确地踩中这个形态。
     *
     * `status` 是 ASCII（PASS / FAIL），任何单字节编码解码出来都一样，所以**降级后判定依然可靠**；
     * 受影响的只有 `message` 的可读性。因此：先用严格解码，失败则用 REPLACE 重读并**明确告警**
     * （告诉用户哪个文件、可能是什么原因）——既不把编码问题伪装成崩溃，也不让它彻底静默。
     */
    private fun loadProperties(resultFile: File, scenario: String): Properties {
        val bytes = resultFile.readBytes()
        val strict = Properties()
        try {
            strict.load(bytes.inputStream().reader(Charsets.UTF_8))
            return strict
        } catch (ex: Exception) {
            // 落到下面的宽容解码；这里只记原因，真正的告警在下面统一给出
        }

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val lenient = Properties().apply {
            InputStreamReader(bytes.inputStream(), decoder).use { load(it) }
        }
        val replacementCount = lenient.getProperty(McTestkitResultFile.MESSAGE_KEY).orEmpty().count { it == REPLACEMENT_CHAR }
        warn(
            "[mc-testkit] 结果文件不是合法的 UTF-8：${resultFile.absolutePath}（场景 $scenario）。" +
                "已按宽容方式解码——status 是 ASCII，判定不受影响；" +
                "但 message 里出现了 $replacementCount 个替换符（�），可读性受损。" +
                "请让桩以 UTF-8 写结果文件（Windows 上常见原因是用了平台默认编码 GBK）。",
        )
        return lenient
    }

    /** UTF-8 解码失败时替换出的字符。 */
    private const val REPLACEMENT_CHAR = '\uFFFD'

    /**
     * 告警出口。默认落 stderr——`verify/` 与 `bot/` 一样只依赖 `contract/` 与 JDK，
     * 不引入 Gradle API；任务侧可在 `apply` 时接到 `project.logger`。
     */
    @Volatile
    private var warningSink: (String) -> Unit = { System.err.println(it) }

    /** 任务侧注入告警出口（可选；不注入也能工作）。 */
    fun useWarningSink(sink: (String) -> Unit) {
        warningSink = sink
    }

    private fun warn(message: String) = warningSink(message)
}
