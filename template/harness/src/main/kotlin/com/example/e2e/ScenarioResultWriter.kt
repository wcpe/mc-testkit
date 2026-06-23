package com.example.e2e

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * 结果文件写出器（照抄物）。
 *
 * 把场景判定结论写成 `<scenario>.properties`，键名严格对齐 mc-testkit 冻结契约
 * （docs/API.md §3.5）：`status`(PASS/FAIL) / `message`(结论说明) + 可选场景明细键。
 * 编排的 verify 任务**只认这个文件**判 PASS/FAIL，故这里是测试结论的唯一真源。
 */
class ScenarioResultWriter(private val resultFile: File) {

    /**
     * 写出结果文件。
     *
     * @param status 结果状态，取 [STATUS_PASS] / [STATUS_FAIL]。
     * @param message 结论说明（失败时写失败原因）。
     * @param details 场景特定明细键（如 rewardCount / txId），由消费方按需补充。
     */
    fun write(status: String, message: String, details: Map<String, String> = emptyMap()) {
        resultFile.parentFile?.mkdirs()
        val properties = Properties().apply {
            // 先写明细，再写 status/message，保证后者不被同名明细键覆盖
            details.forEach { (key, value) -> setProperty(key, value) }
            setProperty(KEY_STATUS, status)
            setProperty(KEY_MESSAGE, message)
        }
        // 原子写出：先写同目录临时文件，再原子移动替换目标——避免编排 verify 在桩写到一半时读到半成品结果
        // 文件（docs/API.md §3.5：结果文件须原子落盘）。
        val dir = resultFile.absoluteFile.parentFile
        val temp = File.createTempFile("result-", ".tmp", dir)
        try {
            temp.outputStream().use { properties.store(it, "mc-testkit E2E result") }
            try {
                Files.move(temp.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** 结果状态键（对齐契约 status）。 */
        const val KEY_STATUS = "status"

        /** 结果说明键（对齐契约 message）。 */
        const val KEY_MESSAGE = "message"

        /** 通过状态值。 */
        const val STATUS_PASS = "PASS"

        /** 失败状态值。 */
        const val STATUS_FAIL = "FAIL"
    }
}
