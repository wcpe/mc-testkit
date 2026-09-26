package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.bot.PidFileStopResult
import top.wcpe.mc.testkit.bot.stopProcessByPid
import top.wcpe.mc.testkit.bot.stopProcessByPidFile
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 收尾一组进程：先按 pid 文件（快路径），再用进程台账兜底（跨轮次逃逸路径）。
 *
 * **两条路径的必要性**：pid 文件是「当前一轮」的凭证（明文 pid、无需核对身份），命中即最快收尾；
 * 但它会被下一轮起服覆盖 / 随运行目录清理消失，故上一轮被强杀留下的进程只能靠台账找回来
 * （见 [ProcessLedger]）。两者都走同一杀进程原语（[stopProcessByPid]）。
 *
 * **安全边界**：台账路径在杀之前核对命令特征（[ProcessRecord.matchesLiveProcess]）——pid 复用
 * （旧 pid 被无关进程占用）时**拒绝杀**并清理记录，绝不误伤用户自己的进程。
 *
 * @param resultsDir 结果目录（pid 文件与台账都在此）。
 * @param pidFiles 待收尾的 pid 文件（可含不存在的；缺失即跳过）。
 * @param logger 中文分级日志输出。
 * @return 收尾摘要（供停任务打印可操作反馈，见 [StopSummary]）。
 */
internal fun stopTrackedProcesses(
    resultsDir: File,
    pidFiles: List<File>,
    logger: (String) -> Unit,
): StopSummary {
    val results = pidFiles.map { stopProcessByPidFile(it, logger) }

    // 台账兜底：pid 文件缺失时进程可能逃逸到上一轮（这正是本路径存在的理由）
    var ledgerStopped = 0
    var ledgerSkipped = 0
    ProcessLedger.records(resultsDir).forEach { record ->
        if (!record.isAlive()) {
            ProcessLedger.unrecord(resultsDir, record.key)
            return@forEach
        }
        if (record.matchesLiveProcess()) {
            ProcessHandle.of(record.pid).ifPresent { handle ->
                stopProcessByPid(record.pid, handle, logger)
                logger("按台账收尾跨轮次残留进程（${record.key}）")
            }
            ProcessLedger.unrecord(resultsDir, record.key)
            ledgerStopped++
        } else {
            // pid 已被复用或读不到命令行：保守拒杀（宁可漏收尾并提示手工排查，也不误伤无关进程）
            logger("台账记录 ${record.key}（pid=${record.pid}）命令行与登记特征不符，已跳过并清理该记录（避免误杀）")
            ProcessLedger.unrecord(resultsDir, record.key)
            ledgerSkipped++
        }
    }

    return StopSummary(
        stopped = results.count { it == PidFileStopResult.STOPPED } + ledgerStopped,
        pidFileMissing = results.count { it == PidFileStopResult.NO_PID_FILE },
        ledgerStopped = ledgerStopped,
        ledgerSkipped = ledgerSkipped,
    )
}

/**
 * 一次停任务收尾的摘要（供打印可操作反馈）。
 *
 * @property stopped 实际被结束的进程数（含台账兜底的跨轮残留）。
 * @property pidFileMissing 缺失的 pid 文件数（>0 说明该节点没起来，或进程逃逸后记录已丢）。
 * @property ledgerStopped 其中由台账兜底找回并收尾的进程数（>0 即「跨轮次逃逸」确实发生过）。
 * @property ledgerSkipped 台账记录因身份不符而跳过的条数（通常是 pid 复用，属安全拒绝）。
 */
internal data class StopSummary(
    val stopped: Int,
    val pidFileMissing: Int,
    val ledgerStopped: Int,
    val ledgerSkipped: Int,
)

/**
 * 探测端口是否仍被占用（收尾后复验用；判定口径与起服前的 [requirePortsAvailable] 刚好相反）。
 *
 * 只看「能否连上」：收尾后的残留进程通常仍在监听，能连上即说明端口没释放。
 * 纯判定、不抛错（收尾路径不得因复验失败而失败）。
 *
 * @param timeoutMs 连接超时（毫秒）；收尾复验用短超时即可。
 */
internal fun portsStillOccupied(ports: List<Int>, timeoutMs: Int = 300): List<Int> = ports.filter { port ->
    try {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }
}
