package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RunLayout] 路径推导与 [cleanRunDirPreservingRuntimeCaches] 的纯函数单测（FR-04）。
 *
 * 用临时目录穷举：路径关系不写死本机绝对路径（NFR 可移植）、清理运行目录保留运行库缓存（NFR 幂等）。
 */
class RunLayoutTest {

    @TempDir
    lateinit var tmp: File

    private fun layout(): RunLayout = RunLayout(
        buildDir = File(tmp, "build"),
        gradleUserHome = File(tmp, "gradle-home"),
        rootDir = File(tmp, "root"),
    )

    @Test
    @DisplayName("运行目录与结果目录应位于 build/mc-testkit 下")
    fun deriveBuildScopedRunAndResultDirectories() {
        val layout = layout()
        assertEquals(File(tmp, "build/mc-testkit/run"), layout.runDir)
        assertEquals(File(tmp, "build/mc-testkit/results"), layout.resultsDir)
        assertEquals(File(tmp, "build/mc-testkit/run-proxy"), layout.proxyRunDir)
    }

    @Test
    @DisplayName("jar 缓存目录应位于 Gradle 用户主目录以支持跨工程复用")
    fun deriveJarCacheFromGradleUserHome() {
        assertEquals(File(tmp, "gradle-home/caches/mc-testkit-jars"), layout().jarCacheRoot)
    }

    @Test
    @DisplayName("持久运行库缓存应位于根工程 .gradle 目录")
    fun derivePersistentRuntimeCacheFromRootProject() {
        assertEquals(File(tmp, "root/.gradle/mc-testkit/server-base"), layout().persistentServerBaseDir)
    }

    @Test
    @DisplayName("代理 pid 文件应按代理名写入结果目录")
    fun deriveProxyPidFileFromProxyName() {
        assertEquals(File(tmp, "build/mc-testkit/results/proxy-wf.pid"), layout().proxyPidFile("wf"))
    }

    @Test
    @DisplayName("未配置 Bot 目录时应相对根工程解析为 e2e-bot")
    fun resolveDefaultBotDirectoryFromRootProject() {
        assertEquals(File(tmp, "root/e2e-bot"), layout().botDir(null))
        assertEquals(File(tmp, "root/e2e-bot"), layout().botDir(""))
    }

    @Test
    @DisplayName("配置相对 Bot 目录时应相对根工程解析")
    fun resolveRelativeBotDirectoryFromRootProject() {
        assertEquals(File(tmp, "root/custom/bot"), layout().botDir("custom/bot"))
    }

    @Test
    @DisplayName("配置绝对 Bot 目录时应直接采用该路径")
    fun resolveAbsoluteBotDirectoryDirectly() {
        val abs = File(tmp, "elsewhere/bot").absoluteFile
        assertEquals(abs, layout().botDir(abs.path))
    }

    @Test
    @DisplayName("清理运行目录时应保留运行库缓存并删除其他产物")
    fun cleanRunDirectoryPreservesRuntimeCaches() {
        val runDir = File(tmp, "run").apply { mkdirs() }
        // 运行库缓存（应保留）
        File(runDir, "libraries/a.jar").apply { parentFile.mkdirs() }.writeText("x")
        File(runDir, "versions/v.json").apply { parentFile.mkdirs() }.writeText("x")
        File(runDir, "cache/c.dat").apply { parentFile.mkdirs() }.writeText("x")
        // 运行产物（应删除）
        File(runDir, "plugins/p.jar").apply { parentFile.mkdirs() }.writeText("x")
        File(runDir, "world/level.dat").apply { parentFile.mkdirs() }.writeText("x")
        File(runDir, "server.properties").writeText("x")

        cleanRunDirPreservingRuntimeCaches(runDir)

        assertTrue(File(runDir, "libraries/a.jar").exists(), "运行库 libraries 应保留")
        assertTrue(File(runDir, "versions/v.json").exists(), "versions 应保留")
        assertTrue(File(runDir, "cache/c.dat").exists(), "cache 应保留")
        assertFalse(File(runDir, "plugins").exists(), "plugins 应删除")
        assertFalse(File(runDir, "world").exists(), "world 应删除")
        assertFalse(File(runDir, "server.properties").exists(), "server.properties 应删除")
    }

    @Test
    @DisplayName("清理不存在的运行目录时应安全完成且不抛异常")
    fun cleanMissingRunDirectorySafely() {
        // 不抛异常即通过
        cleanRunDirPreservingRuntimeCaches(File(tmp, "missing"))
    }
}
