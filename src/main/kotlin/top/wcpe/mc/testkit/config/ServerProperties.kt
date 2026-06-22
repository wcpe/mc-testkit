package top.wcpe.mc.testkit.config

import java.io.File
import java.util.Properties

/**
 * 运行目录 `server.properties` 的纯函数式编辑（FR-05）。
 *
 * 「读现有 properties → 改指定键 → 写回」，**保留未涉及键**；只依赖 JDK [Properties] / [File]，
 * 不耦合 Gradle，便于用临时目录穷举单测。键名是 Minecraft `server.properties` 既定字面量，
 * 集中在本对象常量声明，避免散落字符串。
 *
 * 本对象只做"框架级把后端调成可被测 / 经代理模式"的通用编辑（端口 / 在线模式 / 世界类型等），
 * 不写任何业务（商店 / 点券）配置——那是消费方自带桩的事（scope-discipline）。
 */
object ServerProperties {

    // ── server.properties 既定键名（Minecraft 字面量）──
    /** 监听端口。 */
    const val SERVER_PORT = "server-port"

    /** 在线模式（经代理 / 离线 bot 入服须为 false）。 */
    const val ONLINE_MODE = "online-mode"

    /** 安全档案强制（离线 bot 入服须为 false）。 */
    const val ENFORCE_SECURE_PROFILE = "enforce-secure-profile"

    /** 世界类型（压测常设平坦世界 `minecraft:flat` 省时省盘）。 */
    const val LEVEL_TYPE = "level-type"

    /** 最大玩家数。 */
    const val MAX_PLAYERS = "max-players"

    /** 视距。 */
    const val VIEW_DISTANCE = "view-distance"

    /** 模拟距离。 */
    const val SIMULATION_DISTANCE = "simulation-distance"

    /** 出生点保护半径（设 0 便于 bot 在出生点附近活动）。 */
    const val SPAWN_PROTECTION = "spawn-protection"

    /** 端口缺省回退值（运行目录无 `server.properties` 或未写 server-port 时用）。 */
    const val DEFAULT_PORT = 25565

    /** 读运行目录下的 `server.properties`；文件不存在返回空 [Properties]。 */
    fun load(runDir: File): Properties {
        val file = File(runDir, "server.properties")
        return Properties().apply {
            if (file.exists()) {
                file.reader(Charsets.UTF_8).use { load(it) }
            }
        }
    }

    /**
     * 编辑 `server.properties`：load → 写入 [overrides] 各键（覆盖已存在键、保留未涉及键）→ 写回。
     *
     * @param runDir 运行目录（`server.properties` 所在 / 将写入处）。
     * @param overrides 要写入的键值（键用本对象常量；值为字符串）。
     * @param comment 写回时的文件头注释（默认通用说明）。
     * @return 写回后的 [Properties]（便于调用方 / 测试断言）。
     */
    fun edit(
        runDir: File,
        overrides: Map<String, String>,
        comment: String = "mc-testkit E2E server.properties",
    ): Properties {
        val properties = load(runDir)
        overrides.forEach { (key, value) -> properties.setProperty(key, value) }
        val file = File(runDir, "server.properties")
        file.parentFile?.mkdirs()
        // 用 UTF-8 Writer 写回：与读取一致，避免中文值（如 motd）被 store 的 ISO-8859-1 默认编码弄乱。
        file.writer(Charsets.UTF_8).use { properties.store(it, comment) }
        return properties
    }

    /** 读 `server-port`，缺失 / 非法时回退 [DEFAULT_PORT]。 */
    fun port(runDir: File): Int =
        load(runDir).getProperty(SERVER_PORT)?.toIntOrNull() ?: DEFAULT_PORT
}
