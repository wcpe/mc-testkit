package top.wcpe.mc.testkit.bot

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次测试 run 的输出字节预算（机器人驱动与结果判定 高风险区：进程生命周期 / 磁盘）。
 *
 * ## 为什么需要它
 *
 * 事故现场：9 个 bot 日志合计 **130 GB**（单文件最大 14.7 GB），盘从 183 GB 空闲降到 53 GB。
 * 根因的**结构性部分**是：`ProcessBuilder.Redirect.appendTo(File)` 由**内核直接写文件**，
 * JVM 侧完全不经过这条数据流——**没有任何位置可以计数、限流或中断**。不是「上限设得太大」，
 * 而是**结构上没有上限的挂载点**。
 *
 * ## 口径
 *
 * - [perSinkLimit]：**单个**输出目的地（一个 bot 的一份日志）的 stdout+stderr 合计上限。
 * - [runLimit]：**整个 run** 跨所有 sink 的合计上限。同一 run 内多次 [acquire] 共享同一份已用字节，
 *   不因再次创建 sink 而重置。
 * - 超限是**不可逆**的：一旦 [overrun]，本 run 后续任何 sink 一律不再写入正文。
 *
 * ## 并发
 *
 * 多个 pump 线程可能同时写。`usedRunBytes` 用 [AtomicLong] 累加；超限判定与置位用
 * [AtomicBoolean] 保证**只发生一次**（第一个触限的 sink 负责写标记行）。
 *
 * @property perSinkLimit 单 sink 字节上限（必须为正有限值）。
 * @property runLimit 整 run 字节上限（必须为正有限值）。
 */
class OutputBudget(
    val perSinkLimit: Long = DEFAULT_PER_SINK_LIMIT,
    val runLimit: Long = DEFAULT_RUN_LIMIT,
) {
    init {
        // 配置校验：0 / 负数 / NaN 一律拒绝，**不得**解释成「无限」。
        require(perSinkLimit > 0) { "单输出上限必须为正数，实际 $perSinkLimit" }
        require(runLimit > 0) { "整 run 输出上限必须为正数，实际 $runLimit" }
        require(perSinkLimit <= MAX_LIMIT) { "单输出上限不得超过 $MAX_LIMIT 字节，实际 $perSinkLimit" }
        require(runLimit <= MAX_LIMIT) { "整 run 输出上限不得超过 $MAX_LIMIT 字节，实际 $runLimit" }
        require(perSinkLimit <= runLimit) { "单输出上限 $perSinkLimit 不应超过整 run 上限 $runLimit" }
    }

    private val usedRunBytes = AtomicLong(0)
    private val overrunFlag = AtomicBoolean(false)
    private val overrunDetail = AtomicReferenceHolder()

    /** 本 run 已写出的正文字节数（不含超限标记行）。 */
    fun usedBytes(): Long = usedRunBytes.get()

    /** 本 run 是否已触限（不可逆）。 */
    fun isOverrun(): Boolean = overrunFlag.get()

    /** 触限说明（未触限时为 null）。 */
    fun overrunReason(): String? = overrunDetail.get()

    /**
     * 为本 run 的**一个**输出目的地开一份限额视图。
     *
     * 返回的 [Sink] 维护自己的已用字节；写入时**同时**受单 sink 与整 run 两个上限约束。
     *
     * @param label 人类可读的 sink 名（通常是 bot key），只用于标记行与日志。
     */
    fun acquire(label: String): Sink = Sink(this, label)

    /**
     * 尝试为本 sink 记账 [bytes] 字节，返回**实际允许写入**的字节数。
     *
     * 允许值可能小于请求值（触限时截断到最后可用空间）；返回 0 表示不得再写正文。
     * 一旦本 run 触限，此后恒返回 0。
     *
     * 这段是**先记账后写**：先占额度再落盘，避免多线程各自判断「还够」而合计超量。
     */
    internal fun tryConsume(bytes: Int): Int {
        if (bytes <= 0) return 0
        if (overrunFlag.get()) return 0
        var allowed = 0
        while (true) {
            val used = usedRunBytes.get()
            val room = runLimit - used
            if (room <= 0) return 0
            val take = minOf(bytes.toLong(), room).toInt()
            if (usedRunBytes.compareAndSet(used, used + take)) {
                allowed = take
                break
            }
        }
        return allowed
    }

    /**
     * 置位「本 run 已触限」。只有**第一个**调用者会拿到 true（负责写标记行）。
     *
     * @param reason 触限原因（单 sink 还是整 run、实际写了多少）。
     */
    internal fun markOverrun(reason: String): Boolean {
        if (overrunFlag.compareAndSet(false, true)) {
            overrunDetail.set(reason)
            return true
        }
        return false
    }

    /** 一个输出目的地的限额视图。**不可跨 sink 复用**。 */
    class Sink internal constructor(
        private val budget: OutputBudget,
        val label: String,
    ) {
        private val used = AtomicLong(0)
        private val stopped = AtomicBoolean(false)

        /** 本 sink 已写出的正文字节数。 */
        fun usedBytes(): Long = used.get()

        /** 本 sink 是否已停止写入。 */
        fun isStopped(): Boolean = stopped.get()

        /**
         * 写一段字节到 [out]；到限即截断并写一行有界标记，此后不再写正文。
         *
         * @return 实际写入的字节数（不含标记行）。
         */
        fun write(out: OutputStream, buffer: ByteArray, length: Int): Int {
            if (length <= 0) return 0
            if (stopped.get()) return 0

            val remaining = budget.perSinkLimit - used.get()
            if (remaining <= 0) {
                stopAndMark(out)
                return 0
            }
            val want = minOf(length.toLong(), remaining).toInt()
            val allowed = budget.tryConsume(want)
            if (allowed <= 0) {
                stopAndMark(out)
                return 0
            }

            out.write(buffer, 0, allowed)
            out.flush()
            used.addAndGet(allowed.toLong())

            if (allowed < want || used.get() >= budget.perSinkLimit) {
                stopAndMark(out)
            }
            return allowed
        }

        private fun stopAndMark(out: OutputStream) {
            if (!stopped.compareAndSet(false, true)) return
            val sinkUsed = used.get()
            val runUsed = budget.usedBytes()
            // 触发者必须写进标记：否则「单 sink 限额」与「整 run 限额」两条路径
            // 在日志上长得一模一样，测试无法区分是哪一道拦下的（守卫会假绿）。
            val triggeredBy = if (sinkUsed >= budget.perSinkLimit) "per-sink" else "run"
            val reason = "limit=$triggeredBy sink=$label 单输出限额=${budget.perSinkLimit} " +
                "实际=$sinkUsed；run 限额=${budget.runLimit} 实际=$runUsed"
            budget.markOverrun(reason)
            // 标记行本身也必须**有界**（[MARKER_MAX_BYTES]），否则超限写标记又能无限写。
            val marker = "\n[OUTPUT_LIMIT] $reason\n".toByteArray(Charsets.UTF_8)
            val bounded = if (marker.size > MARKER_MAX_BYTES) marker.copyOf(MARKER_MAX_BYTES) else marker
            runCatching {
                out.write(bounded)
                out.flush()
            }
        }
    }

    /** 仅用于装一个 String 的并发容器（避免再引 AtomicReference 的包依赖歧义）。 */
    private class AtomicReferenceHolder {
        @Volatile
        private var value: String? = null

        fun get(): String? = value

        fun set(newValue: String) {
            value = newValue
        }
    }

    companion object {
        /** 单 bot 默认上限：8 MiB。 */
        const val DEFAULT_PER_SINK_LIMIT: Long = 8L * 1024 * 1024

        /** 整 run 默认上限：128 MiB。 */
        const val DEFAULT_RUN_LIMIT: Long = 128L * 1024 * 1024

        /** 配置的绝对上界（防误配成天文数字导致事实无上限）。 */
        const val MAX_LIMIT: Long = 64L * 1024 * 1024 * 1024

        /** 超限标记行的硬上限。 */
        const val MARKER_MAX_BYTES: Int = 512

        /** 从系统属性 / 环境变量读取预算；缺省用默认值。非正数或超上界直接抛错（不静默回退）。 */
        fun fromConfig(): OutputBudget {
            val perSink = readLimit("mc.testkit.bot.log.limit.bytes") ?: DEFAULT_PER_SINK_LIMIT
            val run = readLimit("mc.testkit.bot.log.runLimit.bytes") ?: DEFAULT_RUN_LIMIT
            return OutputBudget(perSink, run)
        }

        private fun readLimit(key: String): Long? {
            val raw = System.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase())
            if (raw.isNullOrBlank()) return null
            return raw.trim().toLongOrNull()
                ?: throw IllegalArgumentException("输出上限配置 $key=$raw 不是合法整数")
        }
    }
}

/**
 * 把一个子进程的 stdout+stderr 以**固定 buffer** 增量读出并落盘，同时受 [OutputBudget] 约束。
 *
 * 为什么不能用 `readLine` / `readText` / `BufferedReader`：**一个超长且无换行的行**
 * （事故里的情形正是单个巨大 JSON 数组）会被 `readLine` 无限增长直到 OOM，**限额完全失效**。
 * 因此必须按字节块读。
 *
 * 到限后的处置：
 * 1. 停止写正文（[OutputBudget.Sink] 内部置位）
 * 2. **继续 drain**（读完丢弃），否则子进程写满管道缓冲区会永久阻塞
 * 3. drain 有**有界 deadline**（[drainDeadlineMillis]），超时则强制收尾进程树，避免线程被吊死
 *
 * @param label 人类可读名（bot key）。
 * @param input 子进程的合并输出流（`redirectErrorStream(true)` 后的 `process.inputStream`）。
 * @param logFile 落盘目标。
 * @param budget 本 run 共享的预算。
 * @param onOverrun 超限回调（写标记后调用一次；调用方据此置本 run 失败）。
 */
class BoundedProcessOutputPump(
    private val label: String,
    private val input: InputStream,
    private val logFile: File,
    private val budget: OutputBudget,
    private val drainDeadlineMillis: Long = DEFAULT_DRAIN_DEADLINE_MILLIS,
    private val onOverrun: (String) -> Unit = {},
) {
    private val sink = budget.acquire(label)
    private val stopped = AtomicBoolean(false)

    /** 已写出的正文字节数（供测试与日志）。 */
    fun writtenBytes(): Long = sink.usedBytes()

    /** 是否已超限。 */
    fun isOverrun(): Boolean = sink.isStopped() || budget.isOverrun()

    /**
     * 在**当前线程**跑泵循环（调用方通常丢进独立线程）。
     *
     * 返回时保证：正文写入已停止（无论正常结束还是超限），文件句柄已关闭。
     */
    fun run() {
        logFile.parentFile?.mkdirs()
        val buffer = ByteArray(BUFFER_SIZE)
        val drainDeadline = System.currentTimeMillis() + drainDeadlineMillis
        java.io.FileOutputStream(logFile, true).use { out ->
            try {
                while (!stopped.get()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val wrote = sink.write(out, buffer, read)
                    if (wrote == 0 && sink.isStopped()) {
                        // 已停止写正文：继续 drain 到有界 deadline，防子进程阻塞。
                        if (System.currentTimeMillis() > drainDeadline) {
                            stopped.set(true)
                            break
                        }
                    }
                }
            } catch (io: java.io.IOException) {
                // 读端断了（进程被杀 / 管道关闭）属正常收尾路径，不抛。
            } finally {
                if (sink.isStopped()) {
                    val reason = budget.overrunReason() ?: "sink=$label 触限"
                    onOverrun(reason)
                }
            }
        }
    }

    companion object {
        /** 固定读缓冲：单次最多这么多字节，超长行也不会撑爆内存。 */
        const val BUFFER_SIZE: Int = 16 * 1024

        /** 超限后继续 drain 的有界 deadline。 */
        const val DEFAULT_DRAIN_DEADLINE_MILLIS: Long = 5_000L
    }
}
