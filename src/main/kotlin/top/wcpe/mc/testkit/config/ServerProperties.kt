package top.wcpe.mc.testkit.config

import java.io.File
import java.util.Properties

/**
 * 运行目录 `server.properties` 的纯函数式编辑（环境契约）。
 *
 * 「读现有 properties → 改指定键 → 写回」，**保留未涉及键**；只依赖 JDK [Properties] / [File]，
 * 不耦合 Gradle，便于用临时目录穷举单测。键名是 Minecraft `server.properties` 既定字面量，
 * 集中在本对象常量声明，避免散落字符串。
 *
 * **保留范围以键值为准**：底层 [Properties.store] 只保留键值对，**不保留原文件注释与键顺序**、且每次写回会带
 * 一行时间戳注释——故写回后文件**逐字节不等**于原文件，也非跨次幂等（这对 E2E 用途无影响：后端每轮重铺
 * 运行目录）。需要保留注释 / 结构的配置（如 `spigot.yml` / `paper-global.yml`）走 `BackendBungeeCordConfig`
 * 的 YAML 读改写回，不用本对象。
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

    /** 难度（测试环境默认 `peaceful`，保护测试玩家不被怪物 / 环境杀；消费方模板已设则保留，见 默认 peaceful 难度）。 */
    const val DIFFICULTY = "difficulty"

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

    /**
     * 按 Minecraft 版本过滤不支持的键 + 转换 `level-type`（多版本服务端拉起）。
     *
     * 不同版本的服务端对 `server.properties` 键的支持范围不同，写入不存在的键会被忽略或导致异常。
     * 本函数在调用方把 overrides 喂给 [edit] 之前做版本感知过滤：
     *
     * - **1.7.10–1.13**：移除 `simulation-distance`（1.14 才引入）。
     * - **1.7.10–1.18**：移除 `enforce-secure-profile`（1.19 才引入）。
     * - **`level-type` 转换**：1.7.10–1.12 用 `FLAT`（旧版字面量），1.13+ 用 `minecraft:flat`（带命名空间）。
     *   若 overrides 含 `level-type` 则按版本改写其值，确保旧版服务端能识别。
     *
     * 纯函数、不读写文件，便于穷举单测。
     *
     * @param version Minecraft 版本（如 `1.7.10` / `1.20.1`）。
     * @param baseOverrides 调用方构建的原始 overrides（键用本对象常量）。
     * @return 过滤 + 转换后的 overrides（新映射，不改入参）。
     */
    fun versionAwareOverrides(version: String, baseOverrides: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap(baseOverrides)
        // 1.7.10–1.13：移除 simulation-distance（1.14 才引入）
        if (MinecraftVersionGroup.isLegacy(version) || isVersion134OrEarlier(version)) {
            result.remove(SIMULATION_DISTANCE)
        }
        // 1.7.10–1.18：移除 enforce-secure-profile（1.19 才引入）
        if (!MinecraftVersionGroup.needsPaperGlobal(version)) {
            result.remove(ENFORCE_SECURE_PROFILE)
        }
        // level-type 按版本转换：1.7.10–1.12 → FLAT，1.13+ → minecraft:flat
        if (result.containsKey(LEVEL_TYPE)) {
            result[LEVEL_TYPE] = if (MinecraftVersionGroup.isLegacy(version)) "FLAT" else "minecraft:flat"
        }
        return result
    }

    /** 1.13.x 及更早（simulation-distance 在 1.14 才引入）。 */
    private fun isVersion134OrEarlier(version: String): Boolean {
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major < 2 && minor <= 13
    }
}
