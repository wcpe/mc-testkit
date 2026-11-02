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

    /**
     * 命令通道的 Minecraft 自定义 payload 通道名（FR-30，ADR-0027）：{@code mc-testkit:cmd}。
     *
     * <p>通道名格式随版本而变，本名对两段格式都合法，故**同一个名字**覆盖全部支持版本：
     *
     * <ul>
     *   <li><b>1.13 及以后</b>：通道名是 {@code namespace:path} 形式的 {@code Identifier}，
     *       Bukkit 侧会拒绝不含冒号的名字（{@code StandardMessenger.validateAndCorrectChannel}）。</li>
     *   <li><b>1.12 及更早</b>：通道名只是裸字符串，约束是有长度上限（旧版上限 20 字符），
     *       本名 14 字符，同样在限内。</li>
     * </ul>
     *
     * <p>注意：通道名合法只是**必要条件**。服务端把回执发回给某个玩家时，
     * 还会检查该玩家是否声明过接收这个通道（Paper 的 {@code CraftPlayer.sendPluginMessage}
     * 会比对玩家已 REGISTER 的通道集合，未声明则**静默丢弃**）。因此机器人侧必须先声明该通道，
     * 见 {@code @wcpe/mc-testkit-bot/lib/commands}。
     */
    public static final String COMMAND_CHANNEL = "mc-testkit:cmd";

    /**
     * 机器人请求以 console 身份执行一条命令：{@code E2E_CMD:<id>:<command>}。
     *
     * <p>{@code <id>} 由机器人生成，用于配对请求与回执；{@code <command>} 是**其余全部内容**，
     * 因此命令里出现的冒号（例如 {@code give <玩家> minecraft:stone}）原样保留。
     * 解析方必须按「在**首个**冒号处切分」取值，不能按段数切。
     */
    public static final String COMMAND_PREFIX = "E2E_CMD:";

    /**
     * console 命令的执行回执：{@code E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>}。
     *
     * <h2>解析规则（必须按此实现，否则 output 里的冒号会被切坏）</h2>
     *
     * 前两段位置固定——{@code <id>} 与 {@code <PASS|FAIL>} 都不得含冒号；
     * {@code <output>} 是**最后一段**，可以是任意内容。故正确做法是
     * <b>最多切成 4 段</b>（{@code split(":", 4)}）：第 1 段是前缀 {@code E2E_CMD_DONE}，
     * 第 2 段是 id，第 3 段是判定，第 4 段是 output 全文（其中的冒号原样保留）。
     *
     * <p>反例：{@code "a:b".split(":")} 会把 {@code output=a:b} 切出第 5 段，
     * 于是末段只剩 {@code b}，回执看起来「有输出」但内容已被截断——属于静默失真。
     *
     * <p>另见 {@link #commandDone(String, boolean, String)} 对 {@code <output>} 的单行化约定。
     */
    public static final String COMMAND_DONE_PREFIX = "E2E_CMD_DONE:";

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

    /**
     * 拼接「console 命令请求」完整消息：{@code E2E_CMD:<id>:<command>}。
     *
     * @param id      请求 id（**不得含冒号**——它是第一个字段，含冒号会让接收方配错请求）
     * @param command 待执行命令（可带前导 {@code /}，桩侧会去掉；其中的冒号原样保留）
     * @return 消息
     * @throws IllegalArgumentException id 为空或含冒号时抛出，消息为中文
     */
    public static String commandRequest(String id, String command) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("命令请求 id 不得为空——没有 id 的回执无法与请求配对");
        }
        if (id.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                "命令请求 id 不得含冒号（收到：" + id + "）——它是第一个字段，含冒号会让接收方配错请求");
        }
        return COMMAND_PREFIX + id + ":" + (command == null ? "" : command);
    }

    /**
     * 拼接「console 命令执行回执」完整消息：{@code E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>}。
     *
     * <p>{@code output} 会被**单行化**（换行 / 回车压成空格）：回执是一条消息而不是一段文本，
     * 留换行会让读日志与解析的人看到被切断的回执。
     *
     * @param id     请求 id（与 {@link #commandRequest(String, String)} 的 id 逐字一致）
     * @param passed 命令是否被服务端接受并执行
     * @param output 单行化的命令输出 / 失败原因（可为 null，按空串处理）
     * @return 消息
     */
    public static String commandDone(String id, boolean passed, String output) {
        return COMMAND_DONE_PREFIX + id + ":" + (passed ? "PASS" : "FAIL") + ":" + singleLine(output);
    }

    /**
     * 从 {@code E2E_CMD:<id>:<command>} 取出 id。
     *
     * @param payload 收到的消息
     * @return id；消息不是命令请求（无 {@link #COMMAND_PREFIX} 前缀或缺 id）时返回 null
     */
    public static String commandRequestId(String payload) {
        if (payload == null || !payload.startsWith(COMMAND_PREFIX)) {
            return null;
        }
        int end = payload.indexOf(':', COMMAND_PREFIX.length());
        if (end < 0) {
            return null;
        }
        String id = payload.substring(COMMAND_PREFIX.length(), end);
        return id.isEmpty() ? null : id;
    }

    /**
     * 从 {@code E2E_CMD:<id>:<command>} 取出命令全文。
     *
     * <p>在**首个**冒号处切分，故命令里出现的冒号（如 {@code minecraft:stone}）原样保留。
     *
     * @param payload 收到的消息
     * @return 命令；消息不是命令请求时返回 null（空命令返回空串）
     */
    public static String commandRequestBody(String payload) {
        if (payload == null || !payload.startsWith(COMMAND_PREFIX)) {
            return null;
        }
        int end = payload.indexOf(':', COMMAND_PREFIX.length());
        if (end < 0) {
            return null;
        }
        return payload.substring(end + 1);
    }

    /** 压成单行：换行 / 回车变空格，避免一条回执在日志里被切成几行。 */
    private static String singleLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
