package top.wcpe.mc.testkit.dsl

/**
 * mcTestkit DSL 标记：阻止内层 DSL 块误用外层接收者（如在 backend{} 里误调 proxy{} 成员）。
 */
@DslMarker
annotation class McTestkitDsl

/**
 * 后端平台。
 *
 * 后端平台覆盖 Bukkit API 的代表实现：Paper、Folia 与 Spigot。
 */
enum class BackendPlatform { PAPER, FOLIA, SPIGOT }

/**
 * 代理平台（P1 范围：Velocity / Waterfall / BungeeCord）。
 */
enum class ProxyPlatform { VELOCITY, WATERFALL, BUNGEECORD }
