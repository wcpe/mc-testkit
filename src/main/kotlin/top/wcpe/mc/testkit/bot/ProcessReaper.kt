package top.wcpe.mc.testkit.bot

import top.wcpe.mc.testkit.contract.McTestkitPidFile
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
 * **身份校验**：pid 文件带启动时刻（见 [McTestkitPidFile.write]）时，杀之前核对活进程的启动时刻是否一致；
 * 不一致或拿不到活进程的启动时刻 → **拒杀**并清理记录。这与进程台账路径的口径一致，
 * 防的是「陈旧 pid 文件恰好命中被回收复用的 pid」这种误杀。
 *
 * 校验与收尾**共用同一个句柄**（[McTestkitPidFile.verify] 一并返回），不再二次解析 pid：
 * 两次解析之间原进程可能退出、pid 被无关进程占用，那正是本校验要防的情形。
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
    val pid = McTestkitPidFile.readPid(pidFile)
    if (pid == null) {
        // 内容非法：清理脏 pid 文件后 no-op
        pidFile.delete()
        return PidFileStopResult.INVALID_PID_FILE
    }

    // 身份校验：recording 里有启动时刻就必须一致，否则这个 pid 已被别的进程复用。
    // 校验结果带着已解析的句柄，下面直接复用它收尾——不再 ProcessHandle.of(pid) 解析第二遍，
    // 否则两次解析之间原进程可能退出、pid 被无关进程占用，等于把刚排除的误杀风险放回来。
    val handle = when (val verdict = McTestkitPidFile.verify(pidFile, pid)) {
        McTestkitPidFile.Verdict.Gone -> {
            pidFile.delete()
            return PidFileStopResult.PROCESS_GONE
        }

        is McTestkitPidFile.Verdict.Reused -> {
            logger(
                "pid 文件 ${pidFile.name} 里的进程（pid=$pid）启动时刻与记录不符" +
                    "（记录=${verdict.expected}，实际=${verdict.actual}），该 pid 已被复用，" +
                    "不按它收尾以免误杀无关进程；已清理这条陈旧记录",
            )
            pidFile.delete()
            return PidFileStopResult.INVALID_PID_FILE
        }

        // 旧格式（只有 pid、没有启动时刻）或平台取不到启动时刻：无法核对身份。
        //
        // 这里选择**放行并告警**而不是拒杀，理由有两条，方向相反但结论一致：
        // ① 兼容：升级前写下的所有 pid 文件都是单行，拒杀会让它们全部失效——而它们正是
        //    「上一轮被强杀、进程还在跑」的唯一凭证，等于把最需要收尾的场景全废掉；
        // ② 收益有限：pid 复用误杀要求「旧进程已退出 + pid 恰好被无关进程占用 + 那个 pid
        //    仍是本工具写的那个文件」三个条件同时成立，概率远低于残留进程占着端口不让跑。
        // 新格式（两行）已经能精确核对，这条路只会走到旧文件上。
        is McTestkitPidFile.Verdict.Unverifiable -> {
            if (verdict.collectionFailed) {
                // 与「旧文件遗留」不同：新写法明确记了「想记但取不到」，说明这台机器上身份校验
                // 从来没生效过。要说出这一层，否则用户会以为自己一直有保护。
                logger(
                    "pid 文件 ${pidFile.name} 记录了「启动时刻取不到」标记：本平台上 JDK 拿不到" +
                        "进程启动时刻，身份校验无法生效（这是采集失败，不是旧文件遗留）。" +
                        "仍按兼容口径收尾 pid=$pid；如需可靠身份核对，请改用台账（stop 任务）",
                )
            } else {
                logger(
                    "pid 文件 ${pidFile.name} 没有可核对的启动时刻（旧格式或平台不支持），" +
                        "无法确认 pid=$pid 是否仍是本工具起的那个进程；按兼容口径继续收尾。" +
                        "若该 pid 已被无关进程占用，请改用台账（stop 任务）或按端口手工排查",
                )
            }
            verdict.handle
        }

        is McTestkitPidFile.Verdict.Same -> verdict.handle
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
