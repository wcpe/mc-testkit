package top.wcpe.mc.testkit.dsl

/**
 * 多版本矩阵条目：一个 Minecraft 版本对应一个后端 + 一个场景。
 *
 * @property version Minecraft 版本号（如 `1.20.1`、`26.2`）。
 * @property key 矩阵内唯一短键（默认由版本号去掉非数字得到：`1.20.1` → `1201`）。
 * @property bot 是否用 mineflayer 机器人驱动（true → `full-<key>` + bot；false → `smoke-<key>`）。
 */
@McTestkitDsl
data class MatrixVersionEntry(
    val version: String,
    var key: String = deriveKey(version),
    var bot: Boolean = false,
) : java.io.Serializable {
    companion object {
        /** 版本号 → 矩阵短键：只保留数字（`1.20.1` → `1201`，`26.1.2` → `2612`）。 */
        fun deriveKey(version: String): String {
            val digits = version.filter { it.isDigit() }
            if (digits.isNotEmpty()) return digits
            return version.replace('.', '_').replace('-', '_').ifEmpty { "v" }
        }
    }
}

/**
 * 版本矩阵声明（第 6 个顶层块，加法扩展）：一次声明多个 MC 版本，自动展开为
 * `backend("v<key>")` + `scenario("full-<key>"|"smoke-<key>")`，并生成串行聚合任务。
 *
 * ```kotlin
 * versionMatrix("nms") {
 *     platform = paper
 *     portBase = 25600
 *     botAction = "taboolib-full"
 *     entry("1.20.1")
 *     entry("1.20.6") { bot = true }
 *     entry("26.2", key = "262", bot = false)
 * }
 * ```
 */
@McTestkitDsl
class VersionMatrixSpec(val name: String) : java.io.Serializable {

    /** 矩阵内全部后端使用的平台（默认 paper）。 */
    var platform: BackendPlatform = BackendPlatform.PAPER

    /** 端口基数：第 i 个条目端口 = [portBase] + i（默认 25600，避开常见 25565）。 */
    var portBase: Int = DEFAULT_PORT_BASE

    /** 后端名前缀：后端名 = 前缀 + key（默认 `v` → `v1201`）。 */
    var backendNamePrefix: String = "v"

    /** bot-full 场景的机器人用户名前缀（默认 `Tb` → `Tb1201`）。 */
    var botUsernamePrefix: String = "Tb"

    /** bot-full 场景的 action / 场景 id（与机器人分发表一致；默认 `matrix-bot`）。 */
    var botAction: String = "matrix-bot"

    private val mutableEntries = mutableListOf<MatrixVersionEntry>()

    /** 已声明的版本条目（声明顺序即端口递增顺序）。 */
    val entries: List<MatrixVersionEntry> get() = mutableEntries.toList()

    // DSL 便捷量
    val paper: BackendPlatform get() = BackendPlatform.PAPER
    val folia: BackendPlatform get() = BackendPlatform.FOLIA
    val spigot: BackendPlatform get() = BackendPlatform.SPIGOT

    /** 声明一个版本条目（key 默认由版本号推导）。 */
    fun entry(
        version: String,
        key: String? = null,
        bot: Boolean = false,
    ) {
        val resolvedKey = key?.takeIf { it.isNotBlank() } ?: MatrixVersionEntry.deriveKey(version)
        mutableEntries += MatrixVersionEntry(version = version, key = resolvedKey, bot = bot)
    }

    /** 声明一个版本条目并配置（可改 key / bot）。 */
    fun entry(version: String, configure: MatrixVersionEntry.() -> Unit) {
        mutableEntries += MatrixVersionEntry(version = version).apply(configure)
    }

    /** 批量声明版本（全部默认 smoke；需要 bot 的条目请用 [entry] 单独声明）。 */
    fun versions(vararg versions: String) {
        versions.forEach { entry(it) }
    }

    companion object {
        const val DEFAULT_PORT_BASE = 25600

        /** 聚合任务缺省描述前缀。 */
        const val AGGREGATE_TASK_PREFIX = "e2eMatrix"
    }
}
