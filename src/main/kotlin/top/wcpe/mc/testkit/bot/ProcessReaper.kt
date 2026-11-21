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
 * pid 文件的内容格式：第一行 pid，第二行进程启动时刻（毫秒）。第二行可选（兼容旧文件）。
 *
 * 为什么要第二行：pid 文件是**跨轮次残留物**，OS 会把旧进程的 pid 回收后分配给新进程。
 * 只认 pid 就会在「陈旧 pid 文件恰好指向无关进程」时误杀用户自己的 IDE / 数据库 / 另一个项目。
 * 同仓库的进程台账路径早已用「命令行 + 启动时刻」核对身份（见 [ProcessRecord.matchesLiveProcess]），
 * 而 pid 文件路径此前**不做任何校验**，两条收尾路径的安全等级不一致。
 */
private const val PID_FILE_START_LINE = 2

/**
 * 写一个带身份的 pid 文件（pid + 启动时刻）。
 *
 * 拿不到启动时刻时**仍写 pid**（不放弃收尾能力），只是读回时无法校验身份，
 * 届时会按「无法确认」拒绝杀（见 [stopProcessByPidFile]）。
 *
 * @param pidFile 目标 pid 文件（父目录自动创建）
 * @param pid 进程号
 */
fun writePidFile(pidFile: File, pid: Long) {
    pidFile.parentFile?.mkdirs()
    val startedAt = ProcessHandle.of(pid).orElse(null)
        ?.info()?.startInstant()?.map { it.toEpochMilli() }?.orElse(null)
    pidFile.writeText(
        if (startedAt == null) pid.toString() else "$pid\n$startedAt",
    )
}

/**
 * 读 pid 文件里的启动时刻；没有第二行 / 解析不了时返回 null（表示「无从校验」）。
 */
private fun readStartMillis(pidFile: File): Long? =
    runCatching {
        pidFile.readLines(Charsets.UTF_8)
            .getOrNull(PID_FILE_START_LINE - 1)
            ?.trim()
            ?.toLongOrNull()
    }.getOrNull()

/**
 * 按 pid 文件结束进程（机器人驱动与结果判定 高风险区：进程生命周期 / 收尾 / 跨平台；模型见 ADR-0004）。
 *
 * 先温和 [ProcessHandle.destroy]，限时未退则 [ProcessHandle.destroyForcibly] 强杀，最后删除 pid 文件。
 * **安全 no-op**：pid 文件缺失 / 内容非法 / 进程已退出都不报错（用于 finalizer/`finally` 收尾，
 * 不应因「本就没起来 / 已自停」而让收尾失败）。跨平台依赖 JDK [ProcessHandle]，不分平台写不同逻辑。
 *
 * **身份校验**：pid 文件带启动时刻（见 [writePidFile]）时，杀之前核对活进程的启动时刻是否一致；
 * 不一致或拿不到活进程的启动时刻 → **拒杀**并清理记录。这与进程台账路径的口径一致，
 * 防的是「陈旧 pid 文件恰好命中被回收复用的 pid」这种误杀。
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
    val pid = pidFile.readText().trim().lines().firstOrNull()?.trim()?.toLongOrNull()
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

    // 身份校验：pid 文件里记了启动时刻就必须一致，否则这个 pid 已被别的进程复用。
    // 拿不到活进程的启动时刻时保守拒杀——宁可漏收尾（并提示手工排查），也不误杀无关进程。
    val expected = readStartMillis(pidFile)
    if (expected != null) {
        val actual = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)
        if (actual == null || actual != expected) {
            logger(
                "pid 文件 ${pidFile.name} 里的进程（pid=$pid）启动时刻与记录不符（记录=$expected，实际=$actual），" +
                    "该 pid 已被复用，不按它收尾以免误杀无关进程；已清理这条陈旧记录",
            )
            pidFile.delete()
            return PidFileStopResult.INVALID_PID_FILE
        }
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
        // Windows 的强杀是异步的：必须再等待一次，调用方才能可靠观察到进程已退出。
        runCatching { handle.onExit().get(GRACEFUL_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }
    logger("已结束进程 pid=$pid")
}

/**
 * 连同子孙进程一起结束（钩子在 Windows 上经 `cmd /c` 起，只杀根进程会留下真正干活的子进程）。
 *
 * 子孙进程必须在根进程退出前记下：根进程一退，它们就不再算根进程的子孙。记下的句柄带启动时刻，
 * 之后强杀时 pid 已被复用也不会误杀。
 */
fun stopProcessTree(pid: Long, handle: ProcessHandle, logger: (String) -> Unit = {}) {
    val descendants = handle.descendants().use { it.toList() }
    stopProcessByPid(pid, handle, logger)
    descendants.forEach { if (it.isAlive) it.destroyForcibly() }
}
