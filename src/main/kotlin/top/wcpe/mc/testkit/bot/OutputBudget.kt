package top.wcpe.mc.testkit.bot

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次构建里 bot 与钩子进程输出的字节预算（ADR-0029）。
 *
 * 两道上限语义不同：
 * - 单个日志到 [perSinkLimit] 只截断这一个日志：写一行 `[OUTPUT_LIMIT]` 标记，之后的输出照常读出来丢掉，
 *   进程不会卡在写满的管道上；只记一次截断（[truncatedSinks]），不判失败、不停任何进程。
 * - 整次构建到 [runLimit] 判失败并停掉全部受监管进程，这是防止写满磁盘的最后一道闸。
 *
 * 计数全用原子操作，写盘不持有任何共享锁：某个日志写盘卡住，不会拖住 [fail] 和停进程。
 */
class OutputBudget(
    val perSinkLimit: Long = DEFAULT_PER_SINK_LIMIT,
    val runLimit: Long = DEFAULT_RUN_LIMIT,
) {
    init {
        require(perSinkLimit in 1..MAX_LIMIT && runLimit in 1..MAX_LIMIT) { "输出上限须在 1 到 $MAX_LIMIT 字节之间" }
        require(perSinkLimit <= runLimit) { "单个日志文件上限（$perSinkLimit）不得大于整个构建上限（$runLimit）" }
    }

    private val used = AtomicLong()
    private val failure = AtomicReference<String?>()
    private val truncated = AtomicInteger()

    /** 正文写不进这部分额度，它留给各日志的 `[OUTPUT_LIMIT]` 标记行。 */
    private val markerPool = minOf(MARKER_POOL_BYTES, runLimit / 4)
    internal val supervisor by lazy { RunOutputSupervisor(this) }

    fun usedBytes(): Long = used.get()

    /** 第一个失败原因；null 表示本次构建的输出与受监管进程都正常。 */
    fun failureReason(): String? = failure.get()

    /** 超过单个日志上限、已被截断的日志数。 */
    fun truncatedSinks(): Int = truncated.get()

    /** 记下失败（只保留第一个原因），并停掉全部受监管进程。 */
    fun fail(reason: String) {
        val text = reason.take(MAX_REASON_CHARS).map { if (it.code in 32..126) it else '_' }.joinToString("")
        if (failure.compareAndSet(null, text)) supervisor.requestStopAll()
    }

    fun checkHealthy() {
        failure.get()?.let { throw IllegalStateException("RUN_FAIL: $it") }
    }

    fun acquire(label: String): Sink {
        require(label.length in 1..MAX_LABEL_CHARS) { "日志标签长度须在 1 到 $MAX_LABEL_CHARS 之间，实际为 ${label.length}" }
        return Sink(this, label)
    }

    /** 从整次构建的额度里拿至多 [want] 字节，并始终留出 [keep] 字节；返回实际拿到的字节数。 */
    private fun reserve(want: Long, keep: Long): Long {
        while (true) {
            val current = used.get()
            val take = minOf(want, runLimit - keep - current).coerceAtLeast(0)
            if (take == 0L || used.compareAndSet(current, current + take)) return take
        }
    }

    /** 一个日志文件的写入口，只能由一个线程（它的泵）写。 */
    class Sink internal constructor(private val budget: OutputBudget, val label: String) {
        @Volatile
        private var used = 0L

        @Volatile
        private var truncated = false

        @Volatile
        private var closed = false

        fun usedBytes(): Long = used
        fun isTruncated(): Boolean = truncated

        /**
         * 把 [buffer] 的前 [length] 字节写进 [out]，超出上限的部分丢掉；返回写进去的正文字节数。
         * 已截断或写盘失败后返回 0，调用方照常继续读。整次构建判了其他失败（超期、非零退出等）后仍照常写：
         * 被停掉的进程最后的输出是排障依据，磁盘由两道字节上限兜底。
         */
        fun write(out: OutputStream, buffer: ByteArray, length: Int): Int {
            require(length in 0..buffer.size) { "写入长度 $length 超出缓冲区大小 ${buffer.size}" }
            if (length == 0 || truncated || closed) return 0
            val markerReserve = minOf(MARKER_MAX_BYTES.toLong(), budget.perSinkLimit / 8)
            val want = minOf(length.toLong(), budget.perSinkLimit - markerReserve - used).coerceAtLeast(0)
            val take = budget.reserve(want, budget.markerPool)
            used += take
            try {
                if (take > 0) out.write(buffer, 0, take.toInt())
                if (take < length) {
                    val runLimited = take < want
                    val reason = "OUTPUT_LIMIT limit=${if (runLimited) "run" else "per-sink"} sink=$label"
                    writeMarker(out, reason, markerReserve)
                    if (runLimited) {
                        closed = true
                        budget.fail(reason)
                    } else {
                        truncated = true
                        budget.truncated.incrementAndGet()
                    }
                }
                out.flush()
            } catch (ex: IOException) {
                closed = true
                budget.fail("IOFAIL sink=$label ${ex.javaClass.simpleName}")
            }
            return take.toInt()
        }

        private fun writeMarker(out: OutputStream, reason: String, markerReserve: Long) {
            val marker = "\n[OUTPUT_LIMIT] ${reason.removePrefix("OUTPUT_LIMIT ")}\n".toByteArray(Charsets.UTF_8)
            val n = budget.reserve(minOf(marker.size.toLong(), markerReserve, budget.perSinkLimit - used), 0)
            used += n
            if (n > 0) out.write(marker, 0, n.toInt())
        }
    }

    companion object {
        const val DEFAULT_PER_SINK_LIMIT = 64L * 1024 * 1024
        const val DEFAULT_RUN_LIMIT = 1024L * 1024 * 1024
        const val MAX_LIMIT = 16L * 1024 * 1024 * 1024
        const val MARKER_MAX_BYTES = 512
        private const val MARKER_POOL_BYTES = 8192L
        private const val MAX_REASON_CHARS = 384
        private const val MAX_LABEL_CHARS = 128
        fun fromConfig(): OutputBudget = fromLookup(System::getProperty, System::getenv)

        /** 系统属性优先，其次环境变量（前缀按 ADR-0006 用 `MC_TESTKIT_E2E_`）；非法值直接失败，不退回默认。 */
        internal fun fromLookup(property: (String) -> String?, env: (String) -> String?): OutputBudget {
            fun limit(key: String, envKey: String): Long? {
                val raw = property(key) ?: env(envKey) ?: return null
                require(raw.matches(Regex("[1-9][0-9]{0,15}"))) { "$key / $envKey 须为十进制正整数（字节），实际为「$raw」" }
                return raw.toLong().also { require(it <= MAX_LIMIT) { "$key / $envKey 不得超过 $MAX_LIMIT 字节，实际为 $it" } }
            }
            return OutputBudget(
                limit("mc.testkit.bot.log.limit.bytes", "MC_TESTKIT_E2E_BOT_LOG_LIMIT_BYTES") ?: DEFAULT_PER_SINK_LIMIT,
                limit("mc.testkit.bot.log.runLimit.bytes", "MC_TESTKIT_E2E_BOT_LOG_RUN_LIMIT_BYTES") ?: DEFAULT_RUN_LIMIT,
            )
        }
    }
}

/**
 * 把子进程的合并输出泵进 [logFile]，按 [budget] 限额落盘。
 *
 * 一直读到管道 EOF：截断或写盘失败后照常读，只是不再写，子进程不会卡在写满的管道上。
 * 管道被遗留的子孙进程握着时泵不会结束，收尾据此判定还有进程可能在写。
 * 第一次截断时调一次 [onTruncated]。
 */
class BoundedProcessOutputPump(
    private val label: String,
    private val input: InputStream,
    private val logFile: File,
    internal val budget: OutputBudget,
    private val onTruncated: (String) -> Unit = {},
) {
    private val sink = budget.acquire(label)

    @Volatile
    private var finished = false
    fun writtenBytes(): Long = sink.usedBytes()
    fun isTruncated(): Boolean = sink.isTruncated()
    fun isFinished(): Boolean = finished
    fun run() {
        val buffer = ByteArray(BUFFER_SIZE)
        var out: OutputStream? = null
        try {
            out = open()
            var reported = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0 || out == null) continue
                sink.write(out, buffer, read)
                if (!reported && sink.isTruncated()) {
                    reported = true
                    onTruncated("${logFile.name} 超过单个日志上限 ${budget.perSinkLimit} 字节，已截断，后续输出不再落盘")
                }
            }
        } catch (ex: IOException) {
            budget.fail("IOFAIL sink=$label ${ex.javaClass.simpleName}")
        } finally {
            runCatching { out?.close() }
            finished = true
        }
    }

    private fun open(): OutputStream? = try {
        logFile.parentFile?.mkdirs()
        FileOutputStream(logFile, false)
    } catch (ex: IOException) {
        budget.fail("IOFAIL sink=$label ${ex.javaClass.simpleName}")
        null
    }

    companion object {
        const val BUFFER_SIZE = 8192
    }
}
