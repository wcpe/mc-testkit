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

    /**
     * 机器人请求执行一条**服务端侧断言**的前缀：{@code E2E_ASSERT:<name>}。
     *
     * <p>与 {@link #STRESS_RESULT_PREFIX} 的方向相反：那条是机器人**上报**结论，
     * 这条是机器人**请求服务端判定**。两者分开命名，避免"谁在判定"这件事在一串前缀里含糊。
     *
     * <p>它存在的理由见 {@link McTestkitServerAssertions}：有些事实只有服务端内部知道，
     * 把它打成聊天文本再让机器人解析，既脆弱也不是断言。
     */
    public static final String ASSERTION_PREFIX = "E2E_ASSERT:";

    /**
     * 服务端断言的<b>完成回执</b>前缀：{@code E2E_ASSERT_DONE:<name>:<PASS|FAIL>}。
     *
     * <p>结论本身仍只写在结果文件里（那是唯一真源）。这条回执只回答一个不同的问题：
     * <b>断言到底跑没跑</b>。
     *
     * <p>不区分这两件事会掩盖一整类故障：桩没被装载、断言名拼错、断言体里抛异常——
     * 在机器人侧全都表现为"等不到任何东西"，于是被当成超时。
     * 而"没跑"和"跑了但没通过"的排查方向完全相反：前者要查桩装没装上，
     * 后者要查被测行为。回执让机器人能立刻分辨，而不必去猜。
     */
    public static final String ASSERTION_DONE_PREFIX = "E2E_ASSERT_DONE:";

    private McTestkitProtocol() {
    }

    /** 拼接「就绪信号」完整消息：{@code E2E_READY:<scenarioId>}。 */
    public static String readyMessage(String scenarioId) {
        return READY + ":" + scenarioId;
    }

    /**
     * 拼接「服务端断言请求」完整消息：{@code E2E_ASSERT:<name>}。
     *
     * @param name 断言名（须已在桩侧登记）
     * @return 消息
     */
    public static String assertionRequest(String name) {
        return ASSERTION_PREFIX + name;
    }

    /**
     * 拼接「服务端断言完成回执」完整消息：{@code E2E_ASSERT_DONE:<name>:<PASS|FAIL>}。
     *
     * @param name   断言名
     * @param passed 断言是否通过
     * @return 消息
     */
    public static String assertionDone(String name, boolean passed) {
        return ASSERTION_DONE_PREFIX + name + ":" + (passed ? "PASS" : "FAIL");
    }

    /**
     * 取证结果前缀：{@code E2E_PROBE_RESULT:<name>:<text>}。
     *
     * <p>与 {@link #ASSERTION_DONE_PREFIX} 严格区分：取证只是回显，<b>不构成判定</b>，
     * 也不写结果文件。混用会让"看一眼"变成"下结论"，从而掩盖真实失败。
     */
    public static final String PROBE_RESULT_PREFIX = "E2E_PROBE_RESULT:";

    /**
     * 拼接「取证结果」完整消息。
     *
     * @param name 取证名
     * @param text 取证说明（单行；换行会被压平，避免被打断成多条消息）
     * @return 消息
     */
    public static String probeResult(String name, String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        return PROBE_RESULT_PREFIX + name + ":" + flat;
    }
}
