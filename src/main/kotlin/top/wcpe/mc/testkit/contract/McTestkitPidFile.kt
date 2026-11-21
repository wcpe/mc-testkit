package top.wcpe.mc.testkit.contract

import java.io.File

/**
 * pid 文件的内容契约（跨 `provision` / `bot` / `dsl` / `task` 四个包共用）。
 *
 * ## 格式
 *
 * 第一行 pid，第二行进程启动时刻（毫秒，epoch）。第二行**可选**——旧版本写下的文件只有一行，
 * 本契约必须继续能读（否则升级后第一次收尾会把它们全判成脏文件）。
 *
 * ## 为什么第二行是必要的
 *
 * pid 文件是**跨轮次残留物**：它落在运行目录 / 结果目录，不随进程退出自动消失。而操作系统会在旧
 * 进程退出后回收它的 pid 并分配给新进程。只认 pid 就会在「陈旧 pid 文件恰好指向无关进程」时
 * 误杀用户自己的 IDE / 数据库 / 另一个项目。
 *
 * 同仓库的进程台账（`bot/ProcessLedger`）早已用「命令行 + 启动时刻」核对身份；pid 文件路径此前
 * **不做任何校验**，两条收尾路径的安全等级并不一致。本契约把同一套判据补到 pid 文件上。
 *
 * ## 放在 `contract/` 的理由
 *
 * 写 pid 的是 `provision/ServerLauncher`、`bot/BotLauncher`、`dsl/ScenarioHooks`，
 * 读 pid 的是 `bot/ProcessReaper`、`dsl/ScenarioHookRuntime`、`task/ProcessStopper`。
 * 四个包都要用，而 `contract/` 是唯一被允许「全包共享」的包（见 ARCHITECTURE 的内部包接缝）。
 * 把它留在 `bot/` 会让 `provision/` 反向依赖 `bot/`，破坏「`bot/` 仅依赖 `contract/` 与 JDK」这条既有约束。
 */
object McTestkitPidFile {

    /** 启动时刻所在的行号（1 起）。 */
    private const val START_LINE_INDEX = 1

    /**
     * 写一个带身份的 pid 文件（pid + 启动时刻）。
     *
     * 拿不到启动时刻时**仍写 pid**，不放弃收尾能力：此时读回无法校验身份，收尾方会按
     * 「无法确认」保守处理，而不是拒绝整条路径。宁可这次少校验一次，也不能让进程彻底失去收尾凭证。
     *
     * 父目录自动创建。
     *
     * @param pidFile 目标 pid 文件
     * @param pid 进程号
     */
    fun write(pidFile: File, pid: Long) {
        pidFile.parentFile?.mkdirs()
        val startedAt = startedAtMillis(pid)
        pidFile.writeText(if (startedAt == null) pid.toString() else "$pid\n$startedAt")
    }

    /**
     * 读 pid 文件的 pid（首行）。
     *
     * 只认首行、按行切分而不是按固定字节数读取：pid 位数与启动时刻位数都不固定，读死 N 字节
     * 会在位数变化时拿到半截数字，然后静默失败（表现为「pid 文件存在但收尾被跳过」）。
     *
     * @return pid；文件缺失、内容为空或首行不是正整数时返回 null
     */
    fun readPid(pidFile: File): Long? = runCatching {
        pidFile.readLines(Charsets.UTF_8).firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * 读 pid 文件记录的启动时刻（次行）。
     *
     * @return 毫秒时间戳；没有次行（旧文件）、解析不了或文件读不出来时返回 null，表示「无从校验」
     */
    fun readStartedAtMillis(pidFile: File): Long? = runCatching {
        pidFile.readLines(Charsets.UTF_8).getOrNull(START_LINE_INDEX)?.trim()?.toLongOrNull()
    }.getOrNull()

    /**
     * 某个活进程的启动时刻（毫秒）。
     *
     * @return 毫秒时间戳；进程不存在或平台拿不到启动时刻时返回 null
     */
    fun startedAtMillis(pid: Long): Long? =
        ProcessHandle.of(pid).orElse(null)
            ?.info()?.startInstant()?.map { it.toEpochMilli() }?.orElse(null)

    /**
     * pid 文件记录的进程是否**仍然是当初那个进程**。
     *
     * 判据是「pid 仍存活 **且** 启动时刻与记录一致」。记录里没有启动时刻时返回 [Verdict.Unverifiable]
     * ——调用方必须自己决定保守方向，本函数不替它决定。收尾路径（`stopProcessByPidFile`）对
     * [Verdict.Unverifiable] 选择**放行并告警**，理由见那里的注释（旧文件兼容 + 误杀概率远低于
     * 残留进程占着端口的代价）。
     */
    fun verify(pidFile: File, pid: Long): Verdict {
        val expected = readStartedAtMillis(pidFile)
        val handle = ProcessHandle.of(pid).orElse(null)
            ?: return Verdict.Gone
        if (!handle.isAlive) return Verdict.Gone
        if (expected == null) return Verdict.Unverifiable
        val actual = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)
            ?: return Verdict.Unverifiable
        return if (actual == expected) Verdict.Same(expected) else Verdict.Reused(expected, actual)
    }

    /** [verify] 的判定结果。 */
    sealed interface Verdict {
        /** 进程已不存在。 */
        data object Gone : Verdict

        /** 记录里没有启动时刻（旧文件），无法确认是不是同一个进程。 */
        data object Unverifiable : Verdict

        /** 仍是当初那个进程：[startedAtMillis] 是双方一致的启动时刻。 */
        data class Same(val startedAtMillis: Long) : Verdict

        /** pid 已被别的进程复用：[expected] 是记录的时刻，[actual] 是现占用者的时刻。 */
        data class Reused(val expected: Long, val actual: Long) : Verdict
    }
}
