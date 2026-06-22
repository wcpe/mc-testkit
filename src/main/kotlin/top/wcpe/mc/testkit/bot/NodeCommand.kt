package top.wcpe.mc.testkit.bot

/** 当前是否为 Windows 平台（决定可执行名 / pid 收尾差异）。 */
internal val isWindows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * 当前平台的 node 可执行名（跨平台：Windows 为 `node.exe`，其余为 `node`）。
 *
 * 机器人为 mineflayer（Node 程序），由 [BotLauncher] 以此命令 + 入口脚本路径后台拉起。
 */
fun nodeCommand(): String = if (isWindows) "node.exe" else "node"
