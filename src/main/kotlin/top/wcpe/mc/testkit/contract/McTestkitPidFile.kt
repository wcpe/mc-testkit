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
     * 第 2 行写这个标记表示「本框架想记启动时刻，但当场取不到」。
     *
     * 与「旧版本写的单行文件」必须可区分：前者是**本次新写法下的采集失败**（说明这台机器上
     * 身份校验其实没生效，值得告警），后者只是历史遗留。若两者都退化成单行，收尾方无从分辨，
     * 用户也就永远不知道自己其实一直在裸奔。
     */
    const val START_UNKNOWN_MARKER = "unknown"

    /**
     * 写一个带身份的 pid 文件（pid + 启动时刻）。
     *
     * 拿不到启动时刻时仍写 pid（不放弃收尾能力），但第 2 行写 [START_UNKNOWN_MARKER] 而不是
     * 干脆省略——省略会让它看起来和旧版本写的单行文件一模一样，收尾方无法区分「历史遗留」与
     * 「这台机器上身份校验从没生效过」。宁可多一行标记，也不能让采集失败静默混进兼容路径。
     *
     * 父目录自动创建。
     *
     * @param pidFile 目标 pid 文件
     * @param pid 进程号
     */
    fun write(pidFile: File, pid: Long) {
        pidFile.parentFile?.mkdirs()
        val startedAt = startedAtMillis(pid)
        pidFile.writeText(if (startedAt == null) "$pid\n$START_UNKNOWN_MARKER" else "$pid\n$startedAt")
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
     * @return 毫秒时间戳；没有次行（旧文件）、次行是 [START_UNKNOWN_MARKER]、解析不了或
     *   文件读不出来时返回 null，表示「无从校验」
     */
    fun readStartedAtMillis(pidFile: File): Long? = runCatching {
        pidFile.readLines(Charsets.UTF_8).getOrNull(START_LINE_INDEX)?.trim()?.toLongOrNull()
    }.getOrNull()

    /**
     * pid 文件的次行是否明确写着「本框架想记启动时刻但取不到」（[START_UNKNOWN_MARKER]）。
     *
     * 用来把「新写法下的采集失败」与「旧版本遗留的单行文件」区分开：两者都无法校验身份，
     * 但前者意味着这台机器上身份保护一直没生效，值得让收尾方说清楚。
     */
    fun hasUnknownStartMarker(pidFile: File): Boolean = runCatching {
        pidFile.readLines(Charsets.UTF_8).getOrNull(START_LINE_INDEX)?.trim() == START_UNKNOWN_MARKER
    }.getOrDefault(false)

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
     * ——调用方必须自己决定保守方向，本函数不替它决定。
     *
     * 判定结果里带上**已经解析过、已经确认存活的那个句柄**：调用方直接对它收尾，不要再
     * `ProcessHandle.of(pid)` 解析第二遍。二次解析会重新按 pid 查进程，而在两次解析之间
     * 原进程可能恰好退出、pid 被无关进程占用——那正是本校验要防的情形，重复解析等于把
     * 刚排除的风险又放回来一次。
     *
     * 需要说明的**残余风险**：JDK 的 [ProcessHandle] 在多数平台底层仍按 pid 发信号，
     * 因此「校验通过」与「真正发出信号」之间理论上仍有极窄窗口。本框架已把窗口压到
     * 「一次解析 + 一次 kill」，并在进程台账路径上用命令行特征做第二重核对；若需要
     * 进程级绝对隔离，得依赖 pidfd 之类的平台能力（JDK 无公开 API）。
     */
    fun verify(pidFile: File, pid: Long): Verdict {
        val expected = readStartedAtMillis(pidFile)
        val handle = ProcessHandle.of(pid).orElse(null)
            ?: return Verdict.Gone
        if (!handle.isAlive) return Verdict.Gone
        if (expected == null) return Verdict.Unverifiable(handle, collectionFailed = hasUnknownStartMarker(pidFile))
        val actual = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)
            ?: return Verdict.Unverifiable(handle, collectionFailed = hasUnknownStartMarker(pidFile))
        return if (actual == expected) Verdict.Same(handle, expected) else Verdict.Reused(expected, actual)
    }

    /** [verify] 的判定结果。 */
    sealed interface Verdict {
        /** 进程已不存在。 */
        data object Gone : Verdict

        /**
         * 无法核对身份（记录里没有启动时刻，或平台当场取不到活进程的启动时刻）。
         *
         * 携带已解析的 [handle]，供放行收尾时复用，避免二次解析放大竞态窗口。
         *
         * @property handle 已解析并确认存活的句柄
         * @property collectionFailed true 表示 pid 文件里明确写了「想记但取不到」
         *   （[START_UNKNOWN_MARKER]）——这不是旧文件遗留，而是这台机器上身份校验真的没生效，
         *   值得让调用方说清楚；false 表示旧版本遗留的单行文件
         */
        data class Unverifiable(val handle: ProcessHandle, val collectionFailed: Boolean = false) : Verdict

        /**
         * 仍是当初那个进程。
         *
         * @property handle 已解析并确认存活的句柄，调用方直接对它收尾
         * @property startedAtMillis 双方一致的启动时刻
         */
        data class Same(val handle: ProcessHandle, val startedAtMillis: Long) : Verdict

        /** pid 已被别的进程复用：[expected] 是记录的时刻，[actual] 是现占用者的时刻。 */
        data class Reused(val expected: Long, val actual: Long) : Verdict
    }
}
