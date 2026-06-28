package top.wcpe.mc.testkit

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ServeSpec
import top.wcpe.mc.testkit.topology.TopologyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 持久手测（serve，FR-17，ADR-0011）契约与解析单元测试：DSL 记录 serve 声明、任务命名约定、
 * 保留哨兵场景 id，及 TopologyResolver 对 serve 引用的配置期校验（纯函数，不联网 / 不起进程）。
 */
class ServeContractTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    fun `DSL 记录 serve 声明（后端 代理引用）`() {
        val ext = extension()
        ext.backend("s1")
        ext.proxy("wf") { routesTo("s1") }
        ext.serve("dev") {
            backend = "s1"
            via = "wf"
        }
        assertEquals(1, ext.declaredServes.size)
        val dev = ext.declaredServes.single()
        assertEquals("dev", dev.name)
        assertEquals("s1", dev.backend)
        assertEquals("wf", dev.via)
    }

    @Test
    fun `serve 默认 backend 与 via 为 null（直连默认后端）`() {
        val ext = extension()
        ext.serve("dev")
        val dev = ext.declaredServes.single()
        assertNull(dev.backend)
        assertNull(dev.via)
    }

    @Test
    fun `serve 任务命名约定（PascalCase 中缀）`() {
        assertEquals("serveDev", McTestkitTaskNames.serve("dev"))
        assertEquals("serveMyDev", McTestkitTaskNames.serve("my-dev"))
        assertEquals("stopDevServe", McTestkitTaskNames.stopServe("dev"))
        assertEquals("stopMyDevServe", McTestkitTaskNames.stopServe("my-dev"))
    }

    @Test
    fun `保留哨兵场景 id 契约固定`() {
        assertEquals("__mc_testkit_serve__", McTestkitContract.SERVE_SCENARIO_ID)
    }

    @Test
    fun `serve 合法声明（直连 + 经代理）解析通过`() {
        // 不抛异常即通过
        TopologyResolver.resolve(
            backends = listOf(BackendSpec("s1").apply { port = 25565 }),
            proxies = listOf(
                ProxySpec("wf").apply {
                    port = 25577
                    routesTo("s1")
                },
            ),
            scenarios = emptyList(),
            serves = listOf(
                ServeSpec("direct").apply { backend = "s1" },
                ServeSpec("viaproxy").apply {
                    backend = "s1"
                    via = "wf"
                },
            ),
        )
    }

    @Test
    fun `serve 引用不存在后端配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("s1").apply { port = 25565 }),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(ServeSpec("dev").apply { backend = "nope" }),
            )
        }
        assertTrue(ex.message!!.contains("不存在"), "应报后端不存在：${ex.message}")
    }

    @Test
    fun `serve 引用不存在代理配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("s1").apply { port = 25565 }),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backend = "s1"
                        via = "nope"
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("代理") && ex.message!!.contains("不存在"), "应报代理不存在：${ex.message}")
    }

    @Test
    fun `serve via 未路由到目标后端配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(
                    BackendSpec("s1").apply { port = 25565 },
                    BackendSpec("s2").apply { port = 25566 },
                ),
                proxies = listOf(
                    ProxySpec("wf").apply {
                        port = 25577
                        routesTo("s1")
                    },
                ),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backend = "s2"
                        via = "wf"
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("未路由"), "应报代理未路由到目标后端：${ex.message}")
    }

    @Test
    fun `serve 名重复配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("s1").apply { port = 25565 }),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply { backend = "s1" },
                    ServeSpec("dev").apply { backend = "s1" },
                ),
            )
        }
        assertTrue(ex.message!!.contains("重复"), "应报 serve 名重复：${ex.message}")
    }

    @Test
    fun `serve 无后端可用配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = emptyList(),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(ServeSpec("dev")),
            )
        }
        assertTrue(ex.message!!.contains("无可用后端"), "应报无可用后端：${ex.message}")
    }

    // ── 集群 serve（FR-18）──

    @Test
    fun `集群 serve DSL 记录 backends 与 via`() {
        val ext = extension()
        ext.backend("s1")
        ext.backend("s2")
        ext.proxy("wf") { routesTo("s1", "s2") }
        ext.serve("dev") {
            backends("s1", "s2")
            via = "wf"
        }
        val dev = ext.declaredServes.single()
        assertEquals(listOf("s1", "s2"), dev.backendRefs)
        assertEquals("wf", dev.via)
    }

    @Test
    fun `集群 serve 合法声明解析通过`() {
        TopologyResolver.resolve(
            backends = listOf(
                BackendSpec("s1").apply { port = 25565 },
                BackendSpec("s2").apply { port = 25566 },
            ),
            proxies = listOf(
                ProxySpec("wf").apply {
                    port = 25577
                    routesTo("s1", "s2")
                },
            ),
            scenarios = emptyList(),
            serves = listOf(
                ServeSpec("dev").apply {
                    backends("s1", "s2")
                    via = "wf"
                },
            ),
        )
    }

    @Test
    fun `集群 serve 缺 via 配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(
                    BackendSpec("s1").apply { port = 25565 },
                    BackendSpec("s2").apply { port = 25566 },
                ),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(ServeSpec("dev").apply { backends("s1", "s2") }),
            )
        }
        assertTrue(ex.message!!.contains("必须经代理") || ex.message!!.contains("via"), "集群 serve 缺 via 应中文报错：${ex.message}")
    }

    @Test
    fun `集群 serve via 未覆盖全部后端配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(
                    BackendSpec("s1").apply { port = 25565 },
                    BackendSpec("s2").apply { port = 25566 },
                ),
                proxies = listOf(
                    ProxySpec("wf").apply {
                        port = 25577
                        routesTo("s1")
                    },
                ),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backends("s1", "s2")
                        via = "wf"
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("未路由"), "集群 serve via 未覆盖全部后端应中文报错：${ex.message}")
    }

    @Test
    fun `集群 serve 与单后端 backend 并用配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(
                    BackendSpec("s1").apply { port = 25565 },
                    BackendSpec("s2").apply { port = 25566 },
                ),
                proxies = listOf(
                    ProxySpec("wf").apply {
                        port = 25577
                        routesTo("s1", "s2")
                    },
                ),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backend = "s1"
                        backends("s1", "s2")
                        via = "wf"
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("不可同时"), "集群 serve 与 backend 并用应中文报错：${ex.message}")
    }

    // ── serve 人机混场 bot（FR-19）──

    @Test
    fun `serve DSL 记录 bot 声明`() {
        val ext = extension()
        ext.backend("s1")
        ext.serve("dev") {
            backend = "s1"
            bot {
                username = "Filler"
                action = "idle-walk"
            }
        }
        val dev = ext.declaredServes.single()
        assertEquals(1, dev.botSpecs.size)
        assertEquals("Filler", dev.botSpecs.single().username)
        assertEquals("idle-walk", dev.botSpecs.single().action)
    }

    @Test
    fun `serve 多匿名 bot 配置期中文报错`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("s1").apply { port = 25565 }),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backend = "s1"
                        bot { username = "A" }
                        bot { username = "B" }
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("唯一角色名") || ex.message!!.contains("匿名"), "serve 多匿名 bot 应中文报错：${ex.message}")
    }

    @Test
    fun `serve bot count 必须为正`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("s1").apply { port = 25565 }),
                proxies = emptyList(),
                scenarios = emptyList(),
                serves = listOf(
                    ServeSpec("dev").apply {
                        backend = "s1"
                        bot {
                            username = "A"
                            count = 0
                        }
                    },
                ),
            )
        }
        assertTrue(ex.message!!.contains("count"), "serve bot count<1 应中文报错：${ex.message}")
    }
}
