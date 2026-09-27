package top.wcpe.mc.testkit.provision

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * pty4j 伪终端底座，负责启动、探测和调整伪终端尺寸。
 *
 * pty4j 在 Windows 上优先使用 ConPTY，ConPTY 不可用时由库自动回退到 winpty；
 * 在 Unix 系统上使用系统伪终端实现，因此调用方不需要感知平台差异。
 */
internal object PseudoTerminal {

    /** pty 默认尺寸：启动时先给一个稳定值，attach 时再按当前终端真实尺寸调整。 */
    const val DEFAULT_ROWS = 50
    const val DEFAULT_COLS = 200

    /** 探测/启动超时（秒）。 */
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    /** pty4j 进程启动并消费一小段输出后的结果，供探测单测注入。 */
    internal data class CommandResult(val exitCode: Int, val output: String)

    /**
     * 启动一个带 PTY 的子进程。
     *
     * pty4j 在 Windows 上优先走 ConPTY，失败时按库自身策略回退；Unix 直接使用系统 PTY。
     * `environment` 是完整环境快照，调用方负责在这里传入已合并好的环境。
     */
    fun start(
        command: List<String>,
        directory: File,
        environment: Map<String, String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
    ): PtyProcess {
        require(command.isNotEmpty()) { "要启动的 PTY 命令不得为空" }
        return PtyProcessBuilder(command.toTypedArray())
            .setDirectory(directory.absolutePath)
            .setEnvironment(environment)
            .setRedirectErrorStream(true)
            .setInitialRows(rows)
            .setInitialColumns(cols)
            .setUseWinConPty(true)
            .start()
    }

    /**
     * 探测 pty4j + 当前平台原生后端是否可用。
     *
     * 探测真正启动当前 JVM 的 `-version`，不是只检查类是否存在：这样 Windows ConPTY/WinPty 原生库
     * 加载失败、Linux/macOS PTY 创建失败都会被统一捕获并退化为行级控制台。
     */
    fun isAvailable(
        logger: (String) -> Unit = {},
        run: (List<String>) -> CommandResult = ::runProbe,
    ): Boolean {
        val command = listOf(javaExecutable(), "-version")
        return runCatching { run(command) }
            .map { result ->
                val ok = result.exitCode == 0 && result.output.contains("version")
                if (!ok) {
                    logger("PTY 分配器探测失败（退出码 ${result.exitCode}），附加控制台不可用：${result.output.trim().take(240)}")
                }
                ok
            }
            .getOrElse { ex ->
                logger("PTY 分配器不可用：${ex.message ?: ex.javaClass.simpleName}")
                false
            }
    }

    /** 给 attach 端同步终端尺寸；pty4j 统一覆盖 Unix 与 Windows。 */
    fun resize(process: Process, rows: Int, cols: Int): Boolean {
        if (rows <= 0 || cols <= 0) return false
        val pty = process as? PtyProcess ?: return false
        return runCatching {
            // WinSize 类型由 pty4j 提供；构建参数只跳过外部库的 Kotlin 元数据版本校验，源码仍保持 Kotlin 1.9。
            pty.setWinSize(WinSize(cols, rows))
            true
        }.getOrDefault(false)
    }

    /** 默认探测：真实起一个 PTY 进程、读输出、限时等待。 */
    private fun runProbe(command: List<String>): CommandResult {
        val process = start(command, directory = File(System.getProperty("java.io.tmpdir")), environment = System.getenv())
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, "探测超时")
        }
        return CommandResult(process.exitValue(), output)
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        return File(File(System.getProperty("java.home"), "bin"), executable).absolutePath
    }
}
