package top.wcpe.mc.testkit.bot

import java.io.File
import java.util.concurrent.TimeUnit

/** 收尾时等待进程温和退出的上限（秒），超时则强杀。 */
private const val GRACEFUL_EXIT_TIMEOUT_SECONDS = 15L

/**
 * 一次「按 pid 文件收尾」的结果（供停任务据此给出可操作反馈，见 [stopProcessByPidFile]）。
 *
 * 分开这几种情形是为了让**收尾日志说真话**：此前一律静默 no-op，用户无法区分「本来就没起 /
 * 已收尾」与「pid 记录丢失、进程逃逸到上一轮」——后者恰恰是最需要提示的情形。
 */
enum class PidFileStopResult {
    /** pid 文件不存在：进程本就没起、已被收尾，或**跨轮次逃逸**（pid 记录被后续轮次覆盖 / 清理）。 */
    NO_PID_FILE,

    /** pid 文件内容非法（脏文件）：已清理，未收尾任何进程。 */
    INVALID_PID_FILE,

    /** pid 指向的进程已不存在：陈旧记录，已清理，未收尾任何进程。 */
    PROCESS_GONE,

    /** 已结束该进程并删除 pid 文件。 */
    STOPPED,
}

/**
 * 按 pid 文件结束进程（机器人驱动与结果判定 高风险区：进程生命周期 / 收尾 / 跨平台；模型见 ADR-0004）。
 *
 * 先温和 [ProcessHandle.destroy]，限时未退则 [ProcessHandle.destroyForcibly] 强杀，最后删除 pid 文件。
 * **安全 no-op**：pid 文件缺失 / 内容非法 / 进程已退出都不报错（用于 finalizer/`finally` 收尾，
 * 不应因「本就没起来 / 已自停」而让收尾失败）。跨平台依赖 JDK [ProcessHandle]，不分平台写不同逻辑。
 *
 * 返回值区分「是否真收尾了东西」：调用方（停任务）据此给出可操作提示，但**不得**因返回
 * [PidFileStopResult.NO_PID_FILE] 而让收尾失败——收尾路径本身必须幂等无副作用。
 *
 * @param pidFile 记录目标进程 pid 的文件。
 * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
 * @return 收尾结果，见 [PidFileStopResult]。
 */
fun stopProcessByPidFile(pidFile: File, logger: (String) -> Unit = {}): PidFileStopResult {
    if (!pidFile.exists()) {
        // 进程本就没起来（或已被收尾）：no-op
        return PidFileStopResult.NO_PID_FILE
    }
    val pid = pidFile.readText().trim().toLongOrNull()
    if (pid == null) {
        // 内容非法：清理脏 pid 文件后 no-op
        pidFile.delete()
        return PidFileStopResult.INVALID_PID_FILE
    }

    val handle = ProcessHandle.of(pid).orElse(null)
    if (handle == null) {
        // 进程已退出：删除陈旧 pid 文件
        pidFile.delete()
        return PidFileStopResult.PROCESS_GONE
    }
    stopProcessByPid(pid, handle, logger)
    pidFile.delete()
    return PidFileStopResult.STOPPED
}

/**
 * 按 pid 结束一个进程（收尾原语：pid 文件路径与进程台账路径共用，避免两处各写一遍杀进程逻辑）。
 *
 * 先温和 [ProcessHandle.destroy]，限时未退则 [ProcessHandle.destroyForcibly] 强杀。
 * 进程早已退出（或 pid 已被回收）时安全 no-op。
 *
 * @param pid 目标进程号。
 * @param handle 已解析的进程句柄（调用方通常已 `ProcessHandle.of` 过，直接复用省一次解析）。
 * @param logger 中文分级日志输出（默认 no-op）。
 */
fun stopProcessByPid(pid: Long, handle: ProcessHandle, logger: (String) -> Unit = {}) {
    handle.destroy()
    try {
        handle.onExit().get(GRACEFUL_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (ignored: Exception) {
        // 超时 / 中断 / 已退出：落到下面按存活状态决定是否强杀
    }
    if (handle.isAlive) {
        handle.destroyForcibly()
    }
    logger("已结束进程 pid=$pid")
}
