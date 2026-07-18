package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.topology.TopologyResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FR-20 节点资源解析、环境合并与 staging 单元测试。 */
class NodeRuntimeInjectionTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `envOrPath 非空环境变量优先且相对路径相对项目目录解析`() {
        val literalJar = file("literal/proxy-sentinel.jar")
        val environmentJar = file("environment/proxy-sentinel.jar")
        literalJar.writeText("literal-sentinel")
        environmentJar.writeText("environment-sentinel")
        val proxy = resolvedProxy {
            plugin("PROXY_PLUGIN_SENTINEL")
        }

        val resources = resolveProxyRuntimeResources(projectDir, proxy) { name ->
            if (name == "PROXY_PLUGIN_SENTINEL") "environment/proxy-sentinel.jar" else null
        }

        assertEquals(environmentJar.canonicalFile, resources.plugins.single().canonicalFile)
    }

    @Test
    fun `envOrPath 环境变量空值时回退声明路径`() {
        val template = file("proxy-template-sentinel").apply { mkdirs() }
        val proxy = resolvedProxy {
            templateDirectory("proxy-template-sentinel")
        }

        val resources = resolveProxyRuntimeResources(projectDir, proxy) { "" }

        assertEquals(template.canonicalFile, resources.templateDirectory?.canonicalFile)
    }

    @Test
    fun `后端节点模板优先且未声明时兼容旧全局模板`() {
        val nodeTemplate = file("node-template-sentinel").apply { mkdirs() }
        val legacyTemplate = file("legacy-template-sentinel").apply { mkdirs() }
        val nodeBackend = resolvedBackend {
            templateDirectory("node-template-sentinel")
        }
        val legacyBackend = resolvedBackend()

        val nodeResources = resolveBackendRuntimeResources(
            projectDirectory = projectDir,
            backend = nodeBackend,
            legacyTemplatePath = legacyTemplate.absolutePath,
            readEnv = { null },
        )
        val legacyResources = resolveBackendRuntimeResources(
            projectDirectory = projectDir,
            backend = legacyBackend,
            legacyTemplatePath = legacyTemplate.absolutePath,
            readEnv = { null },
        )

        assertEquals(nodeTemplate.canonicalFile, nodeResources.templateDirectory?.canonicalFile)
        assertEquals(legacyTemplate.canonicalFile, legacyResources.templateDirectory?.canonicalFile)
    }

    @Test
    fun `环境变量已设置但路径无效时不回退并给出中文错误上下文`() {
        val literalJar = file("literal/proxy-sentinel.jar").apply { writeText("literal-sentinel") }
        val proxy = resolvedProxy { plugin("PROXY_PLUGIN_SENTINEL") }

        val exception = assertFailsWith<GradleException> {
            resolveProxyRuntimeResources(projectDir, proxy) { name ->
                if (name == "PROXY_PLUGIN_SENTINEL") "missing/environment-sentinel.jar" else null
            }
        }

        assertTrue(literalJar.isFile)
        assertTrue(exception.message!!.contains("代理"))
        assertTrue(exception.message!!.contains("proxy-sentinel"))
        assertTrue(exception.message!!.contains("PROXY_PLUGIN_SENTINEL"))
        assertTrue(exception.message!!.contains("missing"))
        assertTrue(exception.message!!.contains("采用环境变量"))
        assertTrue(exception.message!!.contains("jar"))
    }

    @Test
    fun `插件必须为普通 jar 文件且模板必须为目录`() {
        file("not-jar-sentinel.txt").writeText("sentinel")
        file("not-directory-sentinel").writeText("sentinel")
        val pluginException = assertFailsWith<GradleException> {
            resolveProxyRuntimeResources(
                projectDir,
                resolvedProxy { plugin("not-jar-sentinel.txt") },
                readEnv = { null },
            )
        }
        val templateException = assertFailsWith<GradleException> {
            resolveBackendRuntimeResources(
                projectDirectory = projectDir,
                backend = resolvedBackend { templateDirectory("not-directory-sentinel") },
                legacyTemplatePath = null,
                readEnv = { null },
            )
        }

        assertTrue(pluginException.message!!.contains(".jar"))
        assertTrue(templateException.message!!.contains("目录"))
    }

    @Test
    fun `多个代理插件解析为同一目标文件名时中文失败`() {
        file("a/same-sentinel.jar").writeText("a-sentinel")
        file("b/same-sentinel.jar").writeText("b-sentinel")
        val proxy = resolvedProxy {
            plugin("a/same-sentinel.jar")
            plugin("b/same-sentinel.jar")
        }

        val exception = assertFailsWith<GradleException> {
            resolveProxyRuntimeResources(projectDir, proxy) { null }
        }

        assertTrue(exception.message!!.contains("same-sentinel.jar"))
        assertTrue(exception.message!!.contains("冲突"))
    }

    @Test
    fun `节点环境覆盖宿主且框架环境最终覆盖节点`() {
        val merged = mergeNodeEnvironment(
            nodeEnvironment = linkedMapOf(
                "NODE_SENTINEL" to "node-sentinel",
                "FRAMEWORK_SENTINEL" to "node-attempt-sentinel",
            ),
            frameworkEnvironment = mapOf("FRAMEWORK_SENTINEL" to "framework-sentinel"),
        )

        assertEquals("node-sentinel", merged["NODE_SENTINEL"])
        assertEquals("framework-sentinel", merged["FRAMEWORK_SENTINEL"])
    }

    @Test
    fun `后端 staging 先铺模板再写权威配置且只注入 dependencies`() {
        val template = file("backend-template-sentinel").apply { mkdirs() }
        File(template, "server.properties").writeText("server-port=1\ndifficulty=hard\n")
        File(template, "node-template-sentinel.txt").writeText("node-template-sentinel")
        val dependencyJar = file("backend-dependency-sentinel.jar").apply { writeText("backend-dependency-sentinel") }
        val runDir = file("backend-run-sentinel").apply {
            mkdirs()
            File(this, "stale-sentinel.txt").writeText("stale-sentinel")
        }
        val backend = resolvedBackend()
        val resources = BackendRuntimeResources(
            templateDirectory = template,
            dependencyJars = listOf(ResolvedPluginJar("backend-dependency-sentinel.jar", dependencyJar, underTest = false)),
        )

        stageBackendRuntime(runDir, backend, resources)

        val properties = File(runDir, "server.properties").readText()
        assertTrue("server-port=${backend.port}" in properties)
        assertTrue("difficulty=hard" in properties)
        assertTrue(File(runDir, "node-template-sentinel.txt").isFile)
        assertTrue(File(runDir, "plugins/backend-dependency-sentinel.jar").isFile)
        assertFalse(File(runDir, "stale-sentinel.txt").exists())
    }

    @Test
    fun `代理 staging 清理后铺模板写权威配置并让显式插件覆盖模板同名文件`() {
        val template = file("proxy-template-sentinel").apply { mkdirs() }
        File(template, "config.yml").writeText("template-config-sentinel")
        File(template, "plugins").mkdirs()
        File(template, "plugins/proxy-plugin-sentinel.jar").writeText("template-plugin-sentinel")
        File(template, "plugin-config-sentinel.yml").writeText("plugin-config-sentinel")
        val plugin = file("proxy-plugin-sentinel.jar").apply { writeText("explicit-plugin-sentinel") }
        val runDir = file("proxy-run-sentinel").apply {
            mkdirs()
            File(this, "stale-sentinel.txt").writeText("stale-sentinel")
        }
        val resources = ProxyRuntimeResources(template, listOf(plugin))
        val order = mutableListOf<String>()

        stageProxyRuntime(
            runDirectory = runDir,
            resources = resources,
            writeFrameworkConfiguration = {
                order += "config"
                File(runDir, "config.yml").writeText("framework-config-sentinel")
            },
            preparePlatformRuntime = { order += "platform" },
        )

        assertEquals(listOf("config", "platform"), order)
        assertEquals("framework-config-sentinel", File(runDir, "config.yml").readText())
        assertEquals("explicit-plugin-sentinel", File(runDir, "plugins/proxy-plugin-sentinel.jar").readText())
        assertTrue(File(runDir, "plugin-config-sentinel.yml").isFile)
        assertFalse(File(runDir, "stale-sentinel.txt").exists())
    }

    private fun resolvedBackend(configure: BackendSpec.() -> Unit = {}) =
        TopologyResolver.resolve(
            backends = listOf(BackendSpec("backend-sentinel").apply(configure)),
            proxies = emptyList(),
            scenarios = emptyList(),
        ).backends.single()

    private fun resolvedProxy(configure: ProxySpec.() -> Unit) =
        TopologyResolver.resolve(
            backends = listOf(BackendSpec("backend-sentinel")),
            proxies = listOf(
                ProxySpec("proxy-sentinel").apply {
                    routesTo("backend-sentinel")
                    configure()
                },
            ),
            scenarios = emptyList(),
        ).proxies.single()

    private fun file(relativePath: String): File = File(projectDir, relativePath).apply { parentFile?.mkdirs() }
}
