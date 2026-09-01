package top.wcpe.mc.testkit.provision

import top.wcpe.mc.testkit.contract.McTestkitEnv

/**
 * 六个平台 → 下载源 + 环境变量覆盖名 的映射（内部用，内置下载与运行）。
 *
 * 后端 Paper/Folia 与代理 Velocity/Waterfall 走 PaperMC 下载 API；BungeeCord 走 SpigotMC Jenkins；
 * Spigot 依次使用经授权的 GetBukkit 与 GitHub 构件镜像。本枚举不对外暴露：DSL 侧已有
 * `dsl/Platforms` 的对外平台枚举，本枚举只供下载模块内部解析下载源。
 *
 * @property id 平台标识（与 DSL 平台便捷量字面量一致，便于 任务自动编排 接线时按名映射）。
 * @property paperProject PaperMC 下载 API 的 project 名；BungeeCord 经 Jenkins 故为 null。
 * @property jarEnv 直接提供 jar 路径的环境变量名（存在即优先、不下载）。
 * @property versionEnv 覆盖版本的环境变量名（BungeeCord 无版本概念故为 null）。
 * @property versionedByMajorMinor 该平台在其下载源是否仅按 Minecraft major.minor 发布（仅 Waterfall：
 * PaperMC 的 waterfall 版本是 1.20 / 1.21 这种，没有补丁号；Paper/Folia 按完整 MC 版本、Velocity 用自有版本号）。
 */
internal enum class ProvisionPlatform(
    val id: String,
    val paperProject: String?,
    val jarEnv: String,
    val versionEnv: String?,
    val versionedByMajorMinor: Boolean = false,
) {
    /** Paper 后端（PaperMC API）。后端 jar 覆盖名取契约常量 [McTestkitEnv.PAPER_JAR]（离线/CI 逃生口）。 */
    PAPER("paper", "paper", McTestkitEnv.PAPER_JAR, McTestkitEnv.MINECRAFT_VERSION),

    /** Folia 后端（PaperMC API）。后端 jar 覆盖名取 [McTestkitEnv.FOLIA_JAR]。 */
    FOLIA("folia", "folia", McTestkitEnv.FOLIA_JAR, McTestkitEnv.MINECRAFT_VERSION),

    /** Spigot 后端（经授权的公共构件源；下载后记录实际来源、版本与本地 SHA-256）。 */
    SPIGOT("spigot", null, McTestkitEnv.SPIGOT_JAR, McTestkitEnv.MINECRAFT_VERSION),

    /** Velocity 代理（PaperMC API）。版本为 Velocity 自有版本号（如 3.5.1），非 MC 版本。 */
    VELOCITY("velocity", "velocity", McTestkitEnv.VELOCITY_JAR, McTestkitEnv.VELOCITY_VERSION),

    /** Waterfall 代理（PaperMC API）。PaperMC 仅按 major.minor 发布（如 1.20），故 [versionedByMajorMinor] = true。 */
    WATERFALL("waterfall", "waterfall", McTestkitEnv.WATERFALL_JAR, McTestkitEnv.WATERFALL_VERSION, versionedByMajorMinor = true),

    /** BungeeCord 代理（SpigotMC Jenkins，无版本，仅构建号）。 */
    BUNGEECORD("bungeecord", null, McTestkitEnv.BUNGEECORD_JAR, null),
    ;

    /** 是否经 PaperMC 下载 API（否则经 SpigotMC Jenkins）。 */
    val usesPaperApi: Boolean get() = paperProject != null

    /** 是否使用固定版本的 Spigot 公共构件地址。 */
    val usesGetBukkit: Boolean get() = this == SPIGOT

    /** Spigot 的固定版本构件地址，首源不可达时按顺序回退。 */
    fun downloadUrls(version: String): List<String> {
        require(usesGetBukkit) { "平台 $id 不使用 GetBukkit 下载源。" }
        return listOf(
            "https://download.getbukkit.org/spigot/spigot-$version.jar",
            "https://github.com/BaldGang/spigot-build/releases/latest/download/spigot-$version.jar",
        )
    }

    /**
     * 把"请求版本"归一为该平台下载源实际可用的版本。
     *
     * Waterfall 在 PaperMC 仅按 major.minor 发布（如 1.20），传入完整 MC 版本（1.20.1）会请求到
     * 不存在的 `.../versions/1.20.1` 而 404，故截到 major.minor；Paper/Folia 按完整版本发布、
     * Velocity 用自有版本号，均原样返回。纯函数，可穷举单测。
     */
    fun downloadVersion(version: String): String =
        if (versionedByMajorMinor) majorMinorOf(version) else version

    companion object {
        /**
         * 按平台 id 解析（大小写不敏感）。
         *
         * @throws IllegalArgumentException 平台不受支持时抛中文错误。
         */
        fun fromId(id: String): ProvisionPlatform =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "不支持的平台 '$id'：当前支持 Paper/Folia/Spigot 后端与 Velocity/Waterfall/BungeeCord 代理。",
                )

        /**
         * 取版本号的 major.minor（如 `1.20.1` → `1.20`，`26.2.1` → `26.2`）。
         *
         * 同时覆盖旧 `1.x.y` 与 26.x 新版号方案；不足两段（已是 major.minor 或异常串）则原样返回。
         */
        private fun majorMinorOf(version: String): String {
            val parts = version.split('.')
            return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else version
        }
    }
}
