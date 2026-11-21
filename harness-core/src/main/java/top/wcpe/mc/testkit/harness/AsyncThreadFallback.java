package top.wcpe.mc.testkit.harness;

/**
 * 调度器不可用时的最后退路：用一段独立守护线程执行任务。
 *
 * <p><b>为什么单独成一个类</b>：这段逻辑不依赖 Bukkit / Folia 的任何类型，抽出来才能被
 * 纯单测覆盖。而 {@link McTestkitHarnessPlugin} 继承 {@code JavaPlugin}，paper-api 又是
 * {@code compileOnly}（运行期由真实服务端提供），测试类路径上根本没有它——把兜底留在
 * 插件类里就只有「编译通过」这一层保障，而这里最需要的恰恰是行为保障。
 *
 * <p><b>为什么必须有这条退路</b>：任务静默不执行会让结论永远不出现，外部只表现为
 * 「场景执行超时」，与真实原因（调度器解析失败、反射签名对不上、Folia 版本差异）完全脱节。
 * 宁可牺牲调度器提供的线程安全上下文，也不能让结论消失——这是本模块最不能接受的
 * 失败形态，因为它把可诊断的报错换成了无从下手的超时。
 */
final class AsyncThreadFallback {

    /** 兜底线程的默认线程名；出现在线程转储里时能直接认出这条退路。 */
    static final String DEFAULT_THREAD_NAME = "mc-testkit-async-fallback";

    private AsyncThreadFallback() {
    }

    /**
     * 起一段守护线程执行任务。
     *
     * <p>守护线程是刻意的：这条路径只在异常情况下走，不能让一个卡住的任务挂住 JVM 退出。
     *
     * @param task 待执行任务；非 null
     */
    static void run(Runnable task) {
        run(task, DEFAULT_THREAD_NAME);
    }

    /**
     * 起一段带指定线程名的守护线程执行任务。
     *
     * @param task 待执行任务；非 null
     * @param threadName 线程名，便于在线程转储里分辨是哪次兜底
     */
    static void run(Runnable task, String threadName) {
        Thread fallback = new Thread(task, threadName);
        fallback.setDaemon(true);
        fallback.start();
    }
}
