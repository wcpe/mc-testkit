package com.example.e2e

/**
 * E2E 场景枚举（照抄物，通用骨架仅内置示例场景）。
 *
 * 消费方按真实用例在此追加自己的场景（如 BUY_SUCCESS、QUIT_DURING_PURCHASE 等），
 * 并在桩主体的派发表、机器人侧 action 分发、编排 DSL 三处同步登记同一个 id。
 *
 * ## 机器人侧登记了、本枚举却没有的场景
 *
 * `template/bot/src/connectAndWait.js` 里登记了但本枚举**刻意不含**的两个 action：
 * `network-forensics`（只发 Plugin Message 做协议取证，不需要桩判定）与
 * `exec-command-demo`（照抄用的最薄示例，见 `template/README.md` 第 3 节）。
 *
 * 它们若被直接用进 DSL 的 `scenario("...")`，会因 id 对不上而在启动期判 FAIL——报错会同时
 * 列出「DSL 传了什么」与「本桩认识哪些」，不会退化成等 600 秒的静默超时。要用这两个 action
 * 就按上面三处同步登记（`template/README.md` 有步骤）。
 *
 * @property id 场景字符串 id，须与机器人 action、编排声明的场景名完全一致（kebab-case）。
 */
enum class ScenarioName(val id: String) {
    /** 烟雾场景：无机器人，仅校验桩自身与被测插件已就绪即 PASS。 */
    SMOKE("smoke"),

    /** 机器人驱动示例场景：桩发就绪信号，机器人入服驱动一步最小动作。 */
    EXAMPLE_BOT("example-bot"),

    /** 跨服集群示例场景（集群编排）：桩对称——入服发就绪信号、收到机器人切换确认标记即 PASS。 */
    CROSS_SERVER("cross-server"),

    /** 持续压测示例场景（压测编排）：每 bot 入服发就绪信号、收集各 bot 上报，到时聚合写 PASS（薄示例不做业务断言）。 */
    CONTINUOUS_STRESS("continuous-stress"),

    /** 单场景多 bot 示例场景（单场景多 bot）：多个各唯一 username 的 bot 直连入服，桩收齐 + settle 窗口后聚合写 PASS（薄示例）。 */
    MULTI_BOT("multi-bot"),

    /** 崩溃接管 fallback 示例场景（崩溃接管）：默认后端收崩溃触发即模拟宕机；bot 经代理回退到存活后端、确认到达即 PASS（薄示例）。 */
    CRASH_TAKEOVER("crash-takeover"),

    /**
     * bot 命令通道自检场景（FR-29 / FR-30 / FR-31，ADR-0027）：机器人先用 `execCommand` 以
     * console 身份执行命令并断言**能拿到真实输出**，再用 `bot.chat` 以玩家身份执行需要 OP 的
     * 命令验证 `op = true` 生效。两段都通过才 PASS。
     *
     * <p>这是框架自身能力的**实机验证场景**，不是业务示例——它存在的意义是把
     * 「命令通道到底通不通、output 到底拿不拿得到」从推测变成事实。
     */
    BOT_COMMAND("bot-command"),

    /**
     * 持久手测（serve）空闲场景（持久手测 serve，ADR-0011）：mc-testkit serve 模式经 `MC_TESTKIT_E2E_SCENARIO`
     * 下发此**保留 id**（字面量对齐 `McTestkitContract.SERVE_SCENARIO_ID`，不 import 插件包）。
     * 桩据此**空闲**：不驱动任何场景、不挂超时、不写结果、不关服——把服务端留给真人客户端手测。
     */
    SERVE("__mc_testkit_serve__"),
    ;

    companion object {
        /** 按 id 解析场景；未知 id 直接报中文错误，避免静默跑错场景。 */
        fun from(id: String): ScenarioName =
            entries.firstOrNull { it.id == id }
                ?: error("未知 E2E 场景: $id（可选值: ${entries.joinToString(", ") { it.id }}）")
    }
}
