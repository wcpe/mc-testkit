package top.wcpe.mc.testkit.bot

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本次构建启动的 bot 与钩子进程的监管表：只管经 [register] 登记的进程，以及观察到的它们的子孙进程。
 *
 * 负责到期判 TIMEOUT、按需停止（先温和，[ESCALATE_AFTER_MS] 后强杀）和构建结束时的收尾确认（[survivors]）。
 * 子孙进程每 [SCAN_INTERVAL_MS] 扫一遍本 JVM 的进程树，沿父进程链归到各自的登记进程下：逐个登记进程调
 * [ProcessHandle.descendants] 每次都要全量扫一遍进程表，几百个 bot 同时在跑时监控线程会吃满一个核。
 *
 * 进程身份交给 [ProcessHandle]：它记着启动时刻，isAlive 与 destroy 都先核对，pid 被复用时不会误判、误杀。
 */
internal class RunOutputSupervisor(private val budget: OutputBudget) : AutoCloseable {
    private class Child(val handle: BotProcessHandle, val deadline: Long?) {
        val process: Process = handle.process
        val log: File = handle.logFile.canonicalFile
        val tree = LinkedHashSet<ProcessHandle>()
        var stopping = false
        var stoppedAt = 0L
        var escalated = false

        /** 温和收尾的升级阈值（毫秒）；默认 [ESCALATE_AFTER_MS]，可按进程声明放宽（如 driver 的 killTimeoutSeconds）。 */
        var escalateAfterMs: Long = ESCALATE_AFTER_MS
        var exitedAt = 0L
        var pidFile: File? = null
        var retired = false
    }

    private val children = mutableListOf<Child>()

    /** 进程到父进程 pid 的缓存。只缓存仍在本 JVM 进程树里的进程，它们的父进程不会变，每个只查一次。 */
    private val parents = HashMap<ProcessHandle, Long>()
    private var monitor: Thread? = null

    @Volatile
    private var closed = false

    @Volatile
    private var survivors: List<ProcessIdentity>? = null
    private val shutdown = Thread({ closeChildren(false) }, "mc-testkit-output-shutdown")

    /**
     * [timeoutMs] 是兜底期限，必须长于编排器自己等这个进程的时间；为 null 时不设期限，由所属会话（serve）和 [close] 收尾。
     * [cleanup] 为 true 表示收尾用的进程（场景后钩子）：本次构建已判失败时照样登记，收尾不因前面的失败而做不成。
     */
    @Synchronized
    fun register(handle: BotProcessHandle, timeoutMs: Long?, cleanup: Boolean = false) {
        require(timeoutMs == null || timeoutMs in 1..MAX_LIFETIME_MS) { "进程寿命上限须在 1 到 $MAX_LIFETIME_MS 毫秒之间，实际为 $timeoutMs" }
        if (!cleanup) budget.checkHealthy()
        check(!closed) { "本次构建的进程监管已经收尾，不能再登记新进程" }
        val child = Child(handle, timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) })
        check(children.none { it.log == child.log && (!it.handle.pump.isFinished() || it.process.isAlive) }) {
            "日志 ${child.log.name} 还有进程在写，不能交给新进程"
        }
        children += child
        if (monitor == null) {
            Runtime.getRuntime().addShutdownHook(shutdown)
            monitor = Thread({ watch() }, "mc-testkit-output-owner").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun requestStopAll() {
        if (children.none { !it.retired }) return
        refresh(scan())
        children.forEach { if (!it.retired) requestStop(it) }
    }

    private fun requestStop(c: Child) {
        if (!c.stopping && !c.process.isAlive && c.process.exitValue() != 0) {
            budget.fail("CHILD_EXIT code=${c.process.exitValue()}")
        }
        if (!c.stopping) {
            c.stopping = true
            c.stoppedAt = System.nanoTime()
        }
        kill(c, force = c.escalated)
    }

    private fun kill(c: Child, force: Boolean) {
        c.tree.forEach { if (force) it.destroyForcibly() else it.destroy() }
        if (force) c.process.destroyForcibly() else c.process.destroy()
    }

    @Synchronized
    fun stop(process: Process) {
        val c = child(process)
        refresh(scan())
        requestStop(c)
    }

    /**
     * 等 [process] 及其已观察到的子孙进程都结束，超过 [ESCALATE_AFTER_MS] 就强杀。发出停止不等于已经停下：Linux 上
     * destroy() 只是 SIGTERM，被杀的进程在被回收之前对 ProcessHandle 仍显示存活。
     */
    fun awaitStopped(process: Process, timeoutMs: Long): Boolean {
        val tree = synchronized(this) { child(process).tree.toList() }
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        awaitGone(process, tree, minOf(deadline, start + TimeUnit.MILLISECONDS.toNanos(child(process).escalateAfterMs)))
        tree.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        awaitGone(process, tree, deadline)
        return !process.isAlive && tree.none { it.isAlive }
    }

    private fun awaitGone(process: Process, tree: List<ProcessHandle>, deadline: Long) {
        val rootWaitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (rootWaitMs > 0) process.waitFor(rootWaitMs, TimeUnit.MILLISECONDS)
        while (deadline - System.nanoTime() > 0 && tree.any { it.isAlive }) Thread.sleep(10)
    }

    private fun child(process: Process): Child =
        children.firstOrNull { it.process === process } ?: throw IllegalStateException("进程 ${process.pid()} 不是本次构建登记的")

    @Synchronized
    fun bindPidFile(process: Process, file: File) {
        child(process).pidFile = file.canonicalFile
    }

    @Synchronized
    fun stopPidFile(file: File): Boolean {
        val pidFile = file.canonicalFile
        val c = children.lastOrNull { it.pidFile == pidFile } ?: return false
        refresh(scan())
        requestStop(c)
        return true
    }

    @Synchronized
    fun stopLog(logFile: File): Boolean {
        val log = logFile.canonicalFile
        val c = children.lastOrNull { it.log == log } ?: return false
        refresh(scan())
        requestStop(c)
        return true
    }

    /**
     * 按日志文件找受监管进程、先把该进程的温和收尾窗口放宽到 [escalateAfterMs] 再请求停止，
     * 返回该进程供调用方 [awaitStopped] 等净——driver 的 `killTimeoutSeconds` 语义走这条路
     * （默认阈值是快照级场景优化，不适合还在落证据的进程）。未登记返回 null。
     */
    @Synchronized
    fun stopLogWithGrace(logFile: File, escalateAfterMs: Long): Process? {
        val log = logFile.canonicalFile
        val c = children.lastOrNull { it.log == log } ?: return null
        c.escalateAfterMs = escalateAfterMs
        refresh(scan())
        requestStop(c)
        return c.process
    }

    /** [pid] 是否是本次构建登记且仍在运行的进程。它活着，这个 pid 就不会被别的进程占用。 */
    @Synchronized
    fun owns(pid: Long): Boolean = children.any { !it.retired && it.process.pid() == pid && it.process.isAlive }

    /** 本 JVM 当前全部子孙进程；一次调用只扫一遍进程表。 */
    private fun scan(): List<ProcessHandle> = ProcessHandle.current().descendants().use { it.toList() }

    /** 把 [snapshot] 里的进程归到仍存活的登记进程下。 */
    private fun refresh(snapshot: List<ProcessHandle>) {
        val roots = HashMap<Long, Child>()
        children.forEach { if (!it.retired && it.process.isAlive) roots[it.process.pid()] = it }
        if (roots.isEmpty() || snapshot.isEmpty()) return
        val byPid = HashMap<Long, ProcessHandle>(snapshot.size * 2)
        snapshot.forEach { byPid[it.pid()] = it }
        parents.keys.retainAll(snapshot.toHashSet())
        val owners = HashMap<Long, Child?>()
        var overflow = false
        snapshot.forEach { ph ->
            if (ph.pid() in roots) return@forEach
            val c = ownerOf(ph, roots, byPid, owners) ?: return@forEach
            if (ph in c.tree) return@forEach
            if (c.tree.size < MAX_DESCENDANTS) c.tree += ph else overflow = true
        }
        if (overflow) budget.fail("RUN_FAIL descendant-count")
    }

    private fun ownerOf(
        start: ProcessHandle,
        roots: Map<Long, Child>,
        byPid: Map<Long, ProcessHandle>,
        owners: MutableMap<Long, Child?>,
    ): Child? {
        val path = ArrayList<Long>()
        var current: ProcessHandle? = start
        var owner: Child? = null
        while (path.size < MAX_DEPTH) {
            val node = current ?: break
            val pid = node.pid()
            if (pid in owners) {
                owner = owners[pid]
                break
            }
            path += pid
            val ppid = parents[node] ?: node.parent().map { it.pid() }.orElse(-1L).also { if (it >= 0) parents[node] = it }
            owner = roots[ppid]
            if (owner != null) break
            current = byPid[ppid]
        }
        path.forEach { owners[it] = owner }
        return owner
    }

    private fun watch() {
        try {
            var nextScan = System.nanoTime()
            while (!closed) {
                val now = System.nanoTime()
                if (now - nextScan >= 0) {
                    nextScan = now + TimeUnit.MILLISECONDS.toNanos(SCAN_INTERVAL_MS)
                    if (synchronized(this) { children.any { !it.retired && it.process.isAlive } }) {
                        val snapshot = scan()
                        synchronized(this) { refresh(snapshot) }
                    }
                }
                synchronized(this) { children.forEach { check(it, now) } }
                Thread.sleep(TICK_MS)
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            budget.fail("RUN_FAIL supervisor ${ex.javaClass.simpleName}")
        }
    }

    private fun check(c: Child, now: Long) {
        if (c.retired) return
        if (!c.stopping && !c.process.isAlive) {
            val code = c.process.exitValue()
            if (code != 0) {
                budget.fail("CHILD_EXIT code=$code")
            } else if (c.handle.pump.isFinished() && c.tree.none { it.isAlive } && budget.failureReason() == null) {
                // 句柄、子孙进程与输出用量都留作证据，只让它退出期限监控。
                c.retired = true
                return
            }
            if (c.exitedAt == 0L) c.exitedAt = now
            // 根进程正常退出后，继承了管道的子孙进程仍可能在写。
            if (now - c.exitedAt > TimeUnit.MILLISECONDS.toNanos(INHERITED_GRACE_MS) &&
                (!c.handle.pump.isFinished() || c.tree.any { it.isAlive })
            ) {
                budget.fail("RUN_FAIL inherited-pipe-or-descendant")
            }
        }
        if (!c.stopping && c.deadline != null && now - c.deadline >= 0) budget.fail("TIMEOUT log=${c.log.name.take(100)}")
        if (c.stopping && !c.escalated && now - c.stoppedAt >= TimeUnit.MILLISECONDS.toNanos(c.escalateAfterMs)) {
            c.escalated = true
            kill(c, force = true)
        }
    }

    private fun closeChildren(markDetached: Boolean) {
        val snapshot = synchronized(this) {
            if (closed) return
            if (markDetached && children.any { !it.stopping && it.process.isAlive }) budget.fail("RUN_FAIL detached-child-at-build-end")
            requestStopAll()
            closed = true
            children.filter { !it.retired }.map { it to it.tree.toList() }
        }
        // 整个构建共用一个期限，不是每个进程各等一轮。
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DEADLINE_MS)
        snapshot.forEach { (c, tree) ->
            tree.forEach { it.destroyForcibly() }
            c.process.destroyForcibly()
        }
        val left = mutableListOf<ProcessIdentity>()
        var known = true
        snapshot.forEach { (c, tree) ->
            awaitGone(c.process, tree, deadline)
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            c.handle.pumpThread?.join(remaining)
            val alive = tree.filter { it.isAlive } + listOfNotNull(c.process.toHandle().takeIf { c.process.isAlive })
            val pumping = c.handle.pumpThread?.isAlive == true
            if (pumping || alive.isNotEmpty()) budget.fail("RUN_FAIL cleanup-deadline")
            if (pumping) known = false
            alive.forEach { ph ->
                val identity = ProcessIdentity.of(ph)
                if (identity == null) known = false else left += identity
            }
        }
        survivors = if (known) left else null
    }

    /**
     * 收尾结果：期限到时仍存活的受监管进程，正常为空。还没收尾、有泵没排空（观察树之外可能还有进程握着
     * 管道），或有存活进程取不到启动时刻时返回 null，表示无法确认。
     */
    fun survivors(): List<ProcessIdentity>? = survivors

    override fun close() {
        closeChildren(true)
        monitor?.interrupt()
        if (Thread.currentThread() !== shutdown) runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }

    companion object {
        /** [register] 期限的上限，也让 nanoTime() 加期限远离溢出。 */
        const val MAX_LIFETIME_MS = 7L * 24 * 3600 * 1000
        private const val TICK_MS = 20L
        private const val SCAN_INTERVAL_MS = 100L
        private const val ESCALATE_AFTER_MS = 200L
        private const val INHERITED_GRACE_MS = 300L
        private const val CLOSE_DEADLINE_MS = 2000L

        /** 单个登记进程可观察的子孙进程数上限，超过判失败：防止 fork 风暴把监控表撑爆。 */
        private const val MAX_DESCENDANTS = 1024
        private const val MAX_DEPTH = 64
    }
}
