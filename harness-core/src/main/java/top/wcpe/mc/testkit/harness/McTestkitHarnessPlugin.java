package top.wcpe.mc.testkit.harness;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
 *   <li>调度：{@link #runLater(long, Runnable)} / {@link #runOnMainThread(Runnable)} 兼容
 *       Paper（Bukkit 调度器）与 Folia（GlobalRegionScheduler，反射调用、编译期不依赖 Folia 专有类）。</li>
 * </ul>
 *
 * <p>消费方继承本类，在 {@link #onHarnessEnabled()} 里驱动 / 判定自己的业务场景；
 * 监听自己的 Bukkit 事件注册到 {@code server.getPluginManager()} 即可。
 * 本类刻意**不含**任何业务逻辑与示例场景。
 */
public abstract class McTestkitHarnessPlugin extends JavaPlugin implements Listener {

    /** 编排下发的场景 id（未下发为 null；serve 空闲时为空闲保留 id）。 */
    protected final String scenarioId = McTestkitEnv.scenarioIdOrNull();

    /** 结果文件：env 下发优先（写到 verify 读取处），未下发回退 config.yml 的 result-file。 */
    protected File resultFile;

    /** 判定是否已结束（幂等门：防重复写结果文件 / 重复关服）。 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /** Folia 全局区域调度器（仅 Folia 运行时非 null；反射取，避免编译期依赖 Folia 专有类）。 */
    private volatile Object foliaGlobalScheduler;

    /**
     * 消费方业务启动钩子：在 onEnable 完成协议准备（场景 / 结果文件 / serve 判断）后调用。
     *
     * <p>serve 空闲模式下**不会被调用**（桩空闲，不驱动、不判定、不关服）。
     * 子类在此注册监听器、读取业务配置、驱动场景。
     */
    protected abstract void onHarnessEnabled();

    @Override
    public final void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        String fromEnv = McTestkitEnv.envOrNull(McTestkitEnv.RESULT_FILE);
        this.resultFile = fromEnv != null
            ? new File(fromEnv)
            : new File(getDataFolder(), getConfig().getString("result-file", "result.properties"));

        // serve 空闲：不注册监听、不启动业务，把服务端留给真人客户端手测（与旧模板行为一致）
        if (McTestkitEnv.isServeIdle()) {
            getLogger().info("[E2E] 持久手测模式（serve）：桩空闲，不驱动场景、不判定、不关服。");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        onHarnessEnabled();
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
}
