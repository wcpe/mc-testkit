package top.wcpe.mc.testkit.bot

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class OutputBudget(
    val perSinkLimit: Long = DEFAULT_PER_SINK_LIMIT,
    val runLimit: Long = DEFAULT_RUN_LIMIT,
) {
    init {
        require(perSinkLimit in 1..MAX_LIMIT && runLimit in 1..MAX_LIMIT)
        require(perSinkLimit <= runLimit)
    }

    private var used = 0L
    private var acquired = 0
    @Volatile
    private var failure: String? = null
    private val metadataReserve = minOf(8192L, runLimit / 4)
    internal val supervisor by lazy { RunOutputSupervisor(this) }
    @Synchronized
    fun usedBytes(): Long = used
    fun isOverrun(): Boolean = failure?.startsWith("OUTPUT_LIMIT") == true
    fun overrunReason(): String? = failure
    fun failureReason(): String? = failure
    fun fail(reason: String) {
        synchronized(this) {
            if (failure != null) return
            failure = reason.take(384).map { if (it.code in 32..126) it else '_' }.joinToString("")
        }
        supervisor.requestStopAll()
    }

    fun checkHealthy() {
        failure?.let { throw IllegalStateException("RUN_FAIL: $it") }
    }

    fun acquire(label: String): Sink {
        require(label.length in 1..128)
        val count = synchronized(this) { ++acquired }
        if (count > MAX_SINKS) {
            fail("OUTPUT_LIMIT sink-count")
            throw IllegalStateException("RUN_FAIL: sink-count")
        }
        return Sink(this, label)
    }

    class Sink internal constructor(private val budget: OutputBudget, val label: String) {
        private var used = 0L
        private var stopped = false
        fun usedBytes(): Long = synchronized(budget) { used }
        fun isStopped(): Boolean = synchronized(budget) { stopped }
        fun write(out: OutputStream, buffer: ByteArray, length: Int): Int {
            require(length in 0..buffer.size)
            var reason: String? = null
            val written = synchronized(budget) {
                if (length == 0 || stopped || budget.failure != null) return@synchronized 0
                val reserve = minOf(MARKER_MAX_BYTES.toLong(), budget.perSinkLimit / 8)
                val sinkRoom = (budget.perSinkLimit - reserve - used).coerceAtLeast(0)
                val runRoom = (budget.runLimit - budget.metadataReserve - reserve - budget.used).coerceAtLeast(0)
                val take = minOf(length.toLong(), sinkRoom, runRoom).toInt()
                used += take
                budget.used += take
                try {
                    out.write(buffer, 0, take)
                    if (take < length) {
                        stopped = true
                        val trigger = if (sinkRoom <= runRoom) "per-sink" else "run"
                        reason = "OUTPUT_LIMIT limit=$trigger sink=$label"
                        val marker = "\n[OUTPUT_LIMIT] ${
                            reason.orEmpty().removePrefix("OUTPUT_LIMIT ")
                        }\n".toByteArray(Charsets.UTF_8)
                        val n = minOf(
                            marker.size.toLong(), reserve, budget.perSinkLimit - used,
                            budget.runLimit - budget.metadataReserve - budget.used
                        ).coerceAtLeast(0).toInt()
                        used += n
                        budget.used += n
                        out.write(marker, 0, n)
                    }
                    out.flush()
                } catch (ex: Exception) {
                    stopped = true
                    reason = "IOFAIL sink=$label ${ex.javaClass.simpleName}"
                }
                take
            }
            reason?.let(budget::fail)
            return written
        }
    }

    companion object {
        const val DEFAULT_PER_SINK_LIMIT = 8L * 1024 * 1024
        const val DEFAULT_RUN_LIMIT = 128L * 1024 * 1024
        const val MAX_LIMIT = 1024L * 1024 * 1024
        const val MARKER_MAX_BYTES = 512
        const val MAX_SINKS = 128
        fun fromConfig(): OutputBudget = OutputBudget(
            readLimit("mc.testkit.bot.log.limit.bytes") ?: DEFAULT_PER_SINK_LIMIT,
            readLimit("mc.testkit.bot.log.runLimit.bytes") ?: DEFAULT_RUN_LIMIT,
        )

        private fun readLimit(key: String): Long? {
            val raw = System.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase()) ?: return null
            require(raw.matches(Regex("[1-9][0-9]{0,15}"))) { "Invalid positive integer: $key" }
            return raw.toLong().also { require(it <= MAX_LIMIT) { "Limit exceeds $MAX_LIMIT: $key" } }
        }
    }
}

class BoundedProcessOutputPump(
    private val label: String,
    private val input: InputStream,
    private val logFile: File,
    internal val budget: OutputBudget,
    private val drainDeadlineMillis: Long = DEFAULT_DRAIN_DEADLINE_MILLIS,
    private val onOverrun: (String) -> Unit = {},
) {
    private val sink = budget.acquire(label)
    private val finished = AtomicBoolean(false)
    fun writtenBytes(): Long = sink.usedBytes()
    fun isOverrun(): Boolean = sink.isStopped() || budget.failureReason() != null
    fun isFinished(): Boolean = finished.get()
    fun run() {
        require(drainDeadlineMillis in 1..5000)
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            logFile.parentFile?.mkdirs()
            java.io.FileOutputStream(logFile, false).use { out ->
                while (budget.failureReason() == null) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) sink.write(out, buffer, read)
                }
            }
        } catch (ex: Exception) {
            if (budget.failureReason() == null) budget.fail("IOFAIL sink=$label ${ex.javaClass.simpleName}")
        } finally {
            finished.set(true)
            budget.failureReason()?.let(onOverrun)
        }
    }

    companion object {
        const val BUFFER_SIZE = 4096
        const val DEFAULT_DRAIN_DEADLINE_MILLIS = 1000L
    }
}
