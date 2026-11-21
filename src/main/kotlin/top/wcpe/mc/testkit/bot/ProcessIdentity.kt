package top.wcpe.mc.testkit.bot

import java.time.Instant

/**
 * 跨构建识别一个进程：pid 加启动时刻（毫秒）。pid 会被系统复用，只认 pid 可能把无关进程当成原来那个，带上启动时刻就不会。
 *
 * 用于在 `output-run.state` 里记下收尾期限到时仍存活的受监管进程，下一次构建据此判断它们是否已经结束。
 */
internal data class ProcessIdentity(val pid: Long, val startMillis: Long) {
    /** 仍是同一个进程且仍存活。 */
    fun isAlive(): Boolean = ProcessHandle.of(pid).map { handle ->
        handle.isAlive && handle.info().startInstant().map(Instant::toEpochMilli).orElse(null) == startMillis
    }.orElse(false)

    override fun toString(): String = "$pid@$startMillis"

    companion object {
        /** 取不到启动时刻的进程无法可靠识别，返回 null。 */
        fun of(handle: ProcessHandle): ProcessIdentity? =
            handle.info().startInstant().map { ProcessIdentity(handle.pid(), it.toEpochMilli()) }.orElse(null)

        /** 解析 [toString] 的输出，格式不对返回 null。 */
        fun parse(text: String): ProcessIdentity? {
            val parts = text.split('@')
            if (parts.size != 2) return null
            val pid = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val start = parts[1].toLongOrNull() ?: return null
            return ProcessIdentity(pid, start)
        }
    }
}
