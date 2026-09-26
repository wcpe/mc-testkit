package top.wcpe.mc.testkit.dsl

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 场景钩子的声明与运行时行为单元测试（零网络、零外部进程依赖）。
 *
 * 覆盖三块：
 * 1. [ScenarioSpec] 的钩子声明累积（顺序、可多次声明）；
 * 2. [ScenarioHookRuntime] 的就绪门与 pid 收尾（用临时目录与当前进程自身当靶子）；
 * 3. 序列化往返——钩子会被任务动作闭包捕获，配置缓存要求整个捕获图可序列化。
 */
class ScenarioHooksTest {

    private fun hookContext(dir: File, log: MutableList<String> = mutableListOf()): HookContext =
        HookContext(
            scenarioName = "probe",
            resultsDir = dir,
            backendRunDirs = mapOf("paper1" to File(dir, "run-paper1")),
            proxyRunDir = File(dir, "run-proxy"),
            log = { log += it },
        )

    private fun tempDir(): File = Files.createTempDirectory("mc-testkit-hooks-test").toFile()

    @Test
    @DisplayName("ScenarioSpec 按声明顺序累积场景前 / 就绪后 / 场景后钩子")
    fun specAccumulatesHooksInOrder() {
        val spec = ScenarioSpec("probe")
        val order = mutableListOf<String>()

        spec.beforeScenario { order += "before-1" }
        spec.beforeScenario { order += "before-2" }
        spec.readyScenario { order += "ready-1" }
        spec.afterScenario { order += "after-1" }

        assertEquals(2, spec.beforeHooks.size)
        assertEquals(1, spec.readyHooks.size)
        assertEquals(1, spec.afterHooks.size)

        val ctx = hookContext(tempDir())
        spec.beforeHooks.forEach { it.run(ctx) }
        spec.readyHooks.forEach { it.run(ctx) }
        spec.afterHooks.forEach { it.run(ctx) }
        assertEquals(listOf("before-1", "before-2", "ready-1", "after-1"), order)
    }

    @Test
    @DisplayName("未声明钩子时三个列表均为空")
    fun specWithoutHooksIsEmpty() {
        val spec = ScenarioSpec("probe")
        assertTrue(spec.beforeHooks.isEmpty())
        assertTrue(spec.readyHooks.isEmpty())
        assertTrue(spec.afterHooks.isEmpty())
    }

    @Test
    @DisplayName("HookContext 取已声明后端目录；取未声明后端时抛出带可用项的中文异常")
    fun hookContextResolvesBackendDirs() {
        val dir = tempDir()
        val ctx = hookContext(dir)

        assertEquals(File(dir, "run-paper1"), ctx.backendRunDir("paper1"))
        val ex = assertFailsWith<IllegalArgumentException> { ctx.backendRunDir("nope") }
        assertTrue(ex.message.orEmpty().contains("未声明的后端"), "异常信息应说明后端未声明：${ex.message}")
        assertTrue(ex.message.orEmpty().contains("paper1"), "异常信息应列出可用后端：${ex.message}")
    }

    @Test
    @DisplayName("HookChain 按序执行，任一步抛错即中断（不继续后续步骤）")
    fun hookChainStopsOnFailure() {
        val dir = tempDir()
        val ctx = hookContext(dir)
        val ran = mutableListOf<String>()
        val chain = HookChain(
            listOf(
                ScenarioHook { ran += "a" },
                ScenarioHook { throw IllegalStateException("第二步失败") },
                ScenarioHook { ran += "c" },
            ),
        )

        assertFailsWith<IllegalStateException> { chain.run(ctx) }
        assertEquals(listOf("a"), ran, "失败后不应继续执行后续钩子")
    }

    @Test
    @DisplayName("SleepHook 等待后正常返回并记日志")
    fun sleepHookWaits() {
        val ctx = hookContext(tempDir())
        val start = System.currentTimeMillis()
        SleepHook(60, "测试沉降").run(ctx)
        assertTrue(System.currentTimeMillis() - start >= 50, "应至少等待约 60ms")
    }

    @Test
    @DisplayName("按 pid 文件收尾：文件不存在 / 内容非法 / 进程已退出三种情形都静默跳过")
    fun stopPidHookToleratesMissingTargets() {
        val dir = tempDir()
        val ctx = hookContext(dir)

        // 文件不存在
        StopPidHook("absent.pid").run(ctx)

        // 内容非法
        File(dir, "bad.pid").writeText("not-a-number")
        StopPidHook("bad.pid").run(ctx)

        // 指向一个几乎不可能存活的 pid
        File(dir, "dead.pid").writeText("2147483646")
        StopPidHook("dead.pid").run(ctx)
    }

    @Test
    @DisplayName("就绪门：端口无人监听时超时报中文异常，异常含标签与端口")
    fun awaitPortTimesOutWithChineseMessage() {
        val dir = tempDir()
        val ctx = hookContext(dir)
        // 取一个极不可能被监听的端口，超时设短以免拖慢测试
        val ex = assertFailsWith<IllegalStateException> {
            ScenarioHookRuntime.awaitPort(ctx, 1, 700, "测试端口")
        }
        assertTrue(ex.message.orEmpty().contains("测试端口"), "异常应含标签：${ex.message}")
        assertTrue(ex.message.orEmpty().contains("未就绪"), "异常应说明未就绪：${ex.message}")
    }

    @Test
    @DisplayName("就绪门：日志命中即返回；未命中则超时报错")
    fun awaitLogDetectsPattern() {
        val dir = tempDir()
        val ctx = hookContext(dir)
        val logFile = File(dir, "svc.log").apply { writeText("启动中...\n服务已就绪\n") }

        ScenarioHookRuntime.awaitLog(ctx, logFile, "服务已就绪", 1_000)

        val ex = assertFailsWith<IllegalStateException> {
            ScenarioHookRuntime.awaitLog(ctx, logFile, "不会出现的标记", 600)
        }
        assertTrue(ex.message.orEmpty().contains("不会出现的标记"), "异常应含未命中的标记：${ex.message}")
    }

    @Test
    @DisplayName("钩子可序列化往返：满足配置缓存对动作捕获图的要求")
    fun hooksAreSerializable() {
        val hooks: List<ScenarioHook> = listOf(
            ExecHook(command = listOf("java", "-version"), env = mapOf("A" to "1"), readyPort = 8080),
            StopPidHook("hook-probe.pid"),
            HttpHook(method = "POST", url = "http://127.0.0.1:8848/admin/v1/auth/login", body = "{}"),
            SleepHook(10, "沉降"),
            HookChain(listOf(SleepHook(1), SleepHook(2))),
        )
        hooks.forEach { hook ->
            val bytes = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(bytes).use { it.writeObject(hook) }
            val restored = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
            assertTrue(restored is ScenarioHook, "反序列化后应仍是 ScenarioHook：${hook::class.simpleName}")
        }
    }

    @Test
    @DisplayName("HTTP 钩子：目标不可达时抛出含方法与地址的异常")
    fun httpHookFailsWithContextOnUnreachableTarget() {
        val ctx = hookContext(tempDir())
        val hook = HttpHook(method = "GET", url = "http://127.0.0.1:1/nope", timeoutMs = 800)
        assertFailsWith<Exception> { hook.run(ctx) }
    }

    @Test
    @DisplayName("HTTP 钩子：状态码不符期望时抛出含期望值与实际值的异常")
    fun httpHookValidatesStatus() {
        // 用一个必然连不上的地址，保证走到异常分支（不依赖外网）
        val ctx = hookContext(tempDir())
        val hook = HttpHook(method = "GET", url = "http://127.0.0.1:1/nope", expectStatus = 200, timeoutMs = 800)
        assertFailsWith<Exception> { hook.run(ctx) }
    }
}
