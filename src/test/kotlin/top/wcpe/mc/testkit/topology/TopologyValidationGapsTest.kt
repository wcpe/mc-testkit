package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 配置期校验的缺口回归（issue #22）。
 *
 * 这几条此前**完全没有校验**，而它们的失败点都远离根因：
 *
 * - 场景名：重名在 `tasks.register` 时抛英文错误；`a-b` 与 `aB` 经
 *   [top.wcpe.mc.testkit.task.McTestkitTaskNames.toTaskKey] 折名后撞任务名；空名让任务名与
 *   结果文件名退化；含路径分隔符让桩写到不存在的子目录。
 * - 端口范围：`port = 70000` / `0` / `-1` 配置期放行，直到起服 `bind` 失败——而
 *   `PortGuard` 会把它翻译成「已被占用」，给完全错误的排查方向。
 * - 大小写重名：`s1` 与 `S1` 在大小写不敏感文件系统上指向同一运行目录，互相清空。
 * - 单后端 `via`：代理没路由到该后端时静默通过，运行期 bot 被反复踢掉直到超时。
 *
 * 每一条都必须**在配置期**抛中文错误，且错误里带上出问题的那个名字。
 */
class TopologyValidationGapsTest {

    // ── 测试辅助：直接构造冻结 spec（与 TopologyResolverTest 同风格，无需 Project） ──

    private fun backend(name: String, port: Int? = null): BackendSpec =
        BackendSpec(name).apply { this.port = port }

    private fun proxy(name: String, port: Int? = null, vararg routesTo: String): ProxySpec =
        ProxySpec(name).apply {
            this.port = port
            routesTo(*routesTo)
        }

    private fun scenario(name: String, backend: String? = null, via: String? = null): ScenarioSpec =
        ScenarioSpec(name).apply {
            this.backend = backend
            this.via = via
        }

    private fun resolve(
        backends: List<BackendSpec> = emptyList(),
        proxies: List<ProxySpec> = emptyList(),
        scenarios: List<ScenarioSpec> = emptyList(),
    ): Topology = TopologyResolver.resolve(backends, proxies, scenarios)

    // ── 场景名：空名 / 非法字符 ──

    @Test
    @DisplayName("场景名为空白时应抛中文错误（空名会让任务名与结果文件名退化）")
    fun rejectsBlankScenarioName() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1")), scenarios = listOf(scenario("")))
        }
        assertTrue(ex.message!!.contains("场景名"), "报错须点明是场景名：${ex.message}")
    }

    @Test
    @DisplayName("场景名只有空白字符时同样拒绝")
    fun rejectsWhitespaceOnlyScenarioName() {
        assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1")), scenarios = listOf(scenario("   ")))
        }
    }

    @Test
    @DisplayName("场景名含 / 时应抛中文错误（会让桩写到不存在的子目录）")
    fun rejectsScenarioNameWithSlash() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1")), scenarios = listOf(scenario("a/b")))
        }
        assertTrue(ex.message!!.contains("场景"), "报错须点明是场景：${ex.message}")
        assertTrue(ex.message!!.contains("a/b"), "报错须带上出问题的名字：${ex.message}")
    }

    @Test
    @DisplayName("场景名含 \\ 时同样拒绝（Windows 路径分隔符）")
    fun rejectsScenarioNameWithBackslash() {
        assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1")), scenarios = listOf(scenario("a\\b")))
        }
    }

    // ── 场景名：重名 ──

    @Test
    @DisplayName("场景名精确重复时应抛中文错误（此前是 tasks.register 的英文异常）")
    fun rejectsDuplicateScenarioNames() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                scenarios = listOf(scenario("buy", backend = "s1"), scenario("buy", backend = "s1")),
            )
        }
        assertTrue(ex.message!!.contains("场景名"), "报错须点明是场景名：${ex.message}")
        assertTrue(ex.message!!.contains("buy"), "报错须带上重复的名字：${ex.message}")
    }

    @Test
    @DisplayName("场景名大小写不同也应视为重复（同名场景共用结果文件，会互相覆盖判定）")
    fun rejectsScenarioNamesDifferingOnlyByCase() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                scenarios = listOf(scenario("buy", backend = "s1"), scenario("BUY", backend = "s1")),
            )
        }
        assertTrue(ex.message!!.contains("场景名"), "报错须点明是场景名：${ex.message}")
    }

    @Test
    @DisplayName("场景名折成任务名后冲突时应抛中文错误（a-b 与 aB 都折成 AB）")
    fun rejectsScenarioNamesCollidingAfterFolding() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                scenarios = listOf(scenario("a-b", backend = "s1"), scenario("aB", backend = "s1")),
            )
        }
        // 折名冲突的错误文案与「精确重复」不同：它要说清两者折成了同一个任务名
        assertTrue(
            ex.message!!.contains("任务名") || ex.message!!.contains("折"),
            "报错须说明是折成任务名后撞名：${ex.message}",
        )
    }

    // ── 端口范围 ──

    @Test
    @DisplayName("后端端口超出 65535 时应抛中文错误（而非等到 bind 失败被译成「已被占用」）")
    fun rejectsBackendPortAboveRange() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1", port = 70000)))
        }
        assertTrue(ex.message!!.contains("65535") || ex.message!!.contains("端口"), "报错须点明端口问题：${ex.message}")
    }

    @Test
    @DisplayName("后端端口为 0 时应拒绝（无法 bind）")
    fun rejectsBackendPortZero() {
        assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1", port = 0)))
        }
    }

    @Test
    @DisplayName("后端端口为负数时应拒绝")
    fun rejectsBackendPortNegative() {
        assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1", port = -1)))
        }
    }

    @Test
    @DisplayName("代理端口超出范围时同样拒绝")
    fun rejectsProxyPortAboveRange() {
        assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(proxy("wf", port = 70000, routesTo = arrayOf("s1"))),
            )
        }
    }

    @Test
    @DisplayName("边界端口 1 与 65535 应放行（校验不能过度收紧）")
    fun acceptsBoundaryPorts() {
        val topology = resolve(
            backends = listOf(backend("s1", port = 1), backend("s2", port = 65535)),
        )
        assertEquals(1, topology.backends.first { it.name == "s1" }.port)
        assertEquals(65535, topology.backends.first { it.name == "s2" }.port)
    }

    // ── 后端 / 代理名大小写重名 ──

    @Test
    @DisplayName("后端名仅大小写不同时应拒绝（大小写不敏感文件系统上指向同一运行目录）")
    fun rejectsBackendNamesDifferingOnlyByCase() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1"), backend("S1")))
        }
        assertTrue(ex.message!!.contains("后端"), "报错须点明是后端：${ex.message}")
    }

    @Test
    @DisplayName("代理名仅大小写不同时应拒绝")
    fun rejectsProxyNamesDifferingOnlyByCase() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(
                    proxy("wf", routesTo = arrayOf("s1")),
                    proxy("WF", routesTo = arrayOf("s1")),
                ),
            )
        }
        assertTrue(ex.message!!.contains("代理"), "报错须点明是代理：${ex.message}")
    }

    // ── 单后端 via 路由 ──

    @Test
    @DisplayName("单后端场景的 via 未路由到该后端时应抛中文错误")
    fun rejectsSingleBackendViaNotRoutingToBackend() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(proxy("wf", routesTo = arrayOf("s2"))),
                scenarios = listOf(scenario("x", backend = "s1", via = "wf")),
            )
        }
        // 不校验的后果是 bot 被代理以「找不到默认服 / 回退服」反复踢掉直到超时
        assertTrue(
            ex.message!!.contains("路由") || ex.message!!.contains("via") || ex.message!!.contains("wf"),
            "报错须指向路由问题：${ex.message}",
        )
    }

    @Test
    @DisplayName("单后端场景的 via 正确路由到该后端时应放行（不能过度收紧）")
    fun acceptsSingleBackendViaRoutingToBackend() {
        // 校验通过即不抛；Topology 只含后端与代理，场景仅参与校验
        val topology = resolve(
            backends = listOf(backend("s1")),
            proxies = listOf(proxy("wf", routesTo = arrayOf("s1"))),
            scenarios = listOf(scenario("x", backend = "s1", via = "wf")),
        )
        assertEquals(1, topology.backends.size)
    }

    @Test
    @DisplayName("单后端场景不设 via 时放行（未走代理是合法用法）")
    fun acceptsSingleBackendWithoutVia() {
        val topology = resolve(
            backends = listOf(backend("s1")),
            scenarios = listOf(scenario("x", backend = "s1")),
        )
        assertEquals(1, topology.backends.size)
    }
}
