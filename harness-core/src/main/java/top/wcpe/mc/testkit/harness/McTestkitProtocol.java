package top.wcpe.mc.testkit.harness;

/**
 * mc-testkit 冻结控制协议的常量（docs/API.md §3.4），纯 JDK。
 *
 * <p>桩与机器人之间经聊天 / 插件消息通道约定消息，载荷走 {@code :} 后缀。
 * 集群 / 崩溃接管等场景的「到达确认」「触发崩溃」标记属 template / 消费方约定，不进冻结协议。
 */
public final class McTestkitProtocol {

    /** 桩通知机器人「已就绪，可开始驱动」：{@code E2E_READY:<scenario>}。 */
    public static final String READY = "E2E_READY";

    /** 压测机器人向桩上报汇总前缀：{@code E2E_STRESS_RESULT:<载荷>}。 */
    public static final String STRESS_RESULT_PREFIX = "E2E_STRESS_RESULT:";

    /** 触发机器人在购买中主动断线（中断恢复场景）。 */
    public static final String DISCONNECT_NOW = "E2E_DISCONNECT_NOW";

    /** 经插件消息 UI 通道驱动时下发的会话 token。 */
    public static final String UI_TOKEN = "E2E_UI_TOKEN";

    private McTestkitProtocol() {
    }

    /** 拼接「就绪信号」完整消息：{@code E2E_READY:<scenarioId>}。 */
    public static String readyMessage(String scenarioId) {
        return READY + ":" + scenarioId;
    }
}
