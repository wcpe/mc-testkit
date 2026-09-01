package top.wcpe.mc.testkit.contract

/**
 * 插件级固定标识（对外契约①：一经发布即契约，见 docs/API.md）。
 */
object McTestkitContract {
    /** 插件 id：消费方 `plugins { id("top.wcpe.mc-testkit") }` 应用（ADR-0001）。 */
    const val PLUGIN_ID = "top.wcpe.mc-testkit"

    /** DSL 扩展名：`mcTestkit { }`。 */
    const val EXTENSION_NAME = "mcTestkit"

    /**
     * 持久手测（serve）模式的**保留场景 id**（ADR-0011）：serve 起后端时经 `MC_TESTKIT_E2E_SCENARIO`
     * 下发它，告诉桩「进入空闲、不驱动任何场景、不关服」。
     *
     * 用双下划线前后缀与消费方 kebab-case 业务场景名划清边界（不会相撞）。`template/harness` 据此**字面量**
     * 对齐（不 import 插件包）加空闲分支；未同步新模板的老桩遇此未知 id 在 `onEnable` 抛错被禁用，
     * 服务端照常挂起（对任何桩都安全）。一经发布即契约，见 docs/API.md §3.3。
     */
    const val SERVE_SCENARIO_ID = "__mc_testkit_serve__"
}

/**
 * 编排默认值（端口 / 版本等）。消费方可经环境变量覆盖（见 [McTestkitEnv]）。
 *
 * 当前只放 插件骨架 契约必需的最小集；具体编排默认值随实现 FR 在此补全。
 */
object McTestkitDefaults {
    /** 后端默认 Minecraft 版本。 */
    const val MINECRAFT_VERSION = "1.20.1"

    /** 压测默认随机种子（各 bot 用 seed ^ botIndex 播种，行为可复现）。可经 `stress { randomSeed }` 覆盖。 */
    const val STRESS_RANDOM_SEED = 20260622L

    /**
     * 代理 Velocity 缺省版本（Velocity 自有版本号、非 MC 版本；官方当前可下载的最新 3.x，需 Java 21，见 ADR-0010）。
     * 可经环境变量 [McTestkitEnv.VELOCITY_VERSION] 覆盖。
     */
    const val VELOCITY_VERSION = "3.5.1"

    /**
     * Velocity modern forwarding 的共享 secret：代理 `forwarding.secret` 文件与后端 `paper-global.yml`
     * 的 `proxies.velocity.secret` 取同值，二者匹配后端才接受代理转发的玩家身份（见 ADR-0010）。
     * 仅用于 localhost 临时测试环境，非安全敏感项。
     */
    const val VELOCITY_FORWARDING_SECRET = "mc-testkit-e2e-velocity-secret"
}
