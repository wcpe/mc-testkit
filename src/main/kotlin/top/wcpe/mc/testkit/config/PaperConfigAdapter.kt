package top.wcpe.mc.testkit.config

/**
 * 按 Minecraft 版本决定写哪种 Paper 配置（FR-21）。
 *
 * Paper 的代理在线模式配置文件随版本变迁：
 * - **1.7.10–1.12**：无 Paper 专属代理配置（BungeeCord 模式经 `spigot.yml` 即可），跳过。
 * - **1.13–1.18**：`paper.yml`，`settings.bungeecord.online-mode: true`（旧版 Paper 单文件配置）。
 * - **1.19+**：`config/paper-global.yml`，`proxies.online-mode: true`（新版 Paper 拆分全局配置，已有逻辑保留）。
 *
 * 本对象只给决策（纯函数），返回应写的配置文件名 + 嵌套路径 + 值；实际 YAML 读写由调用方经
 * [editYaml] / [setNested] 落盘（保留未涉及键、深合并）。不耦合 Gradle，便于穷举单测。
 */
object PaperConfigAdapter {

    /** 版本对应的 Paper 配置决策（null 表示该版本跳过 Paper 配置）。 */
    data class PaperConfig(
        /** 相对运行目录的配置文件路径（如 `paper.yml` / `config/paper-global.yml`）。 */
        val fileName: String,
        /** 嵌套键路径（喂给 [setNested]）。 */
        val path: List<String>,
        /** 要设的值。 */
        val value: Any?,
    )

    /**
     * 按版本返回应写的 Paper 配置决策。
     *
     * @param version Minecraft 版本（如 `1.16.5` / `1.20.1`）。
     * @return 配置决策；1.7.10–1.12 返回 null（跳过）。
     */
    fun forVersion(version: String): PaperConfig? = when {
        MinecraftVersionGroup.isLegacy(version) -> null
        MinecraftVersionGroup.needsPaperYml(version) -> PaperConfig(
            fileName = "paper.yml",
            path = listOf("settings", "bungeecord", "online-mode"),
            value = true,
        )
        else -> PaperConfig(
            fileName = "config/paper-global.yml",
            path = listOf("proxies", "online-mode"),
            value = true,
        )
    }
}
