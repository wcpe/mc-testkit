package top.wcpe.mc.testkit.provision

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [JarCache] 缓存路径推导单元测试（FR-02，纯函数）。
 *
 * 校验 `<cacheRoot>/<platform>/<version>/<build>.jar` 布局、BungeeCord 占位段、相对缓存根
 * （不写死本机绝对路径——缓存根由调用方注入，这里用相对临时根）。
 */
class JarCacheTest {

    private val cacheRoot = File("build/test-cache-root")
    private val cache = JarCache(cacheRoot)

    @Test
    fun `PaperMC 平台路径按 平台-版本-构建号 分层`() {
        val jar = cache.jarFile(ProvisionPlatform.PAPER, "1.20.1", 196)
        assertEquals(cacheRoot.resolve("paper").resolve("1.20.1").resolve("196.jar"), jar)
    }

    @Test
    fun `代理平台路径同样分层`() {
        val jar = cache.jarFile(ProvisionPlatform.WATERFALL, "1.20", 564)
        assertEquals(cacheRoot.resolve("waterfall").resolve("1.20").resolve("564.jar"), jar)
    }

    @Test
    fun `BungeeCord 无版本用固定占位段`() {
        val jar = cache.jarFile(ProvisionPlatform.BUNGEECORD, null, 1820)
        assertEquals(cacheRoot.resolve("bungeecord").resolve("bungeecord").resolve("1820.jar"), jar)
    }

    @Test
    fun `jar 目录与 jar 文件父目录一致`() {
        val dir = cache.jarDir(ProvisionPlatform.PAPER, "1.20.1")
        val jar = cache.jarFile(ProvisionPlatform.PAPER, "1.20.1", 196)
        assertEquals(dir, jar.parentFile)
    }

    @Test
    fun `缓存路径不含本机绝对前缀（可移植）`() {
        // 用相对缓存根，推导结果应仍是相对路径（不被强制绝对化）
        val jar = cache.jarFile(ProvisionPlatform.FOLIA, "1.20.1", 12)
        assertTrue(!jar.isAbsolute, "缓存路径应随注入的相对根保持相对：$jar")
    }

    @Test
    fun `PaperMC 平台缺版本抛中文错误`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cache.jarFile(ProvisionPlatform.PAPER, null, 1)
        }
        assertTrue(ex.message!!.contains("版本号"), "应提示需要版本号：${ex.message}")
    }
}
