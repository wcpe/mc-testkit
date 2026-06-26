package com.example.e2e

/**
 * E2E 场景枚举（照抄物，通用骨架仅内置示例场景）。
 *
 * 消费方按真实用例在此追加自己的场景（如 BUY_SUCCESS、QUIT_DURING_PURCHASE 等），
 * 并在桩主体的派发表、机器人侧 action 分发、编排 DSL 三处同步登记同一个 id。
 *
 * @property id 场景字符串 id，须与机器人 action、编排声明的场景名完全一致（kebab-case）。
 */
enum class ScenarioName(val id: String) {
    /** 烟雾场景：无机器人，仅校验桩自身与被测插件已就绪即 PASS。 */
    SMOKE("smoke"),

    /** 机器人驱动示例场景：桩发就绪信号，机器人入服驱动一步最小动作。 */
    EXAMPLE_BOT("example-bot"),

    /** 跨服集群示例场景（FR-10）：桩对称——入服发就绪信号、收到机器人切换确认标记即 PASS。 */
    CROSS_SERVER("cross-server"),

    /** 持续压测示例场景（FR-11）：每 bot 入服发就绪信号、收集各 bot 上报，到时聚合写 PASS（薄示例不做业务断言）。 */
    CONTINUOUS_STRESS("continuous-stress"),

    /** 单场景多 bot 示例场景（FR-16）：多个各唯一 username 的 bot 直连入服，桩收齐 + settle 窗口后聚合写 PASS（薄示例）。 */
    MULTI_BOT("multi-bot"),
    ;

    companion object {
        /** 按 id 解析场景；未知 id 直接报中文错误，避免静默跑错场景。 */
        fun from(id: String): ScenarioName =
            entries.firstOrNull { it.id == id }
                ?: error("未知 E2E 场景: $id（可选值: ${entries.joinToString(", ") { it.id }}）")
    }
}
