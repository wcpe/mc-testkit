package top.wcpe.mc.testkit.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度器兜底的契约。
 *
 * <p>这组测试钉住一个反复出现的静默失效形态：<b>任务没被执行，外部只看到超时</b>。
 * 它不抛给调用方、不判任何断言失败，只让结论永远不出现——排查时只能看到
 * 「场景执行超时」，与真正的原因（调度器解析失败、反射签名对不上、Folia 版本差异）
 * 完全脱节。
 *
 * <p>回归背景：{@code McTestkitHarnessPlugin#runAsync} 原先只把 Bukkit 那条路包在
 * try 里，Folia 的反射分支在 try 之外。反射签名对不上时 {@code invokeScheduler} 抛
 * {@code IllegalStateException} 一路抛给调用方，异步断言体一次都不执行，
 * 一次 {@code fail()} 都不会走——正是该方法要消灭的形态，却漏了一半。
 *
 * <p>所以兜底被抽到 {@link AsyncThreadFallback}：它不依赖 Bukkit 的任何类型，
 * 才能在纯单测里被真正验证。插件类继承 {@code JavaPlugin}，而 paper-api 是
 * {@code compileOnly}，测试类路径上没有它。
 */
class AsyncThreadFallbackTest {

    /** 等任务真正跑起来的上限；兜底线程是异步启动的，不能假设它立刻完成。 */
    private static final long AWAIT_SECONDS = 5;

    @Test
    @DisplayName("兜底确实会执行任务（不让结论静默消失）")
    void runsTask() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AsyncThreadFallback.run(ran::countDown);

        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS), "兜底线程必须真的执行任务");
    }

    @Test
    @DisplayName("兜底线程是守护线程（不阻止 JVM 退出）")
    void isDaemon() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        AsyncThreadFallback.run(() -> {
            daemon.set(Thread.currentThread().isDaemon());
            ran.countDown();
        });

        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, daemon.get(), "兜底线程必须是守护线程，否则卡住的任务会挂住 JVM 退出");
    }

    @Test
    @DisplayName("默认线程名可用于在线程转储里认出这条退路")
    void usesDefaultThreadName() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        AsyncThreadFallback.run(() -> {
            name.set(Thread.currentThread().getName());
            ran.countDown();
        });

        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(AsyncThreadFallback.DEFAULT_THREAD_NAME, name.get());
    }

    @Test
    @DisplayName("可指定线程名（同一次构建里区分不同兜底来源）")
    void usesGivenThreadName() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        AsyncThreadFallback.run(() -> {
            name.set(Thread.currentThread().getName());
            ran.countDown();
        }, "mc-testkit-async-assertions");

        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("mc-testkit-async-assertions", name.get());
    }

    @Test
    @DisplayName("任务抛异常不影响兜底本身的启动（异常只终结该任务）")
    void taskFailureDoesNotBreakFallback() throws InterruptedException {
        CountDownLatch attempted = new CountDownLatch(1);
        AsyncThreadFallback.run(() -> {
            attempted.countDown();
            throw new IllegalStateException("模拟断言体抛错");
        });

        assertTrue(attempted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "任务至少要被执行到");
    }

    @Test
    @DisplayName("连续兜底互不干扰（每次都是独立线程）")
    void runsConcurrently() throws InterruptedException {
        int count = 8;
        CountDownLatch allRan = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            AsyncThreadFallback.run(allRan::countDown);
        }

        assertTrue(allRan.await(AWAIT_SECONDS, TimeUnit.SECONDS), "不应因为并发兜底而丢失任何一次执行");
    }
}
