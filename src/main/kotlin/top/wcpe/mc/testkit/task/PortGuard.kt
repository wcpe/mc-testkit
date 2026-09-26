package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** 一个待检端口及其用途标签（标签进报错文案，用于指明是哪个节点撞了端口）。 */
internal data class PortTarget(val port: Int, val label: String)

/**
 * 端口可用性探测器（可注入，便于单测穷举「空闲 / 被占用」两分支而不必真去占端口）。
 *
 * 实现须 [java.io.Serializable]：与 `ArtifactUrlResolver` 同款约束，便于被任务动作捕获。
 */
internal fun interface PortAvailabilityProbe : java.io.Serializable {
    /** @return 该端口当前是否可用（true = 空闲，本次起服不会撞端口）。 */
    fun isAvailable(port: Int): Boolean

    companion object {
        /** 缺省实现：见 [DefaultPortAvailabilityProbe]。 */
        val DEFAULT: PortAvailabilityProbe = DefaultPortAvailabilityProbe
    }
}

/**
 * 缺省端口探测器：**可绑定 且 不可连接**才算空闲。
 *
 * 两个信号缺一不可（跨平台差异使单一信号都不成立）：
 * - **可绑定**：用 `SO_REUSEADDR` 试绑通配地址（等价服务端自己绑 `*:端口`）后立即释放。带该选项是为了不被
 *   上一轮连接残留的 `TIME_WAIT` 误报成占用——服务端自身也是这么绑的，故这问的正是「新服务端能否 bind 成功」。
 * - **不可连接**：排除 Windows 下 `SO_REUSEADDR` 的语义差异——它允许**两个活着的** socket 绑同一端口
 *   （与 Unix 相反），只靠试绑会在 Windows 上把「已被占用的端口」误判为空闲；此时连接是能通的，故以此兜住。
 *
 * 用具名 object 而非 lambda（Kotlin SAM 转换产物不可序列化）。
 */
private object DefaultPortAvailabilityProbe : PortAvailabilityProbe {

    /** 连接探测超时（毫秒）：只判「有没有人接」，不等待真实握手。 */
    private const val CONNECT_TIMEOUT_MS = 300

    override fun isAvailable(port: Int): Boolean = canBind(port) && !isConnectable(port)

    /** 试绑通配地址后立即释放；失败（端口被真实监听 / 权限不足）即不可绑定。 */
    private fun canBind(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
        }
        true
    } catch (_: Exception) {
        false
    }

    /** 该端口是否已有进程在接受连接（有则在起服前就该拦住，而不是等服务端 bind 失败）。 */
    private fun isConnectable(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 起服前端口占用预检（进程生命周期与收尾 高风险区）。
 *
 * **为什么需要**：`awaitPortOpen` 以「端口可连」当就绪信号，而上一轮异常中断残留的进程同样让端口可连——
 * 于是新进程被**误判为已就绪**（日志先打「✅ 已就绪」，随后新服务端真正 bind 时才以
 * `bind(..) failed: 地址已在使用` 崩溃）。用户看到的是「起服成功又立刻失败」，归因成本极高。
 * 预检把这件事提前到起服**之前**，并给出中文原因与按端口排查的命令。
 *
 * 纯判定 + 抛错，**不代为清理**：占用端口的可能是用户手工起的服务端，自动杀进程属于越权；
 * 收尾交给配套的 `stop<Key>Serve` 等停任务（按进程台账兜底，见 [ProcessLedger]）。
 *
 * @param targets 待检端口与用途标签（标签进报错，指明是哪个节点撞了端口）。
 * @param stopTaskName 建议用户执行的收尾任务名（进报错文案）；null 时不提。
 * @param probe 端口探测器（单测注入用）。
 * @param osName 用于选择排查命令示例（单测注入用）。
 * @throws GradleException 任一端口不可用时（文案为中文，含原因 + 排查命令 + 收尾建议）。
 */
internal fun requirePortsAvailable(
    targets: List<PortTarget>,
    stopTaskName: String? = null,
    probe: PortAvailabilityProbe = PortAvailabilityProbe.DEFAULT,
    osName: String = System.getProperty("os.name").orEmpty(),
) {
    if (targets.isEmpty()) {
        return
    }
    val occupied = targets.filter { !probe.isAvailable(it.port) }
    if (occupied.isNotEmpty()) {
        throw GradleException(portOccupiedMessage(occupied, stopTaskName, osName))
    }
}

/**
 * 组装端口占用的中文报错文案（纯函数，便于单测穷举文案要素）。
 *
 * @param occupied 不可用的端口（非空）。
 * @param stopTaskName 建议执行的收尾任务名；null 时不提。
 * @param osName 操作系统名（`System.getProperty("os.name")`），用于选排查命令示例。
 */
internal fun portOccupiedMessage(occupied: List<PortTarget>, stopTaskName: String?, osName: String): String {
    val header = occupied.joinToString("；") { "端口 ${it.port} 已被占用（${it.label}）" }
    val commands = if (osName.startsWith("Windows", ignoreCase = true)) {
        occupied.joinToString("\n") { "  netstat -ano | findstr :${it.port}" }
    } else {
        occupied.joinToString("\n") { "  ss -ltnp | grep :${it.port}    # 或：lsof -i :${it.port}" }
    }
    val stopHint = stopTaskName
        ?.let { "收尾残留进程：./gradlew $it（按进程台账清理本次与历史轮次的残留）\n" }
        .orEmpty()
    return buildString {
        appendLine("端口预检失败：$header。")
        appendLine("多半是上一轮异常中断残留的服务端 / 代理进程未收尾。若直接起服，新进程会在启动末尾以")
        appendLine("「bind(..) failed: 地址已在使用」崩溃，且就绪门可能先误报「端口已就绪」，白等一轮。")
        appendLine("排查占用进程：")
        appendLine(commands)
        append(stopHint.trimEnd())
    }
}
