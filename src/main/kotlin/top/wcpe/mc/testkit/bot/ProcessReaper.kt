package top.wcpe.mc.testkit.bot

import java.io.File
import java.util.concurrent.TimeUnit

/** 收尾时等待进程温和退出的上限（秒），超时则强杀。 */
private const val GRACEFUL_EXIT_TIMEOUT_SECONDS = 15L

/**
 * 按 pid 文件结束进程（FR-06 高风险区：进程生命周期 / 收尾 / 跨平台；模型见 ADR-0004）。
 *
 * 先温和 [ProcessHandle.destroy]，限时未退则 [ProcessHandle.destroyForcibly] 强杀，最后删除 pid 文件。
 * **安全 no-op**：pid 文件缺失 / 内容非法 / 进程已退出都不报错（用于 finalizer/`finally` 收尾，
 * 不应因「本就没起来 / 已自停」而让收尾失败）。跨平台依赖 JDK [ProcessHandle]，不分平台写不同逻辑。
 *
 * @param pidFile 记录目标进程 pid 的文件。
 * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
 */
fun stopProcessByPidFile(pidFile: File, logger: (String) -> Unit = {}) {
    if (!pidFile.exists()) {
        // 进程本就没起来（或已被收尾）：no-op
        return
    }
    val pid = pidFile.readText().trim().toLongOrNull()
    if (pid == null) {
        // 内容非法：清理脏 pid 文件后 no-op
        pidFile.delete()
        return
    }

    ProcessHandle.of(pid).ifPresent { handle ->
        handle.destroy()
        try {
            handle.onExit().get(GRACEFUL_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
            // 超时 / 中断 / 已退出：落到下面按存活状态决定是否强杀
        }
        if (handle.isAlive) {
            handle.destroyForcibly()
        }
        logger("已结束进程 pid=$pid（来自 ${pidFile.name}）")
    }
    // 进程已退出（of 返回空）或已收尾：删除 pid 文件
    pidFile.delete()
}
