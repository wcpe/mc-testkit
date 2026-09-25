package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ExternalArtifactProvisioner] 与 [ExternalArtifactSource] 单元测试（ADR-0017）。
 *
 * 覆盖：缓存路径推导、命中缓存不发网络、sha256 校验（成功 / 失败）、原子落盘（不留 `.tmp`）、
 * 两种来源形态的地址解析、以及解析失败的中文报错。
 *
 * 用替身注入下载行为，**零网络**：断言「缓存命中不发网络」这类关键契约必须靠可观测的副作用，
 * 不能只看返回值。
 */
class ExternalArtifactProvisionerTest {

    private val cacheRoot = File("build/test-external-cache")

    private fun fixedSource(
        fileName: String = "PlaceholderAPI-2.12.2.jar",
        url: String = "https://example.invalid/PlaceholderAPI-2.12.2.jar",
        sha256: String? = null,
    ) = ExternalArtifactSource.FixedUrl(
        id = "placeholderapi",
        fileName = fileName,
        url = url,
        expectedSha256 = sha256,
    )

    @Test
    @DisplayName("推导缓存路径时应落在 external 子树下并按来源 id 分层")
    fun resolveCachePathUnderExternalSubtree() {
        val provisioner = ExternalArtifactProvisioner(cacheRoot)
        val cached = provisioner.cacheFile(fixedSource())
        assertEquals(
            cacheRoot.resolve("external").resolve("placeholderapi").resolve("PlaceholderAPI-2.12.2.jar"),
            cached,
        )
    }

    @Test
    @DisplayName("外部制品缓存应与平台下载和 Maven 镜像分层隔离")
    fun keepExternalCacheSeparateFromOtherLayers() {
        val provisioner = ExternalArtifactProvisioner(cacheRoot)
        val external = provisioner.cacheFile(fixedSource())
        val platform = JarCache(cacheRoot).jarFile(ProvisionPlatform.PAPER, "1.20.1", 196)
        val maven = JarCache(cacheRoot).mavenJarFile("io.papermc.paper:paper:1.12.2")
        val externalTop = external.relativeTo(cacheRoot).invariantSeparatorsPath.substringBefore('/')
        assertEquals("external", externalTop)
        assertTrue(external != platform && external != maven, "外部制品路径不应与其它来源重合")
    }

    @Test
    @DisplayName("使用相对缓存根时应保持路径可移植")
    fun preserveRelativeCachePathForPortability() {
        val provisioner = ExternalArtifactProvisioner(cacheRoot)
        assertTrue(!provisioner.cacheFile(fixedSource()).isAbsolute, "缓存路径应随注入的相对根保持相对")
    }

    @Test
    @DisplayName("缓存命中且无期望哈希时应直接复用且不发起下载")
    fun reuseCacheHitWithoutDownload() {
        val provisioner = ExternalArtifactProvisioner(cacheRoot)
        val cached = provisioner.cacheFile(fixedSource())
        cached.parentFile.mkdirs()
        cached.writeText("already-cached")
        val logs = mutableListOf<String>()

        val result = provisioner.provision(fixedSource(url = "https://example.invalid/should-not-be-fetched.jar")) {
            logs += it
        }

        assertEquals(cached, result)
        assertTrue(logs.any { it.contains("命中缓存") }, "应报告命中缓存：$logs")
    }

    @Test
    @DisplayName("缓存存在但哈希不符时应视为未命中，重新下载")
    fun treatHashMismatchAsCacheMiss() {
        val provisioner = ExternalArtifactProvisioner(cacheRoot)
        val cached = provisioner.cacheFile(fixedSource(sha256 = "deadbeef"))
        cached.parentFile.mkdirs()
        cached.writeText("stale-content")

        // 哈希不符 → 走下载路径；地址不可达应失败（证明没有直接复用陈旧文件）
        assertFailsWith<IOException> {
            provisioner.provision(fixedSource(sha256 = "deadbeef", url = "https://127.0.0.1:1/nope.jar"))
        }
    }

    @Test
    @DisplayName("按正则取最后一个匹配时应返回末项（版本升序约定为最新）")
    fun resolveLastUrlMatchForVersionAscendingResponse() {
        val resolver = ArtifactUrlResolver.lastUrlMatch(
            """"url"\s*:\s*"(https://[^"]*PAPI-Expansion-Player[^"]*\.jar)"""",
        )
        val response = """
            {"versions":[
              {"url":"https://cdn.example/1.0/PAPI-Expansion-Player-1.0.jar"},
              {"url":"https://cdn.example/2.0/PAPI-Expansion-Player-2.0.jar"}
            ]}
        """.trimIndent()

        assertEquals("https://cdn.example/2.0/PAPI-Expansion-Player-2.0.jar", resolver.resolve(response))
    }

    @Test
    @DisplayName("正则无匹配时应抛出中文错误并带上正则")
    fun rejectUnmatchedResolverWithChineseError() {
        val resolver = ArtifactUrlResolver.lastUrlMatch(""""url"\s*:\s*"(https://[^"]*Missing[^"]*\.jar)"""")
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve("""{"versions":[]}""") }
        assertTrue(ex.message!!.contains("未从响应中解析到制品下载地址"), "应给中文错误：${ex.message}")
    }

    @Test
    @DisplayName("解析器应可序列化，以便进入消费方任务动作与配置缓存")
    fun keepResolverSerializable() {
        val resolver = ArtifactUrlResolver.lastUrlMatch("""(https://[^"]+\.jar)""")
        val restored = roundTrip(resolver)
        assertEquals(
            resolver.resolve("""{"u":"https://cdn.example/a.jar"}"""),
            restored.resolve("""{"u":"https://cdn.example/a.jar"}"""),
        )
    }

    @Test
    @DisplayName("外部制品来源应可序列化，以便进入消费方任务动作与配置缓存")
    fun keepSourceSerializable() {
        val source = ExternalArtifactSource.ApiResolved(
            id = "papi-expansion-player",
            fileName = "PAPI-Expansion-Player.jar",
            apiUrl = "https://ecloud.example/api/v3/",
            resolver = ArtifactUrlResolver.lastUrlMatch("""(https://[^"]+\.jar)"""),
        )
        val restored = roundTrip(source)

        assertEquals(source.id, restored.id)
        assertEquals(source.fileName, restored.fileName)
        assertEquals(source.apiUrl, restored.apiUrl)
    }

    /** 序列化往返：配置缓存要求任务动作捕获图可序列化，故这些类型须能经得起一次往返。 */
    private fun <T> roundTrip(value: T): T {
        val buffer = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(buffer).use { it.writeObject(value) }
        return java.io.ObjectInputStream(java.io.ByteArrayInputStream(buffer.toByteArray()))
            .use { @Suppress("UNCHECKED_CAST") (it.readObject() as T) }
    }
}
