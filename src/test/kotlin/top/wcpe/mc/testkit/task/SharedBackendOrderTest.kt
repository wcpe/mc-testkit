package top.wcpe.mc.testkit.task

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.McTestkitPlugin
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.topology.TopologyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「共用同一后端的场景串行」（ADR-0026）顺序边计算的单元测试。
 *
 * 被测对象是纯函数 [sharedBackendOrderEdges] 与 [scenarioRuntimeTaskNames]：给定拓扑与场景声明顺序，
 * 算出应挂哪些 `mustRunAfter` 边。纯函数便于穷举，也避免用 TestKit 去间接推断顺序（`mustRunAfter`
 * 是只写 API，读不回来；`--dry-run` 的输出顺序也不反映它）。
 */
class SharedBackendOrderTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    @DisplayName("两个场景共用同一后端时应产生跨场景串行边与机器人先后约束")
    fun chainScenariosSharingOneBackend() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.scenario("first") { backend = "s1" }
        ext.scenario("second") { backend = "s1" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        // 后声明场景的每个运行期任务都要排在前一个场景全部运行期任务之后
        assertEquals(
            scenarioRuntimeTaskNames(ext.declaredScenarios[0]),
            edges["e2eSecond"],
            "第二个场景的入口任务应排在前一个场景全部运行期任务之后",
        )
        // prepare 是实际踩踏点：它既排在前一个场景的 prepare 之后，也排除其运行期任务之后
        assertTrue(
            edges["prepareE2eSecond"].orEmpty().containsAll(scenarioRuntimeTaskNames(ext.declaredScenarios[0])),
            "后一个场景的 prepare 不应与前一个场景任何一步重叠，实际：${edges["prepareE2eSecond"]}",
        )
        // 后一个场景的机器人必须等前一个场景整段结束，否则会先超时退出
        assertEquals(
            scenarioRuntimeTaskNames(ext.declaredScenarios[0]),
            edges["launchSecondBot"],
            "后一个场景的机器人应排在前一个场景全部运行期任务之后",
        )
    }

    @Test
    @DisplayName("首个场景的机器人不应被跨场景边约束，只需等本场景 prepare")
    fun leaveFirstScenarioBotUnconstrained() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.scenario("first") { backend = "s1" }
        ext.scenario("second") { backend = "s1" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        // 首个场景的机器人等自己 prepare 已由 dependsOn 保证，无需 mustRunAfter；更不该被挂到后一个场景上
        assertTrue(
            edges["launchFirstBot"].isNullOrEmpty(),
            "首个场景的机器人无需跨场景串行边，实际：${edges["launchFirstBot"]}",
        )
    }

    @Test
    @DisplayName("场景分属不同后端时不应产生任何顺序约束")
    fun keepDistinctBackendsUnconstrained() {
        val ext = extension()
        ext.backend("alpha") { version = "1.20.1" }
        ext.backend("beta") { version = "1.20.1" }
        ext.scenario("onAlpha") { backend = "alpha" }
        ext.scenario("onBeta") { backend = "beta" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        assertTrue(
            edges.isEmpty(),
            "不同后端有独立目录与端口，不应被串行，实际：$edges",
        )
    }

    @Test
    @DisplayName("三个场景共用同一后端时应串联成一条完整链")
    fun chainThreeScenariosAlongOneBackend() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.scenario("a") { backend = "s1" }
        ext.scenario("b") { backend = "s1" }
        ext.scenario("c") { backend = "s1" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)
        val declared = ext.declaredScenarios

        assertEquals(scenarioRuntimeTaskNames(declared[0]), edges["e2eB"], "b 应排在 a 之后")
        assertEquals(scenarioRuntimeTaskNames(declared[1]), edges["e2eC"], "c 应排在 b 之后")
    }

    @Test
    @DisplayName("未显式声明 backend 的场景应归到首个后端并参与串行")
    fun groupImplicitBackendScenarios() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        // 两个场景都不写 backend = ，按契约取首个后端
        ext.scenario("x")
        ext.scenario("y")

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        assertTrue(
            edges["e2eY"].orEmpty().contains("e2eX"),
            "未声明后端的场景同属首个后端，应串行，实际：${edges["e2eY"]}",
        )
    }

    @Test
    @DisplayName("经代理场景应按其路由到的后端归组，且经代理入口任务在链上")
    fun groupViaProxyScenariosByRoutedBackend() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.proxy("wf") { routesTo("s1") }
        ext.scenario("direct") { backend = "s1" }
        ext.scenario("throughProxy") { via = "wf" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        assertTrue(
            edges["e2eThroughProxy"].orEmpty().contains("e2eDirect"),
            "经代理的场景与其路由到的后端共用目录/端口，应串行，实际：${edges["e2eThroughProxy"]}",
        )
        // 经代理场景的直连入口 e2e<Key> 与经代理入口 e2e<Key>Via<Proxy> 写同一运行目录，两者都要在链上
        assertTrue(
            edges["e2eThroughProxyViaWf"].orEmpty().contains("e2eDirect"),
            "经代理入口任务应排在前一个场景之后，实际：${edges["e2eThroughProxyViaWf"]}",
        )
    }

    @Test
    @DisplayName("单场景不应产生任何顺序约束")
    fun leaveSingleScenarioUnconstrained() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.scenario("only") { backend = "s1" }

        val edges = sharedBackendOrderEdges(TopologyResolver.resolve(ext), ext.declaredScenarios)

        assertTrue(edges.isEmpty(), "单场景无需串行，实际：$edges")
    }

    @Test
    @DisplayName("运行期任务名应覆盖 prepare、机器人、直连、带机器人、集群与压测")
    fun coverAllRuntimeTaskKinds() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.scenario("demo") { backend = "s1" }

        val names = scenarioRuntimeTaskNames(ext.declaredScenarios[0])

        assertEquals(
            listOf(
                McTestkitTaskNames.prepare("demo"),
                McTestkitTaskNames.launchBot("demo"),
                McTestkitTaskNames.verify("demo"),
                McTestkitTaskNames.withBot("demo"),
                McTestkitTaskNames.cluster("demo"),
                McTestkitTaskNames.stress("demo"),
            ),
            names,
        )
    }

    @Test
    @DisplayName("声明 via 的场景应额外把经代理入口任务列入运行期任务")
    fun includeViaProxyEntryTask() {
        val ext = extension()
        ext.backend("s1") { version = "1.20.1" }
        ext.proxy("wf") { routesTo("s1") }
        ext.scenario("proxied") {
            backend = "s1"
            via = "wf"
        }

        val names = scenarioRuntimeTaskNames(ext.declaredScenarios[0])

        assertTrue(
            McTestkitTaskNames.verifyVia("proxied", "wf") in names,
            "经代理入口任务必须在运行期任务集内，实际：$names",
        )
        // 未声明 via 时不应凭空多出经代理入口任务
        val extDirect = extension()
        extDirect.backend("s1") { version = "1.20.1" }
        extDirect.scenario("plain") { backend = "s1" }
        assertTrue(
            scenarioRuntimeTaskNames(extDirect.declaredScenarios[0]).none { "Via" in it },
            "未声明 via 时不应出现经代理入口任务",
        )
    }
}
