package top.wcpe.mc.testkit.dsl

/**
 * mcTestkit DSL 标记：阻止内层 DSL 块误用外层接收者（如在 backend{} 里误调 proxy{} 成员）。
 */
@DslMarker
annotation class McTestkitDsl

/**
 * 后端平台。
 *
 * 平台范围只含 Paper / Folia 后端（不含 Spigot/Bukkit/Sponge）
 * （ADR-0003 / scope-discipline）——故此处**不预置**其它平台空壳枚举项。
 */
enum class BackendPlatform { PAPER, FOLIA }

/**
 * 代理平台（P1 范围：Velocity / Waterfall / BungeeCord）。
 */
enum class ProxyPlatform { VELOCITY, WATERFALL, BUNGEECORD }
