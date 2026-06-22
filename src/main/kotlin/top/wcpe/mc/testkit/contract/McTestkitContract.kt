package top.wcpe.mc.testkit.contract

/**
 * 插件级固定标识（对外契约①：一经发布即契约，见 docs/API.md）。
 */
object McTestkitContract {
    /** 插件 id：消费方 `plugins { id("top.wcpe.mc-testkit") }` 应用（ADR-0001）。 */
    const val PLUGIN_ID = "top.wcpe.mc-testkit"

    /** DSL 扩展名：`mcTestkit { }`。 */
    const val EXTENSION_NAME = "mcTestkit"
}

/**
 * 编排默认值（端口 / 版本等）。消费方可经环境变量覆盖（见 [McTestkitEnv]）。
 *
 * 当前只放 FR-01 契约必需的最小集；具体编排默认值随实现 FR 在此补全。
 */
object McTestkitDefaults {
    /** 后端默认 Minecraft 版本。 */
    const val MINECRAFT_VERSION = "1.20.1"

    /** 压测默认随机种子（各 bot 用 seed ^ botIndex 播种，行为可复现）。可经 `stress { randomSeed }` 覆盖。 */
    const val STRESS_RANDOM_SEED = 20260622L
}
