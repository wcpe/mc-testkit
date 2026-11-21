package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.bot.ProcessIdentity
import java.io.File

/**
 * 结果目录里的 `output-run.state`：最近一次构建对该目录输出的所有权记录，下一次构建据此决定能否接手。
 *
 * 格式（UTF-8，按行）：
 * - 构建进行中：`RUNNING`、runId、`owner=<pid>@<启动毫秒>`——跑这次构建的 JVM，取不到启动时刻时为空；
 * - 构建已收尾：`CLOSED_OK` 或 `RUN_FAIL`、runId、`bytes=<计入预算的字节数>`、失败原因（成功时为空）、
 *   `live=<pid>@<启动毫秒>,…`——收尾期限到时仍存活的受监管进程，正常为空；`live=unknown` 表示无法确认。
 *
 * 接手规则见 [inspect]：只在上一次构建可能仍有进程在写这个目录时拒绝。已确认收尾干净的失败不连坐后续构建：
 * 那次失败已由它自己的构建报告过，场景结果文件每次开跑前也会清掉，不会被当成本次的结果。没收尾就结束的构建
 * （停在 `RUNNING`，持有者已不在）同样不连坐，只要进程台账里没有它留下的仍在写日志的进程。
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

        /** 上一次没收尾就结束了（构建 JVM 被强杀、断电，或收尾前出错），进程台账里也没有它留下的存活进程。 */
        data class Abandoned(val runId: String) : Prior

        /** 上一次可能仍有进程在写；[detail] 是 ASCII 说明，拼进失败原因；[remedy] 是让后续构建恢复接手的办法。 */
        data class Refused(val detail: String, val remedy: Remedy) : Prior
    }

    /** 被拒后怎么恢复。 */
    enum class Remedy {
        /** 跑 stop 任务按进程台账收掉之前的构建留下的进程；它们都结束后，下一次构建自己接手。 */
        STOP_PROCESSES,

        /** 持有记录的构建 JVM 还在运行：等它结束。 */
        WAIT_OWNER,

        /** 记录本身无法确认：收掉能找到的进程、确认没有进程在写这个目录后，手工删掉状态文件。 */
        DELETE_STATE,
    }

    /** [owner] 为 null（取不到启动时刻）时，这次构建若没收尾就结束，下一次构建只能按无法确认处理。 */
    fun running(runId: String, owner: ProcessIdentity?): String = "RUNNING\n$runId\nowner=${owner ?: ""}\n"

    /** [survivors] 为 null 表示无法确认收尾是否干净。 */
    fun closed(runId: String, bytes: Long, failure: String?, survivors: List<ProcessIdentity>?): String {
        val live = if (survivors == null || survivors.size > MAX_LIVE) UNKNOWN else survivors.joinToString(",")
        val status = if (failure == null) "CLOSED_OK" else "RUN_FAIL"
        return "$status\n$runId\nbytes=$bytes\n${failure.orEmpty()}\nlive=$live\n"
    }

    /** [liveWriters] 见 [inspect]。 */
    fun read(file: File, liveWriters: () -> List<String> = { emptyList() }): Prior = when {
        !file.isFile -> Prior.None
        file.length() > MAX_BYTES -> Prior.Refused("state=oversized", Remedy.DELETE_STATE)
        else -> inspect(file.readText(Charsets.UTF_8), liveWriters = liveWriters)
    }

    /**
     * 纯判定；进程是否存活、是否就是当前 JVM、台账里还剩哪些存活进程都可注入，便于不起真进程地穷举各种记录。
     *
     * @param isCurrentJvm 一个 Gradle 守护进程同时只跑一个构建：持有者就是当前 JVM 时，那次构建必定已经结束。
     * @param liveWriters 进程台账里身份核对得上、仍存活、又不归本次构建监管的 bot 与钩子进程的 key；
     *   只在持有者已结束时才查。
     */
    fun inspect(
        text: String,
        isAlive: (ProcessIdentity) -> Boolean = ProcessIdentity::isAlive,
        isCurrentJvm: (ProcessIdentity) -> Boolean = { it == ProcessIdentity.current() },
        liveWriters: () -> List<String> = { emptyList() },
    ): Prior {
        if (text.isEmpty()) return Prior.None
        val lines = text.lines()
        val runId = lines.getOrElse(1) { "" }
        return when (lines[0]) {
            "CLOSED_OK" -> Prior.ClosedOk
            "RUNNING" -> {
                val owner = lines.getOrNull(2)?.takeIf { it.startsWith("owner=") }?.removePrefix("owner=")?.let(ProcessIdentity::parse)
                    ?: return Prior.Refused("state=RUNNING run=$runId owner=unrecorded", Remedy.DELETE_STATE)
                if (!isCurrentJvm(owner) && isAlive(owner)) {
                    return Prior.Refused("state=RUNNING run=$runId owner=$owner", Remedy.WAIT_OWNER)
                }
                val writers = liveWriters()
                if (writers.isEmpty()) {
                    Prior.Abandoned(runId)
                } else {
                    Prior.Refused("state=RUNNING run=$runId owner=ended ledger-live=${writers.size}", Remedy.STOP_PROCESSES)
                }
            }

            "RUN_FAIL" -> {
                val live = lines.getOrNull(4)?.takeIf { it.startsWith("live=") }?.removePrefix("live=")
                    ?: return Prior.Refused("state=RUN_FAIL run=$runId live=unrecorded", Remedy.DELETE_STATE)
                val recorded = parseLive(live)
                    ?: return Prior.Refused("state=RUN_FAIL run=$runId live=$UNKNOWN", Remedy.DELETE_STATE)
                val alive = recorded.filter(isAlive)
                if (alive.isEmpty()) {
                    Prior.ContainedFailure(runId, lines.getOrElse(3) { "" })
                } else {
                    Prior.Refused("state=RUN_FAIL run=$runId live=${alive.joinToString(",")}", Remedy.STOP_PROCESSES)
                }
            }

            else -> Prior.Refused("state=unreadable", Remedy.DELETE_STATE)
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
