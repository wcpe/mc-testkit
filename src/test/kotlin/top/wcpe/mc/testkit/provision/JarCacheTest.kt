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

    // ── Maven 坐标镜像路径（mavenServer）──

    @Test
    @DisplayName("Maven 坐标镜像路径应按 Maven 仓库层级推导")
    fun resolveMavenMirrorPathInMavenLayout() {
        val jar = cache.mavenJarFile("io.papermc.paper:paper:1.12.2")
        assertEquals(
            cacheRoot.resolve("maven")
                .resolve("io").resolve("papermc").resolve("paper")
                .resolve("paper").resolve("1.12.2").resolve("paper-1.12.2.jar"),
            jar,
        )
    }

    @Test
    @DisplayName("groupId 含多点时应逐段转为目录层级")
    fun convertMultiSegmentGroupIdToDirectoryPath() {
        val jar = cache.mavenJarFile("top.wcpe.mc:harness-core:0.1.1")
        assertEquals(
            cacheRoot.resolve("maven")
                .resolve("top").resolve("wcpe").resolve("mc")
                .resolve("harness-core").resolve("0.1.1").resolve("harness-core-0.1.1.jar"),
            jar,
        )
    }

    @Test
    @DisplayName("SNAPSHOT 版本的镜像路径应保留其版本原文")
    fun keepSnapshotVersionLiteralInMavenMirrorPath() {
        val jar = cache.mavenJarFile("com.example:foo-plugin:1.2.0-SNAPSHOT")
        assertEquals(
            cacheRoot.resolve("maven")
                .resolve("com").resolve("example")
                .resolve("foo-plugin").resolve("1.2.0-SNAPSHOT").resolve("foo-plugin-1.2.0-SNAPSHOT.jar"),
            jar,
        )
    }

    @Test
    @DisplayName("Maven 镜像应与内置下载分层隔离，且不进入平台版本目录")
    fun keepMavenMirrorSeparateFromBuiltInDownloadLayers() {
        val mirror = cache.mavenJarFile("io.papermc.paper:paper:1.12.2")
        val builtIn = cache.jarFile(ProvisionPlatform.PAPER, "1.12.2", 1)
        // 镜像在独立 maven/ 子树下，不与 <platform>/<version>/<build>.jar 混放
        assertEquals("maven", mirror.relativeTo(cacheRoot).invariantSeparatorsPath.substringBefore('/'))
        assertTrue(mirror != builtIn, "镜像路径不应与内置下载路径重合")
        assertTrue(!builtIn.relativeTo(cacheRoot).invariantSeparatorsPath.startsWith("maven/"))
    }

    @Test
    @DisplayName("非三段式坐标应抛出中文错误")
    fun rejectMalformedCoordinateForMavenMirrorPath() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cache.mavenJarFile("io.papermc.paper:paper")
        }
        assertTrue(ex.message!!.contains("group:artifact:version"), "应点明期望形态：${ex.message}")
    }

    @Test
    @DisplayName("使用相对缓存根时 Maven 镜像路径应同样保持相对")
    fun keepMavenMirrorPathRelativeForPortability() {
        val jar = cache.mavenJarFile("io.papermc.paper:paper:1.12.2")
        assertTrue(!jar.isAbsolute, "镜像路径应随注入的相对根保持相对：$jar")
    }
}
