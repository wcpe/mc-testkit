package top.wcpe.mc.testkit.harness;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * mc-testkit E2E 桩插件公共基类（共享胶水构件）。
 *
 * <p>封装桩插件与 mc-testkit 编排之间的**协议胶水**（docs/API.md §3.3 / §3.4 / §3.5）：
 *
 * <ul>
 *   <li>场景 id / 结果文件经环境变量读取（{@link McTestkitEnv}），未下发时回退 config.yml
 *       （{@code result-file} 相对 dataFolder）；</li>
 *   <li>持久手测（serve）保留场景 id → 桩**空闲**：不驱动、不判定、不关服（持久手测 serve）；</li>
 *   <li>判定收尾：{@link #pass(String, Map)} / {@link #fail(String)} 经
 *       {@link McTestkitResultWriter} **原子**写出结果文件后延迟关服（幂等门防重复写 / 重复关服）；</li>
 *   <li>控制消息：{@link #sendReady(Player)} 发 {@code E2E_READY:<scenario>}（冻结协议）；</li>
 *   <li>console 命令通道（FR-30，ADR-0027）：自动注册插件消息通道
 *       {@link McTestkitProtocol#COMMAND_CHANNEL}，收到 {@code E2E_CMD:<id>:<command>} 后
 *       <b>投主线程</b>以服务端 console 身份执行，并通过同一通道回
 *       {@code E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>}。消费方无需任何接线；</li>
 *   <li>调度：{@link #runLater(long, Runnable)} / {@link #runOnMainThread(Runnable)} 兼容
 *       Paper（Bukkit 调度器）与 Folia（GlobalRegionScheduler，反射调用、编译期不依赖 Folia 专有类）。</li>
 * </ul>
 *
 * <p>消费方继承本类，在 {@link #onHarnessEnabled()} 里驱动 / 判定自己的业务场景；
 * 监听自己的 Bukkit 事件注册到 {@code server.getPluginManager()} 即可。
 * 本类刻意**不含**任何业务逻辑与示例场景。覆盖 {@link #onDisable()} 时须调用 {@code super.onDisable()}。
 */
public abstract class McTestkitHarnessPlugin extends JavaPlugin implements Listener {

    /**
     * console 命令回执里 output 的字符上限。
     *
     * <p>自定义 payload 有硬性大小上限（Bukkit {@code Messenger.MAX_MESSAGE_SIZE} = 32766 字节，
     * 超出时 {@code sendPluginMessage} 直接抛 {@code IllegalArgumentException}）。UTF-8 下一个汉字
     * 占 3 字节，故按字符留足余量取 8000；截断时**明确写出已被截断**，不静默丢内容。
     */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /** 命令通道载荷的字符集（与机器人侧 {@code Buffer.from(text, 'utf8')} 对齐）。 */
    private static final Charset CHANNEL_CHARSET = Charset.forName("UTF-8");

    /** 编排下发的场景 id（未下发为 null；serve 空闲时为空闲保留 id）。 */
    protected final String scenarioId = McTestkitEnv.scenarioIdOrNull();

    /** 结果文件：env 下发优先（写到 verify 读取处），未下发回退 config.yml 的 result-file。 */
    protected File resultFile;

    /** 判定是否已结束（幂等门：防重复写结果文件 / 重复关服）。 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /**
     * 服务端断言登记表：让"只有服务端内部才看得见的事实"成为可判定的对象。
     *
     * <p>见 {@link McTestkitServerAssertions} 的类注释——它解决的是"判据卡在
     * 一句可能永远不会到来的聊天回执上"这个结构性问题。
     */
    private final McTestkitServerAssertions assertions = new McTestkitServerAssertions();

    /** Folia 全局区域调度器（仅 Folia 运行时非 null；反射取，避免编译期依赖 Folia 专有类）。 */
    private volatile Object foliaGlobalScheduler;

    /**
     * 命令通道的入站监听器（FR-30，ADR-0027）。
     *
     * <p>做成字段而不是匿名内部类：注销入站通道时 {@code unregisterIncomingPluginChannel} 要求
     * 传入**同一个监听器实例**，否则注销不生效、重载后同一个包会被处理两次。
     */
    private final PluginMessageListener commandListener = new CommandChannelListener();

    /**
     * 消费方业务启动钩子：在 onEnable 完成协议准备（场景 / 结果文件 / serve 判断）后调用。
     *
     * <p>serve 空闲模式下**不会被调用**（桩空闲，不驱动、不判定、不关服）。
     * 子类在此注册监听器、读取业务配置、驱动场景。
     */
    protected abstract void onHarnessEnabled();

    @Override
    public final void onEnable() {
        // 配置是可选的：桩的职责是"用服务端内部事实做判定"，多数桩不需要任何配置项。
        // 强行 saveDefaultConfig 会在桩没打包 config.yml 时直接抛 IllegalArgumentException，
        // 表现为"插件写了但因为缺配置文件起不来"——这个异常与桩的判定毫无关系，
        // 却会让整条 E2E 卡死在"断言永不执行"上，排查方向被彻底带偏。
        String fromEnv = McTestkitEnv.envOrNull(McTestkitEnv.RESULT_FILE);
        String fromConfig = null;
        if (hasBundledConfig()) {
            reloadConfig();
            fromConfig = getConfig().getString("result-file", "result.properties");
        }
        String resultName = fromConfig == null || fromConfig.isEmpty() ? DEFAULT_RESULT_FILE : fromConfig;
        this.resultFile = fromEnv != null ? new File(fromEnv) : new File(getDataFolder(), resultName);

        // 命令通道在 serve 空闲判断之前接上：它在 serve 下同样有用（人手测时也能驱动 console 命令），
        // 而注册本身不驱动、不判定、不关服，不违背 serve「桩空闲」的语义。
        registerCommandChannel();

        // serve 空闲：不注册监听、不启动业务，把服务端留给真人客户端手测（与旧模板行为一致）
        if (McTestkitEnv.isServeIdle()) {
            getLogger().info("[E2E] 持久手测模式（serve）：桩空闲，不驱动场景、不判定、不关服。");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        onHarnessEnabled();
    }

    /** 结果文件默认名（无 config.yml 时使用）。 */
    private static final String DEFAULT_RESULT_FILE = "result.properties";

    /**
     * 桩是否自带 {@code config.yml}。
     *
     * <p>用 {@code getResource} 探测而不是 try/catch 兜异常：后者会把"配置写坏了"
     * 也一并吞掉，让真正的配置错误静默通过。
     *
     * @return 自带配置返回 {@code true}
     */
    private boolean hasBundledConfig() {
        return getResource("config.yml") != null;
    }

    /**
     * 注销命令通道。
     *
     * <p>子类覆盖本方法时**必须调用 {@code super.onDisable()}**：入站 / 出站通道不注销的话，
     * 插件重载后同一个通道会堆积多个注册，一条请求被处理多次（命令被执行多次）。
     */
    @Override
    public void onDisable() {
        try {
            getServer().getMessenger()
                .unregisterIncomingPluginChannel(this, McTestkitProtocol.COMMAND_CHANNEL, commandListener);
            getServer().getMessenger()
                .unregisterOutgoingPluginChannel(this, McTestkitProtocol.COMMAND_CHANNEL);
        } catch (RuntimeException e) {
            // 收尾阶段抛异常会让停服流程报错，且它不影响测试结论——记录后继续
            getLogger().warning("[E2E] 注销命令通道失败：" + e.getMessage());
        }
    }

    /** 判 PASS：原子写结果文件 + 延迟关服（幂等）。 */
    protected final void pass(String message, Map<String, String> details) {
        finish(McTestkitResultWriter.STATUS_PASS, message, details);
    }

    /** 判 PASS（无明细键）。 */
    protected final void pass(String message) {
        pass(message, java.util.Collections.emptyMap());
    }

    /** 判 FAIL：原子写结果文件 + 延迟关服（幂等）。 */
    protected final void fail(String message) {
        finish(McTestkitResultWriter.STATUS_FAIL, message, java.util.Collections.emptyMap());
    }

    /** 判定是否已结束（消费方据此短路重复驱动 / 重复上报）。 */
    protected final boolean isFinished() {
        return finished.get();
    }

    /**
     * 登记一条**服务端侧断言**。
     *
     * <p>断言体在服务端线程上执行，直接读世界 / 实体 / 插件内部状态，返回布尔结论与原因。
     * 机器人侧只需发送 {@link McTestkitProtocol#assertionRequest(String)} 触发，
     * 不再解析任何聊天文本。
     *
     * <p>结论经既有的 {@link #pass} / {@link #fail} 落盘——因此幂等门、
     * 原子写出、"插件不得抢在机器人之前写 PASS"这些既有保障全部继续生效。
     *
     * @param name 断言名（与机器人触发时逐字一致）
     * @param body 断言体
     */
    protected final void registerServerAssertion(String name, McTestkitServerAssertions.Body body) {
        assertions.register(name, body);
    }

    /**
     * 登记一条<b>取证</b>（probe）能力：只回显结果，不判定、不写结果文件、不关服。
     *
     * <h2>为什么必须与断言分开（这是踩过的坑）</h2>
     *
     * 断言一旦返回 {@code pass}，基类就写结果文件并<b>关服</b>——那是判定的正确语义：
     * 结论落定即收尾。但如果把"只是想看一眼"的取证也注册成断言，它会：
     *
     * <ol>
     *   <li>抢先写出 {@code PASS}；</li>
     *   <li>关掉服务端；</li>
     *   <li>于是机器人随后上报的真实 {@code FAIL} 打在已关闭的服务端上，根本到不了。</li>
     * </ol>
     *
     * <p>结果就是<b>真实失败被自己的取证结论洗成通过</b>。真机上发生过：
     * 一个恒 PASS 的名字解析探针掩盖了"状态挂不上"的失败，连跑多次"全绿"里有假绿。
     * 这种假通过比直接失败危险得多——它看起来是好的。
     *
     * <p>因此取证走独立通道：结果只经聊天回显给触发者，判定权完全留在机器人/编排那一侧。
     *
     * @param name 取证名（与断言名共用命名空间，重名会被拒绝）
     * @param body 取证体：返回一段给人读的说明
     */
    protected final void registerProbe(String name, java.util.function.Supplier<String> body) {
        // 与 registerServerAssertion 对齐：空名 / 空体在登记期就拒绝，而不是等到机器人触发时
        // 才炸在 putIfAbsent 的 NPE 上（那会把"登记写错"伪装成"服务端异常"）。
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("取证名不得为空白——空名字无法被机器人触发，也就永远不会有结果");
        }
        if (body == null) {
            throw new IllegalArgumentException("取证体不得为 null——登记一条空取证等于承诺一个永远不会到来的结果");
        }
        // 与断言共用命名空间：handleAssertionChat 先查 probes 再走断言，若同名两边都登记成功，
        // 断言会被取证永久遮蔽——机器人发 E2E_ASSERT:<name> 却只收到取证回显，断言体次次不执行，
        // 表象是"断言永远超时"。故此处跨表查重，让冲突在登记期（onEnable）就暴露。
        if (assertions.find(name) != null) {
            throw new IllegalArgumentException(
                "取证名「" + name + "」与已登记的服务端断言重名——二者共用触发前缀，"
                    + "同名会让该断言永远不被执行（取证优先分流），因此直接拒绝");
        }
        if (probes.putIfAbsent(name, body) != null) {
            throw new IllegalArgumentException("取证名「" + name + "」已被登记，不允许重复登记");
        }
    }

    /** 已登记的取证名。 */
    protected final java.util.Set<String> probeNames() {
        return probes.keySet();
    }

    /**
     * 已登记的服务端断言名（供诊断与排错）。
     *
     * @return 断言名集合
     */
    protected final java.util.Set<String> serverAssertionNames() {
        return assertions.names();
    }

    /**
     * 取证表：只回显、不判定。与断言分开，见 {@link #registerProbe}。
     *
     * <p>用并发容器：登记发生在 onEnable，触发发生在聊天线程与主线程之间。
     */
    private final java.util.Map<String, java.util.function.Supplier<String>> probes =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 执行一条已登记的服务端断言并据此判定。
     *
     * <p>找不到该名字时**判 FAIL 并说明已登记了哪些**：静默忽略会让机器人一直等到超时，
     * 而超时的原因（名字拼错 / 桩没登记）与"断言真的没通过"完全不同。
     *
     * @param name 断言名
     */
    /**
     * 最近一次触发断言的玩家：用于把完成回执发回给<b>触发者</b>。
     *
     * <p>记玩家而不是广播：断言是点对点触发-应答，发给全服会让无关的机器人与玩家
     * 也收到控制消息，而控制协议本就该只走这条点对点的边。
     */
    private volatile Player assertionCaller;

    private void runServerAssertion(String name) {
        if (isFinished()) {
            // 已判定过：不再覆盖结论（幂等门在 finish 里也有，这里提前短路省掉一次执行）。
            return;
        }
        McTestkitServerAssertions.Body body = assertions.find(name);
        if (body == null) {
            fail("请求的服务端断言「" + name + "」未登记。已登记："
                + (serverAssertionNames().isEmpty() ? "（无）" : serverAssertionNames()));
            return;
        }
        if (body.isAsync()) {
            // 异步断言：它自己会等 I/O（读文件、读磁盘），在主线程上等会卡住整个 tick。
            // 结论回来后再**切回主线程**落定——写结果文件与关服都是主线程动作。
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                McTestkitServerAssertions.Outcome asyncOutcome;
                try {
                    asyncOutcome = body.run();
                } catch (RuntimeException e) {
                    String reason = "服务端断言「" + name + "」执行时抛出 "
                        + e.getClass().getSimpleName() + "：" + e.getMessage();
                    runOnMainThread(() -> fail(reason));
                    return;
                }
                runOnMainThread(() -> settleAssertion(name, asyncOutcome));
            });
            return;
        }
        McTestkitServerAssertions.Outcome outcome;
        try {
            outcome = body.run();
        } catch (RuntimeException e) {
            // 断言体抛异常时如实判失败并带上异常类型：把它咽掉会让"断言里写错了"表现为"断言没通过"。
            fail("服务端断言「" + name + "」执行时抛出 "
                + e.getClass().getSimpleName() + "：" + e.getMessage());
            return;
        }
        if (outcome == null) {
            fail("服务端断言「" + name + "」返回了 null —— 断言体必须给出结论，"
                + "返回空值无法与「没有结论」区分");
            return;
        }
        settleAssertion(name, outcome);
    }

    /**
     * 把一次断言结果落定：写结论、回执触发者。
     *
     * <p>抽取出来是因为同步与异步两条路径都要走这一段，而它们对
     * "先写结论、后发回执"的顺序要求是相同的。
     *
     * @param name    断言名
     * @param outcome 结果
     */
    private void settleAssertion(String name, McTestkitServerAssertions.Outcome outcome) {
        if (outcome == null) {
            fail("服务端断言「" + name + "」返回了 null —— 断言体必须给出结论，"
                + "返回空值无法与「没有结论」区分");
            return;
        }
        if (outcome.passed) {
            pass(outcome.message);
        } else {
            fail(outcome.message);
        }
        // 回执在结论落定**之后**发：先有结论再通知，否则机器人可能在结果文件
        // 尚未写出时就读到旧的/空的文件，把"还没写"误判成"没通过"。
        notifyAssertionDone(name, outcome.passed);
    }

    /**
     * 向触发者回报断言已完成。
     *
     * <p>只报"跑没跑 + 通过与否"，不重复结论正文——正文在结果文件里，
     * 两处各写一份必然漂移。
     *
     * @param name   断言名
     * @param passed 是否通过
     */
    private void notifyAssertionDone(String name, boolean passed) {
        Player caller = assertionCaller;
        if (caller == null || !caller.isOnline()) {
            return;
        }
        try {
            sendControlMessage(caller, McTestkitProtocol.assertionDone(name, passed));
        } catch (RuntimeException e) {
            // 回执只是可观测性，发不出去不该影响结论——结论已经写进结果文件了。
            getLogger().warning("[E2E] 断言完成回执发送失败（结论不受影响）：" + e.getMessage());
        }
    }

    /**
     * 冻结协议的聊天入口：机器人发 {@code E2E_ASSERT:<name>} 即执行对应断言。
     *
     * <p>由基类自己监听，消费方不必各写一遍——那样每加一个桩都要重复实现
     * 同一段前缀解析，而重复的那份迟早会漏掉某种情况。
     *
     * @param event 异步聊天事件
     */
    @EventHandler
    public final void onAssertionChat(AsyncPlayerChatEvent event) {
        handleAssertionChat(event.getPlayer(), event.getMessage());
    }

    /**
     * 聊天触发入口：机器人发 {@code E2E_ASSERT:<name>} 即执行对应断言。
     *
     * <p>为什么用聊天而不是命令：命令需要注册权限节点，而测试伴侣不该给服务端加可见命令面；
     * 聊天前缀已有成熟先例（{@code E2E_READY:} / {@code E2E_STRESS_RESULT:}），
     * 且机器人侧已经稳定使用这条通道。
     *
     * @param player 发送者
     * @param message 聊天内容
     * @return 是否已作为断言请求处理
     */
    protected final boolean handleAssertionChat(Player player, String message) {
        String prefix = McTestkitProtocol.ASSERTION_PREFIX;
        if (message == null || !message.startsWith(prefix)) {
            return false;
        }
        String name = message.substring(prefix.length()).trim();
        assertionCaller = player;
        // 取证优先分流：它是"只看一眼"，绝不能走到判定路径上（那会写 PASS 并关服，
        // 把机器人随后的真实结论吞掉）。协议前缀相同，行为截然不同。
        java.util.function.Supplier<String> probe = probes.get(name);
        if (probe != null) {
            getLogger().info("[E2E] 收到取证请求：" + name);
            runOnMainThread(() -> {
                String text;
                try {
                    text = probe.get();
                } catch (RuntimeException e) {
                    text = "取证执行抛出 " + e.getClass().getSimpleName() + "：" + e.getMessage();
                }
                sendControlMessage(player, McTestkitProtocol.probeResult(name, text));
            });
            return true;
        }
        getLogger().info("[E2E] 收到服务端断言请求：" + name);
        // 断言体要读世界对象，必须在归属线程执行（Folia 上尤其如此）。
        runOnMainThread(() -> runServerAssertion(name));
        return true;
    }

    /** 发送就绪信号：{@code E2E_READY:<scenario>}，通知机器人可开始驱动。 */
    protected final void sendReady(Player player) {
        String id = scenarioId == null ? "" : scenarioId;
        sendControlMessage(player, McTestkitProtocol.readyMessage(id));
    }

    /** 经聊天通道向机器人发送控制消息（冻结控制协议，docs/API.md §3.4）。 */
    protected final void sendControlMessage(Player player, String message) {
        getLogger().info("[E2E] 发送控制消息 -> " + player.getName() + ": " + message);
        player.sendMessage(message);
    }

    /**
     * 注册 console 命令通道（入站 + 出站）。
     *
     * <p><b>出站必须一起注册</b>：Bukkit 的 {@code sendPluginMessage} 会先校验
     * {@code isOutgoingChannelRegistered}，不注册则回执直接抛 {@code ChannelNotRegisteredException}。
     *
     * <p>注册失败只记 warn、不让 onEnable 抛出去：这条通道是新增能力，缺了它场景照常跑完，
     * 但**不能静默**——否则机器人侧只会等到超时，看不出是这里没注册成功。
     */
    private void registerCommandChannel() {
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, McTestkitProtocol.COMMAND_CHANNEL);
            getServer().getMessenger()
                .registerIncomingPluginChannel(this, McTestkitProtocol.COMMAND_CHANNEL, commandListener);
        } catch (RuntimeException e) {
            getLogger().warning("[E2E] 注册命令通道 " + McTestkitProtocol.COMMAND_CHANNEL
                + " 失败，FR-30 的 console 命令通道在本服不可用：" + e.getMessage());
        }
    }

    /**
     * 处理一条 console 命令请求，并把回执发回请求者。
     *
     * <h2>线程约定</h2>
     *
     * 入站 payload 回调发生在网络线程，而命令会读写世界对象（Folia 上跨线程直接抛线程检查异常、
     * Paper 的 {@code AsyncCatcher} 也会拦），因此**整段执行投到主线程**。
     * 回执也在主线程发（Bukkit 发包须主线程）。
     *
     * <h2>输出捕获的真实限制（不假装能拿到全部输出）</h2>
     *
     * 服务端把命令输出写给传入的 {@code CommandSender}，而 console 发送者的实现是直接
     * {@code System.out.println}，**方法内没有可供拦截的接缝**。想换成自定义包装 sender 去收
     * {@code sendMessage} 也不可行：Paper 的 {@code VanillaCommandWrapper.getListener} 只认
     * 实体 / 方块 / 远程控制台 / console / 代理 sender 这几种具体类型，遇到别的类型直接抛
     * {@code IllegalArgumentException}——那样连命令都跑不起来，属于为了输出而毁掉执行。
     *
     * <p>因此这里**只用真实的 console 发送者**，回执的 {@code output} 是诚实的执行结果说明：
     * 命令未被接受（含常见原因）或执行时抛出的异常类型与消息；命令成功时的实际业务输出
     * 仍只出现在服务端控制台日志里。{@code PASS} 只表示「命令被服务端接受并执行」，不表示
     * 「网络状态已符合预期」——后者正是 {@code E2E_ASSERT} 那类服务端断言要回答的问题。
     *
     * @param requester 触发者（回执发回给他；已离线时只记日志）
     * @param id        请求 id
     * @param raw       原始命令（可带前导 {@code /}）
     */
    private void runConsoleCommand(Player requester, String id, String raw) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        boolean passed;
        String output;
        if (command.trim().isEmpty()) {
            passed = false;
            output = "命令为空";
        } else {
            try {
                passed = getServer().dispatchCommand(getServer().getConsoleSender(), command);
                output = passed ? "命令已被服务端接受并执行" : "命令未被服务端接受（未知命令或无控制台权限）";
            } catch (RuntimeException e) {
                // 命令执行体自己抛出的异常：必须带上类型与消息，否则「失败」二字对排查毫无帮助
                passed = false;
                output = "命令执行时抛出 " + e.getClass().getSimpleName() + "：" + e.getMessage();
            }
        }
        // 超长输出会撞自定义 payload 的硬上限（超出即抛异常、回执发不出去），故截断并明说
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) + "…（回执超出插件消息上限，已截断）";
        }
        if (passed) {
            getLogger().info("[E2E] console 命令完成 -> " + requester.getName() + " id=" + id
                + " 命令=/" + command + " 结果=" + output);
        } else {
            getLogger().warning("[E2E] console 命令失败 -> " + requester.getName() + " id=" + id
                + " 命令=/" + command + " 结果=" + output);
        }
        sendCommandDone(requester, McTestkitProtocol.commandDone(id, passed, output));
    }

    /**
     * 经命令通道把回执发回请求者。
     *
     * <p>单个玩家发、不广播：控制流量只该走触发-应答这一条点对点的边。
     * 发送失败（玩家已离线 / 通道未注册）只记日志——此时回执无处可去，机器人侧会按超时收场。
     */
    private void sendCommandDone(Player requester, String message) {
        try {
            if (!requester.isOnline()) {
                getLogger().warning("[E2E] 命令回执无处发送，触发者已离线：" + message);
                return;
            }
            requester.sendPluginMessage(this, McTestkitProtocol.COMMAND_CHANNEL, message.getBytes(CHANNEL_CHARSET));
        } catch (RuntimeException e) {
            getLogger().warning("[E2E] 发送命令回执失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage());
        }
    }

    /** 延迟在主线程 / 全局区域执行（Folia 走 GlobalRegionScheduler.runDelayed，否则 Bukkit runTaskLater）。 */
    protected final void runLater(long delayTicks, Runnable task) {
        Object folia = resolveFoliaScheduler();
        if (folia != null) {
            // GlobalRegionScheduler.runDelayed(Plugin, Consumer<ScheduledTask>, long)；delay 须 >= 1
            invokeScheduler(folia, "runDelayed", this, (Consumer<Object>) ignored -> task.run(), Math.max(1L, delayTicks));
        } else {
            getServer().getScheduler().runTaskLater(this, task, delayTicks);
        }
    }

    /** 立即在主线程 / 全局区域执行（Folia 走 GlobalRegionScheduler.run，否则 Bukkit runTask）。 */
    protected final void runOnMainThread(Runnable task) {
        Object folia = resolveFoliaScheduler();
        if (folia != null) {
            // GlobalRegionScheduler.run(Plugin, Consumer<ScheduledTask>)
            invokeScheduler(folia, "run", this, (Consumer<Object>) ignored -> task.run());
        } else {
            getServer().getScheduler().runTask(this, task);
        }
    }

    /**
     * 把任务投递到<b>某个世界的归属线程</b>（Folia 上是该世界的区域线程，Paper 上是主线程）。
     *
     * <h2>为什么需要它（而不是直接用 {@link #runOnMainThread}）</h2>
     *
     * Folia 上"全局区域"和"某世界的区域"是两个不同的执行上下文。
     * 对世界做写入类操作（{@code World#save()}、读写区块/实体状态）必须在该世界的区域线程上，
     * 否则要么抛线程检查异常，要么直接拿到 {@code null}：
     *
     * <pre>
     * NullPointerException: Cannot read field "regioniser" because the return value of
     * "io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion()" is null
     * </pre>
     *
     * <p>这条异常真机出现过：桩在全局区域线程上调 {@code World#save()}，Paper 上正常，
     * Folia 上直接空指针。而它看起来像"桩写错了"，本质是**调度上下文用错了**。
     *
     * @param world 目标世界
     * @param task  要在该世界归属线程执行的任务
     */
    protected final void runOnWorldThread(org.bukkit.World world, Runnable task) {
        Object regionScheduler = resolveRegionScheduler();
        if (regionScheduler != null) {
            // 用 `execute(Plugin, Location, Runnable)`，不用 `run(Plugin, World, Consumer)`。
            //
            // 三条实测理由（folia-api 1.20.1 的真实签名，已用 javap 核对）：
            // 1. `execute` 是接口的**抽象方法**，`run(Plugin, World, …)` 只是 default 方法；
            //    `Class#getMethod` 在实现类上找 default 接口方法会落空。
            // 2. `run` 的抽象形态要求区块坐标 `(Plugin, World, int, int, Consumer)`，
            //    而这里表达的是"在某世界的线程上跑一下"，没有特定区块。
            // 3. `execute` 直接收 `Runnable`，不需要再包一层 Consumer。
            //
            // Location 取世界出生点：`execute(Plugin, Location, Runnable)` 会按该位置
            // 解析出所属区域，正是我们要的那条线程。
            invokeScheduler(regionScheduler, "execute", this, world.getSpawnLocation(), task);
        } else {
            // Paper：世界操作本来就在主线程，等价于直接投递。
            runOnMainThread(task);
        }
    }

    /**
     * 取区域调度器（仅 Folia 非 null）。
     *
     * <p>反射获取，编译期不依赖 Folia 专有类——桩要能同时跑在两种服务端上。
     *
     * @return 区域调度器；非 Folia 返回 {@code null}
     */
    private Object resolveRegionScheduler() {
        if (resolveFoliaScheduler() == null) {
            return null;
        }
        try {
            Object server = getServer();
            java.lang.reflect.Method m = server.getClass().getMethod("getRegionScheduler");
            return m.invoke(server);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("[E2E] 取不到 RegionScheduler：" + e.getMessage());
            return null;
        }
    }

    private void finish(String status, String message, Map<String, String> details) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (McTestkitResultWriter.STATUS_PASS.equals(status)) {
            getLogger().info("[E2E][PASS] " + message);
        } else {
            getLogger().severe("[E2E][FAIL] " + message);
        }
        try {
            new McTestkitResultWriter(resultFile).write(status, message, details);
        } catch (RuntimeException e) {
            // 结果文件是测试结论唯一真源，写失败必须出声（但不再抛给 onEnable 链路，避免掩盖场景本身）
            getLogger().severe("写入 E2E 结果文件失败：" + e.getMessage());
        }
        runOnMainThread(() -> getServer().shutdown());
    }

    /** 反射解析 Folia 全局区域调度器；非 Folia（Paper）返回 null。 */
    private Object resolveFoliaScheduler() {
        Object cached = foliaGlobalScheduler;
        if (cached != null) {
            return cached;
        }
        try {
            // RegionizedServer 是 Folia 专有类：存在即判定为 Folia 运行时（Paper 无此类）
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object resolved = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            foliaGlobalScheduler = resolved;
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射调用 Folia 调度器方法（避免编译期依赖 Folia 专有类型）。 */
    private static void invokeScheduler(Object scheduler, String method, Object... args) {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Plugin) {
                paramTypes[i] = Plugin.class;
            } else if (arg instanceof org.bukkit.Location) {
                // RegionScheduler.execute(Plugin, Location, Runnable) 的第二个参数。
                //
                // 漏掉这一支会让 Location 被当成 Consumer 去找签名，于是
                // `getMethod("execute", Plugin, Consumer, Consumer)` 永远找不到，
                // 报"反射调用 Folia 调度器失败"——而真正原因只是参数类型没覆盖到。
                paramTypes[i] = org.bukkit.Location.class;
            } else if (arg instanceof Runnable) {
                paramTypes[i] = Runnable.class;
            } else if (arg instanceof Long) {
                paramTypes[i] = Long.TYPE;
            } else {
                paramTypes[i] = Consumer.class;
            }
        }
        try {
            scheduler.getClass().getMethod(method, paramTypes).invoke(scheduler, args);
        } catch (Throwable t) {
            throw new IllegalStateException("反射调用 Folia 调度器失败：" + method, t);
        }
    }

    /**
     * 命令通道的入站监听器（FR-30，ADR-0027）：解出 {@code <id>:<command>} 并投主线程执行。
     *
     * <p>做成独立类而不是让基类直接实现 {@link PluginMessageListener}：基类已实现
     * {@link Listener}，两者方法签名无关但混在一起会让「注销时须传同一实例」这件事更难做对。
     */
    private final class CommandChannelListener implements PluginMessageListener {

        @Override
        public void onPluginMessageReceived(String channel, Player player, byte[] message) {
            if (!McTestkitProtocol.COMMAND_CHANNEL.equals(channel)) {
                return;
            }
            // 载荷按 UTF-8 解码（与机器人侧 Buffer.from(text, 'utf8') 对齐）
            String payload = new String(message, CHANNEL_CHARSET);
            String id = McTestkitProtocol.commandRequestId(payload);
            String command = McTestkitProtocol.commandRequestBody(payload);
            if (id == null || command == null) {
                // 不是命令请求（或 id 缺失）：不猜、不执行，只记日志——
                // 静默忽略会让机器人侧一直等到超时，看不出是载荷格式不对
                getLogger().warning("[E2E] 收到无法解析的命令请求，已忽略：" + payload);
                return;
            }
            // 回调多半在网络线程：命令会读写世界对象，必须切主线程（与 E2E_ASSERT 的线程约定一致）
            runOnMainThread(() -> runConsoleCommand(player, id, command));
        }
    }
}
