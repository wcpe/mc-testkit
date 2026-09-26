package top.wcpe.mc.testkit.provision

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 平台 PTY 分配器（把服务端放进一个真终端里启动，供 serve 的**附加控制台**使用）。
 *
 * **为什么需要它**：服务的控制台能力（Tab 补全含参数 / ↑↓ 历史 / ←→ 行编辑 / 颜色 / Ctrl+C 停服）
 * 全在服务端自己的 JLine 里，而 JLine 只在「stdin 是终端」时才启用。Gradle 的构建进程拿不到开发者的
 * 终端，所以实现一个真终端只能靠**另起一个分配器**：把服务端的 stdin/stdout 接到一个伪终端（pty）
 * 上——服务端因此认为自己在一台真终端前，附加控制台再把这个 pty 桥给开发者的终端。
 *
 * 实现上**只用系统自带的分配器**（Linux util-linux / macOS 自带的 `script`），不引第三方原生库：
 * 为一个交互增强改本插件的依赖面（离线 / CI 友好性）不划算（见 ADR-0022）。
 *
 * 本对象只负责「命令长什么样」「这平台能不能用」「怎么查/改 pty 尺寸」三件事，不持有进程。
 */
internal object PseudoTerminal {

    /** pty 默认尺寸：启动时先给一个像样的值，避免 0×0 下 JLine 折行错乱；attach 时再按真实终端改。 */
    const val DEFAULT_ROWS = 50
    const val DEFAULT_COLS = 200

    /** 分配器族：只区分 `script` 参数形态不同的两族。 */
    internal enum class Family { LINUX, MACOS, UNSUPPORTED }

    /** 探测/使用超时（分配器探测与 `stty` 都是毫秒级操作，超时即视为不可用）。 */
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    /** OS 名 → 分配器族（纯函数）。 */
    fun family(osName: String = System.getProperty("os.name").orEmpty()): Family = when {
        osName.startsWith("Linux", ignoreCase = true) -> Family.LINUX
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> Family.MACOS
        else -> Family.UNSUPPORTED
    }

    /**
     * 把 java 命令包成「在 pty 里跑」的命令（纯函数，可穷举单测）。
     *
     * - Linux：`script -qec "<stty …; exec <cmd>>" /dev/null`（util-linux 的 `-c` 经 `sh -c` 执行，故做 shell 引用）
     * - macOS：`script -q /dev/null sh -c "<…>"`（BSD `script` 直接 exec 参数，故显式给 `sh -c`）
     *
     * 两种形态都先 `stty rows/cols` 再 `exec`，让 pty 一启动就有尺寸、且不额外留一个 shell 进程。
     */
    fun wrapperCommand(
        command: List<String>,
        family: Family = family(),
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
    ): List<String> {
        require(command.isNotEmpty()) { "要包装的命令不得为空" }
        val inner = "stty rows $rows cols $cols; exec ${shellJoin(command)}"
        return when (family) {
            Family.LINUX -> listOf("script", "-qec", inner, "/dev/null")
            // BSD script：`script [-adkpqr] [-F pipe] [file [command ...]]` → 命令按参数直传，故显式 sh -c
            Family.MACOS -> listOf("script", "-q", "/dev/null", "sh", "-c", inner)
            Family.UNSUPPORTED -> error("当前平台没有可用的 PTY 分配器（附加控制台需要 POSIX 的 script）")
        }
    }

    /** 一条探测命令的结果（退出码 + 合并后的输出）。 */
    internal data class CommandResult(val exitCode: Int, val output: String)

    /**
     * 探测本机是否真的能用（真跑一次极短命令，而不是只看 `which`）。
     *
     * 用 `java -version` 作探针：它必然存在、不依赖被测项目，且能证明「分配器 + pty + 子进程」这条链
     * 是通的（退出码 0 且输出里有 version）。任何异常（如没有 `script` 可执行文件）都按不可用处理——
     * 附加控制台是增强能力，不该阻断 serve。
     *
     * [run] 可注入：单测据此覆盖「命令不存在 / 非零退出 / 输出异常」三条失败路径，不必真的缺 `script`。
     */
    fun isAvailable(
        logger: (String) -> Unit = {},
        osName: String = System.getProperty("os.name").orEmpty(),
        run: (List<String>) -> CommandResult = ::runWithTimeout,
    ): Boolean {
        val family = family(osName)
        if (family == Family.UNSUPPORTED) {
            logger("当前平台（$osName）没有 POSIX 的 script，附加控制台不可用")
            return false
        }
        val probe = wrapperCommand(listOf(javaExecutable(), "-version"), family)
        return runCatching { run(probe) }
            .map { result ->
                val ok = result.exitCode == 0 && result.output.contains("version")
                if (!ok) {
                    logger("PTY 分配器探测失败（退出码 ${result.exitCode}），附加控制台不可用：${result.output.trim().take(200)}")
                }
                ok
            }
            .getOrElse { ex ->
                logger("PTY 分配器不可用：${ex.message ?: ex.javaClass.simpleName}")
                false
            }
    }

    /** 默认探测方式：起进程、读输出、带超时等待。 */
    private fun runWithTimeout(command: List<String>): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, "探测超时")
        }
        return CommandResult(process.exitValue(), output)
    }

    /**
     * 查某进程的**控制终端**设备路径（best-effort：查不到返回 null，不影响附加控制台其余功能）。
     *
     * 用 `ps -o tty=` 而不是读 `/proc`：Linux 与 macOS 都支持，且 macOS 没有 /proc。
     */
    fun terminalDeviceOf(pid: Long): String? {
        val tty = runCatching {
            val process = ProcessBuilder("ps", "-o", "tty=", "-p", pid.toString()).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.trim().also { process.waitFor() }
        }.getOrNull().orEmpty()
        if (tty.isEmpty() || tty == "?" || tty == "-") return null
        return if (tty.startsWith("/")) tty else "/dev/$tty"
    }

    /**
     * 把 pty 尺寸设成给定值（best-effort）：attach 时按开发者终端的真实大小同步一次，避免折行错乱。
     *
     * 设备节点是**服务端**的控制终端，故用 `stty` 的 `-F`（util-linux）/ `-f`（BSD）指定它。
     */
    fun resize(device: String, rows: Int, cols: Int, family: Family = family()): Boolean {
        if (rows <= 0 || cols <= 0) return false
        val flag = if (family == Family.MACOS) "-f" else "-F"
        return runCatching {
            val process = ProcessBuilder("stty", flag, device, "rows", rows.toString(), "cols", cols.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isBlank()
        }.getOrDefault(false)
    }

    /** 当前 JVM 的 java 可执行路径（与 [javaExecutable] 同源）。 */
    private fun javaExecutable(): String =
        File(File(System.getProperty("java.home"), "bin"), if (family() == Family.MACOS) "java" else "java").absolutePath

    /** POSIX shell 引用：单引号包裹，内部单引号按 `'\''` 转义。 */
    private fun shellJoin(args: List<String>): String = args.joinToString(" ") { arg ->
        "'" + arg.replace("'", "'\\''") + "'"
    }
}
