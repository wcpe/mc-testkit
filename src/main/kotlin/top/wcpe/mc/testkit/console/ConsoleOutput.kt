package top.wcpe.mc.testkit.console

/**
 * 服务端**终端流**的文本清洗（附加控制台 / PTY 模式用）。
 *
 * PTY 模式下的服务端认为自己在一台真终端前，于是它的 stdout 不再是纯日志行，而是**终端流**：
 * 带 ANSI 颜色与控制序列、用 `\r` + `ESC[K` 回行首重绘、`>` 提示符会与日志行挤在同一行。
 * 这一层负责把它还原成两种用途的文本：
 *
 * - [forLog]：写进运行目录 `<key>.log`——去掉 ANSI 与 `\r`，**保留全部文本**（排障要能 grep 到内容，
 *   但不想在日志里看到转义字节）；
 * - [forView]：打到 Gradle 控制台——在 [forLog] 基础上再去掉行首的提示符残留（`>` + 占位点），
 *   纯提示符行直接丢弃（否则控制台会被 JLine 的重绘刷屏）。
 *
 * 注意：命令表解析（`help` 输出）跑在 [forLog] 的结果上——它已经是 ANSI 容忍的文本。
 */
internal object ConsoleOutput {

    /**
     * ANSI 转义：CSI（颜色/清行/光标）、OSC（标题）、以及通用的双字符转义（如 JLine 会发的 `ESC M`）。
     *
     * 通用形态用「ESC + 可选中间字节 + 终止字节」表达（ECMA-48：中间字节 0x20–0x2F、终止字节 0x30–0x7E），
     * 否则像 `ESC M` 这种会漏网（实测在真实 Paper 的 PTY 流里出现过，留在日志里很难看）。
     */
    private val ANSI = Regex(
        "\u001B\\[[0-9;?]*[ -/]*[@-~]" +
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            "|\u001B[ -/]*[0-~]" +
            "|\u0007",
    )

    /** 去掉 ANSI 逃逸序列与 BEL（终端控制信息，不承载文本）。 */
    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    /**
     * 日志用文本：去 ANSI、应用退格、去 `\r`。
     *
     * 退格要**应用**而不是丢掉：JLine 用退格做增量回显（`lis\b\b\blist` 表示最终文本是 `list`），
     * 直接丢会拼成 `lislist`（错），丢掉而不补正好相反——应用一次得到的就是屏幕上最终显示的内容。
     */
    fun forLog(rawLine: String): String = applyBackspaces(stripAnsi(rawLine)).replace("\r", "")

    /** 控制台视图用文本：再去掉行首提示符残留；整行只是提示符/控制噪声时返回 null（不打印）。 */
    fun forView(rawLine: String): String? {
        val cleaned = forLog(rawLine)
        // 提示符（`>`）后面紧跟的占位点是 JLine 的绘制残留，不是服务端输出；只在该行确实以 `>` 开头时裁掉
        val text = if (cleaned.startsWith(">")) cleaned.trimStart('>', '.', ' ') else cleaned
        return text.takeIf { it.isNotBlank() }
    }

    /** 应用退格（`\b` 删前一个字符；在行首则是无操作）。 */
    private fun applyBackspaces(text: String): String {
        if (!text.contains('\b')) return text
        val builder = StringBuilder(text.length)
        text.forEach { char ->
            when {
                char == '\b' -> if (builder.isNotEmpty()) builder.deleteCharAt(builder.length - 1)
                char == '\u0000' -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }
}
