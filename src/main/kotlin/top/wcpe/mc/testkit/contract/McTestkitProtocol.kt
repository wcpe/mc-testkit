package top.wcpe.mc.testkit.contract

/**
 * 对外契约④：机器人↔桩控制协议消息名。
 *
 * 机器人与桩通过聊天 / 插件消息通道约定这些消息（沿用首个接入项目的既有约定）。
 * 消息**载荷**由 `:` 后缀承载，如 `E2E_READY:<scenario>`。
 */
object McTestkitControlProtocol {
    /** 桩通知机器人「已装备就绪，可开始驱动」：`E2E_READY:<scenario>`。 */
    const val READY = "E2E_READY"

    /** 压测机器人向桩上报汇总：`E2E_STRESS_RESULT:ok=<n>,err=<n>,...`。 */
    const val STRESS_RESULT = "E2E_STRESS_RESULT"

    /** 触发机器人在购买中主动断线（中断恢复场景）：`E2E_DISCONNECT_NOW:<...>`。 */
    const val DISCONNECT_NOW = "E2E_DISCONNECT_NOW"

    /** 经插件消息 UI 通道驱动时下发的会话 token：`E2E_UI_TOKEN:<uuid>`。 */
    const val UI_TOKEN = "E2E_UI_TOKEN"
}

/**
 * 对外契约④：测试结论真源——桩写出的结果文件约定。
 *
 * 编排 / 任务**只认结果文件**判 PASS/FAIL，不靠日志猜测（NFR 正确性优先、架构不变量真源）。
 */
object McTestkitResultFile {
    /** 结果状态键。 */
    const val STATUS_KEY = "status"

    /** 结果说明键（失败原因写这里）。 */
    const val MESSAGE_KEY = "message"

    /** 通过状态值。 */
    const val PASS = "PASS"

    /** 失败状态值。 */
    const val FAIL = "FAIL"

    /** 某场景的结果文件名：`<scenario>.properties`。 */
    fun fileName(scenario: String): String = "$scenario.properties"
}
