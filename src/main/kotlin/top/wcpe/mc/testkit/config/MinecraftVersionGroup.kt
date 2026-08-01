package top.wcpe.mc.testkit.config

/**
 * Minecraft 版本分组常量与查询函数（FR-21）。
 *
 * 定义 8 个代表版本（覆盖 Legacy → Modern → PaperConfig 三段），并按版本段分组提供查询函数，
 * 供 [ServerProperties.versionAwareOverrides] / [PaperConfigAdapter] / [JavaRuntimeSelector] /
 * bot 版本范围校验等版本感知逻辑共用。纯函数、不耦合 Gradle，便于穷举单测。
 *
 * 分组边界（按 Minecraft 版本号 major.minor.patch 比较）：
 * - **Legacy**：1.7–1.12（`level-type=FLAT`、无 `simulation-distance` / `enforce-secure-profile`、跳过 paper 配置）
 * - **Modern**：1.13–1.18（`level-type=minecraft:flat`、有 `simulation-distance`、无 `enforce-secure-profile`、写 `paper.yml`）
 * - **PaperConfig**：1.19+（`level-type=minecraft:flat`、有 `simulation-distance` / `enforce-secure-profile`、写 `paper-global.yml`）
 */
object MinecraftVersionGroup {

    /** 8 个代表版本（FR-21 覆盖范围，按时间序）。 */
    val REPRESENTATIVE_VERSIONS: List<String> = listOf(
        "1.7.10",
        "1.8.8",
        "1.12.2",
        "1.16.5",
        "1.17.1",
        "1.19.4",
        "1.20.1",
        "1.21.1",
    )

    /** Legacy 段上限（含）：1.12.x 及更早为 Legacy。 */
    private const val LEGACY_CEILING = "1.12"

    /** Modern 段下限（含）：1.13 起为 Modern。 */
    private const val MODERN_FLOOR = "1.13"

    /** PaperConfig 段下限（含）：1.19 起写 `paper-global.yml`。 */
    private const val PAPER_GLOBAL_FLOOR = "1.19"

    /** bot E2E 支持下限（含）：1.8 起支持 mineflayer bot；1.7.10 跳过 bot。 */
    private const val BOT_SUPPORT_FLOOR = "1.8"

    /**
     * 是否为 Legacy 版本（1.7–1.12）。
     *
     * Legacy 版本：`level-type=FLAT`、无 `simulation-distance` / `enforce-secure-profile`、跳过 paper 配置。
     */
    fun isLegacy(version: String): Boolean = compareVersions(version, MODERN_FLOOR) < 0

    /**
     * 是否需要写 `paper.yml`（1.13–1.18）。
     *
     * Modern 版本用 `paper.yml`（非 `paper-global.yml`）承载代理在线模式配置。
     */
    fun needsPaperYml(version: String): Boolean =
        !isLegacy(version) && compareVersions(version, PAPER_GLOBAL_FLOOR) < 0

    /**
     * 是否需要写 `paper-global.yml`（1.19+）。
     *
     * PaperConfig 版本用 `config/paper-global.yml` 承载代理在线模式配置（已有逻辑保留）。
     */
    fun needsPaperGlobal(version: String): Boolean = compareVersions(version, PAPER_GLOBAL_FLOOR) >= 0

    /**
     * 是否支持 bot E2E（1.8+）。
     *
     * 1.7.10 的 mineflayer 协议支持不完整，跳过 bot 启动、仅验服务端拉起。
     */
    fun isBotSupported(version: String): Boolean = compareVersions(version, BOT_SUPPORT_FLOOR) >= 0

    /**
     * 取 Java 版本段标识（用于 `MC_TESTKIT_JAVA_HOME_<段>` 环境变量名）。
     *
     * 把版本号的 major.minor 用下划线连接：1.7.10 → `1_7`、1.17.1 → `1_17`、1.20.1 → `1_20`。
     * 不足两段（异常串）则原样返回（不崩溃，调用方自然查不到对应 env 而回退 `JAVA_HOME`）。
     */
    fun javaVersionSegment(version: String): String {
        val parts = version.split(".")
        return if (parts.size >= 2) "${parts[0]}_${parts[1]}" else version
    }

    /**
     * 比较两个 Minecraft 版本号（major.minor.patch）。
     *
     * @return 负数表示 [left] 早于 [right]、0 表示相等、正数表示 [left] 晚于 [right]。
     * 不足三段的部分按 0 补齐（1.20 与 1.20.0 等价）。
     */
    private fun compareVersions(left: String, right: String): Int {
        val lp = parseVersion(left)
        val rp = parseVersion(right)
        return compareValues(lp.first, rp.first)
            .let { if (it != 0) it else compareValues(lp.second, rp.second) }
            .let { if (it != 0) it else compareValues(lp.third, rp.third) }
    }

    /** 解析版本号为 (major, minor, patch)；非数字段按 0 处理（不崩溃）。 */
    private fun parseVersion(version: String): Triple<Int, Int, Int> {
        val parts = version.split(".")
        return Triple(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }
}
