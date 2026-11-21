package top.wcpe.mc.testkit.bot

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Bounded registry: only handles returned by our starts, plus their observed descendants. */
internal class RunOutputSupervisor(private val budget: OutputBudget) : AutoCloseable {
    private data class Identity(val handle: ProcessHandle, val start: Instant) {
        fun alive(): Boolean = handle.isAlive && handle.info().startInstant().orElse(null) == start
        fun kill(force: Boolean) {
            if (alive()) {
                if (force) handle.destroyForcibly() else handle.destroy()
            }
        }
    }

    private data class Child(
        val handle: BotProcessHandle,
        val root: Identity,
        val deadline: Long,
        val tree: MutableMap<Long, Identity> = linkedMapOf(),
        var stopping: Boolean = false,
        var stoppedAt: Long = 0,
        var exitedAt: Long = 0,
        var pidFile: File? = null,
        var retired: Boolean = false,
    )

    private val children = mutableListOf<Child>()
    private var monitor: Thread? = null

    @Volatile
    private var closed = false
    private val shutdown = Thread({ closeChildren(false) }, "mc-testkit-output-shutdown")

    @Synchronized
    fun register(handle: BotProcessHandle, timeoutMs: Long = 600_000) {
        require(timeoutMs in 1..3_600_000)
        budget.checkHealthy()
        check(!closed)
        check(children.size < OutputBudget.MAX_SINKS)
        check(
            children.none {
                it.handle.logFile.canonicalFile == handle.logFile.canonicalFile &&
                    (!it.handle.pump.isFinished() || it.root.alive())
            },
        ) { "Output file already has an active owner" }
        val ph = handle.process.toHandle()
        val start = ph.info().startInstant().orElseThrow { IllegalStateException("Missing process start time") }
        children += Child(handle, Identity(ph, start), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        if (monitor == null) {
            Runtime.getRuntime().addShutdownHook(shutdown)
            monitor = Thread({ watch() }, "mc-testkit-output-owner").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    private fun observe(c: Child) {
        if (c.root.alive()) {
            c.root.handle.descendants().use { stream ->
                stream.limit(129).forEach { ph ->
                    if (c.tree.size >= 128 && ph.pid() !in c.tree) {
                        budget.fail("RUN_FAIL descendant-count")
                    } else {
                        ph.info().startInstant().orElse(null)?.let { c.tree.putIfAbsent(ph.pid(), Identity(ph, it)) }
                    }
                }
            }
        }
    }

    @Synchronized
    fun requestStopAll() {
        children.forEach { requestStop(it) }
    }

    private fun requestStop(c: Child) {
        observe(c)
        if (!c.stopping && !c.root.alive() && c.handle.process.exitValue() != 0) {
            budget.fail("CHILD_EXIT code=${c.handle.process.exitValue()}")
        }
        if (!c.stopping) {
            c.stopping = true
            c.stoppedAt = System.nanoTime()
        }
        c.tree.values.forEach { it.kill(false) }
        c.root.kill(false)
    }

    @Synchronized
    fun stop(process: Process) {
        children.firstOrNull { it.handle.process === process }?.let { requestStop(it) }
            ?: throw IllegalStateException("Process is not owned by this run")
    }

    /**
     * Waits until [process] and its observed descendants are gone, forcing them after [ESCALATE_AFTER_MS]. A sent stop
     * is not a finished one: on Linux destroy() is only SIGTERM, and a killed process stays alive to ProcessHandle
     * until it is reaped.
     */
    fun awaitStopped(process: Process, timeoutMs: Long): Boolean {
        val (root, tree) = synchronized(this) {
            val c = children.firstOrNull { it.handle.process === process }
                ?: throw IllegalStateException("Process is not owned by this run")
            c.root to c.tree.values.toList()
        }
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        awaitGone(process, root, tree, minOf(deadline, start + TimeUnit.MILLISECONDS.toNanos(ESCALATE_AFTER_MS)))
        tree.forEach { it.kill(true) }
        root.kill(true)
        awaitGone(process, root, tree, deadline)
        return !root.alive() && tree.none { it.alive() }
    }

    private fun awaitGone(process: Process, root: Identity, tree: List<Identity>, deadline: Long) {
        val rootWaitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (rootWaitMs > 0) process.waitFor(rootWaitMs, TimeUnit.MILLISECONDS)
        while (System.nanoTime() < deadline && (root.alive() || tree.any { it.alive() })) Thread.sleep(10)
    }

    @Synchronized
    fun bindPidFile(process: Process, file: File) {
        val c = children.firstOrNull { it.handle.process === process }
            ?: throw IllegalStateException("Unowned process")
        c.pidFile = file.canonicalFile
    }

    @Synchronized
    fun stopPidFile(file: File): Boolean {
        val c = children.firstOrNull { it.pidFile == file.canonicalFile } ?: return false
        requestStop(c)
        return true
    }

    @Synchronized
    fun stopLog(logFile: File): Boolean {
        val c = children.firstOrNull { it.handle.logFile.canonicalFile == logFile.canonicalFile } ?: return false
        requestStop(c)
        return true
    }

    private fun watch() {
        try {
            while (!closed) {
                synchronized(this) {
                    val now = System.nanoTime()
                    children.forEach { c ->
                        if (c.retired) return@forEach
                        observe(c)
                        if (!c.handle.process.isAlive && !c.stopping) {
                            if (c.handle.process.exitValue() != 0) {
                                budget.fail("CHILD_EXIT code=${c.handle.process.exitValue()}")
                            } else if (c.handle.pump.isFinished() && c.tree.values.none { it.alive() } &&
                                budget.failureReason() == null
                            ) {
                                // Keep the handle, descendants and output accounting as evidence; retire only its deadline.
                                c.retired = true
                                return@forEach
                            }
                            if (c.exitedAt == 0L) c.exitedAt = now
                            // An inherited pipe or orphan descendant may outlive an otherwise successful root.
                            if (now - c.exitedAt > TimeUnit.MILLISECONDS.toNanos(300) &&
                                (!c.handle.pump.isFinished() || c.tree.values.any { it.alive() })
                            ) {
                                budget.fail("RUN_FAIL inherited-pipe-or-descendant")
                            }
                        }
                        if (!c.stopping && now >= c.deadline) budget.fail("TIMEOUT log=${c.handle.logFile.name.take(100)}")
                        if (c.stopping && now - c.stoppedAt >= TimeUnit.MILLISECONDS.toNanos(ESCALATE_AFTER_MS)) {
                            c.tree.values.forEach { it.kill(true) }
                            c.root.kill(true)
                        }
                    }
                }
                Thread.sleep(20)
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            budget.fail("RUN_FAIL supervisor ${ex.javaClass.simpleName}")
            requestStopAll()
        }
    }

    private fun closeChildren(markDetached: Boolean) {
        val snapshot = synchronized(this) {
            if (closed) return
            if (markDetached && children.any { !it.stopping && it.root.alive() }) budget.fail("RUN_FAIL detached-child-at-build-end")
            requestStopAll()
            closed = true
            children.map { it to it.tree.values.toList() }
        }
        // One deadline for the whole run, not one timeout per child.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        snapshot.forEach { (c, tree) ->
            tree.forEach { it.kill(true) }
            c.root.kill(true)
        }
        snapshot.forEach { (c, tree) ->
            awaitGone(c.handle.process, c.root, tree, deadline)
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            c.handle.pumpThread?.join(remaining)
            if (c.handle.pumpThread?.isAlive == true || c.root.alive() || tree.any { it.alive() }) {
                budget.fail("RUN_FAIL cleanup-deadline")
            }
        }
    }

    override fun close() {
        closeChildren(true)
        monitor?.interrupt()
        if (Thread.currentThread() !== shutdown) runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }

    companion object {
        private const val ESCALATE_AFTER_MS = 200L
    }
}
