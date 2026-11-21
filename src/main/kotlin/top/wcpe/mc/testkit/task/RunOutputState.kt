package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.bot.ProcessIdentity
import java.io.File

/**
 * 结果目录里的 `output-run.state`：最近一次构建对该目录输出的所有权记录，下一次构建据此决定能否接手。
 *
 * 格式（UTF-8，按行）：
 * - 构建进行中：`RUNNING`、runId；
 * - 构建已收尾：`CLOSED_OK` 或 `RUN_FAIL`、runId、`bytes=<计入预算的字节数>`、失败原因（成功时为空）、
 *   `live=<pid>@<启动毫秒>,…`——收尾期限到时仍存活的受监管进程，正常为空；`live=unknown` 表示无法确认。
 *
 * 接手规则见 [inspect]：只在上一次构建可能仍有进程在写这个目录时拒绝。已确认收尾干净的失败不连坐后续构建：
 * 那次失败已由它自己的构建报告过，场景结果文件每次开跑前也会清掉，不会被当成本次的结果。
 */
internal object RunOutputState {
    const val FILE_NAME = "output-run.state"

    /** `live=` 最多记这么多条，超出只记 `unknown`。 */
    const val MAX_LIVE = 64

    /** 状态文件的字节上限：写入端受 [MAX_LIVE] 与失败原因长度约束，远小于它；超出即不是本插件写的。 */
    private const val MAX_BYTES = 4096L
    private const val UNKNOWN = "unknown"

    /** 上一次构建留下的记录对本次构建意味着什么。 */
    sealed interface Prior {
        /** 没有上一次的记录。 */
        data object None : Prior

        /** 上一次成功收尾。 */
        data object ClosedOk : Prior

        /** 上一次失败，但它的受监管进程都已确认结束。 */
        data class ContainedFailure(val runId: String, val reason: String) : Prior

        /** 上一次可能仍有进程在写；[detail] 是 ASCII 说明，拼进失败原因。 */
        data class Refused(val detail: String) : Prior
    }

    fun running(runId: String): String = "RUNNING\n$runId\n"

    /** [survivors] 为 null 表示无法确认收尾是否干净。 */
    fun closed(runId: String, bytes: Long, failure: String?, survivors: List<ProcessIdentity>?): String {
        val live = if (survivors == null || survivors.size > MAX_LIVE) UNKNOWN else survivors.joinToString(",")
        val status = if (failure == null) "CLOSED_OK" else "RUN_FAIL"
        return "$status\n$runId\nbytes=$bytes\n${failure.orEmpty()}\nlive=$live\n"
    }

    fun read(file: File): Prior = when {
        !file.isFile -> Prior.None
        file.length() > MAX_BYTES -> Prior.Refused("state=oversized")
        else -> inspect(file.readText(Charsets.UTF_8))
    }

    /** 纯判定；[isAlive] 可注入，便于不起真进程地穷举各种记录。 */
    fun inspect(text: String, isAlive: (ProcessIdentity) -> Boolean = ProcessIdentity::isAlive): Prior {
        if (text.isEmpty()) return Prior.None
        val lines = text.lines()
        val runId = lines.getOrElse(1) { "" }
        return when (lines[0]) {
            "CLOSED_OK" -> Prior.ClosedOk
            "RUNNING" -> Prior.Refused("state=RUNNING run=$runId")
            "RUN_FAIL" -> {
                val live = lines.getOrNull(4)?.takeIf { it.startsWith("live=") }?.removePrefix("live=")
                    ?: return Prior.Refused("state=RUN_FAIL run=$runId live=unrecorded")
                val recorded = parseLive(live) ?: return Prior.Refused("state=RUN_FAIL run=$runId live=$UNKNOWN")
                val alive = recorded.filter(isAlive)
                if (alive.isEmpty()) {
                    Prior.ContainedFailure(runId, lines.getOrElse(3) { "" })
                } else {
                    Prior.Refused("state=RUN_FAIL run=$runId live=${alive.joinToString(",")}")
                }
            }

            else -> Prior.Refused("state=unreadable")
        }
    }

    /** RUN_FAIL 记录里收尾期限到时仍存活的进程（不管现在是否还活着）；其它记录、`live=unknown` 或读不出时为空。 */
    fun survivors(file: File): List<ProcessIdentity> {
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val lines = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("").lines()
        if (lines[0] != "RUN_FAIL") return emptyList()
        val live = lines.getOrNull(4)?.takeIf { it.startsWith("live=") }?.removePrefix("live=") ?: return emptyList()
        return parseLive(live).orEmpty()
    }

    private fun parseLive(text: String): List<ProcessIdentity>? = when (text) {
        "" -> emptyList()
        UNKNOWN -> null
        else -> text.split(',').map { ProcessIdentity.parse(it) ?: return null }.takeIf { it.size <= MAX_LIVE }
    }
}
