package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [JarCache] 缓存路径推导单元测试（内置下载与运行，纯函数）。
 *
 * 校验 `<cacheRoot>/<platform>/<version>/<build>.jar` 布局、BungeeCord 占位段、相对缓存根
 * （不写死本机绝对路径——缓存根由调用方注入，这里用相对临时根）。
 */
class JarCacheTest {

    private val cacheRoot = File("build/test-cache-root")
    private val cache = JarCache(cacheRoot)

    @Test
    @DisplayName("解析 PaperMC 缓存路径时应按平台、版本和构建号分层")
    fun resolvePaperCachePathByPlatformVersionAndBuild() {
        val jar = cache.jarFile(ProvisionPlatform.PAPER, "1.20.1", 196)
        assertEquals(cacheRoot.resolve("paper").resolve("1.20.1").resolve("196.jar"), jar)
    }

    @Test
    @DisplayName("解析代理平台缓存路径时应按平台、版本和构建号分层")
    fun resolveProxyCachePathByPlatformVersionAndBuild() {
        val jar = cache.jarFile(ProvisionPlatform.WATERFALL, "1.20", 564)
        assertEquals(cacheRoot.resolve("waterfall").resolve("1.20").resolve("564.jar"), jar)
    }

    @Test
    @DisplayName("解析 BungeeCord 缓存路径时应使用固定版本占位段")
    fun usePlaceholderVersionForBungeeCordCachePath() {
        val jar = cache.jarFile(ProvisionPlatform.BUNGEECORD, null, 1820)
        assertEquals(cacheRoot.resolve("bungeecord").resolve("bungeecord").resolve("1820.jar"), jar)
    }

    @Test
    @DisplayName("解析 jar 目录时应与 jar 文件父目录一致")
    fun keepJarDirectoryConsistentWithJarParent() {
        val dir = cache.jarDir(ProvisionPlatform.PAPER, "1.20.1")
        val jar = cache.jarFile(ProvisionPlatform.PAPER, "1.20.1", 196)
        assertEquals(dir, jar.parentFile)
    }

    @Test
    @DisplayName("使用相对缓存根时应保持缓存路径可移植")
    fun preserveRelativeCachePathForPortability() {
        // 用相对缓存根，推导结果应仍是相对路径（不被强制绝对化）
        val jar = cache.jarFile(ProvisionPlatform.FOLIA, "1.20.1", 12)
        assertTrue(!jar.isAbsolute, "缓存路径应随注入的相对根保持相对：$jar")
    }

    @Test
    @DisplayName("PaperMC 平台缺少版本时应抛出中文错误")
    fun rejectMissingPaperVersionWithChineseError() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cache.jarFile(ProvisionPlatform.PAPER, null, 1)
        }
        assertTrue(ex.message!!.contains("版本号"), "应提示需要版本号：${ex.message}")
    }
}
