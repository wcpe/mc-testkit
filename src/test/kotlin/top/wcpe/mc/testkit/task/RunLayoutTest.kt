package top.wcpe.mc.testkit.task

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
    fun `运行目录与结果目录挂在 build mc-testkit 下`() {
        val layout = layout()
        assertEquals(File(tmp, "build/mc-testkit/run"), layout.runDir)
        assertEquals(File(tmp, "build/mc-testkit/results"), layout.resultsDir)
        assertEquals(File(tmp, "build/mc-testkit/run-proxy"), layout.proxyRunDir)
    }

    @Test
    fun `jar 缓存挂在 Gradle 用户主目录 跨工程复用`() {
        assertEquals(File(tmp, "gradle-home/caches/mc-testkit-jars"), layout().jarCacheRoot)
    }

    @Test
    fun `持久运行库缓存挂在根工程 gradle 下`() {
        assertEquals(File(tmp, "root/.gradle/mc-testkit/server-base"), layout().persistentServerBaseDir)
    }

    @Test
    fun `代理 pid 文件按代理名落结果目录`() {
        assertEquals(File(tmp, "build/mc-testkit/results/proxy-wf.pid"), layout().proxyPidFile("wf"))
    }

    @Test
    fun `bot 目录缺省相对根工程解析为 e2e-bot`() {
        assertEquals(File(tmp, "root/e2e-bot"), layout().botDir(null))
        assertEquals(File(tmp, "root/e2e-bot"), layout().botDir(""))
    }

    @Test
    fun `bot 目录相对路径覆盖相对根工程解析`() {
        assertEquals(File(tmp, "root/custom/bot"), layout().botDir("custom/bot"))
    }

    @Test
    fun `bot 目录绝对路径覆盖直接采用`() {
        val abs = File(tmp, "elsewhere/bot").absoluteFile
        assertEquals(abs, layout().botDir(abs.path))
    }

    @Test
    fun `清理运行目录保留运行库缓存子目录 删除其余`() {
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
    fun `清理不存在的运行目录是安全 no-op`() {
        // 不抛异常即通过
        cleanRunDirPreservingRuntimeCaches(File(tmp, "missing"))
    }
}
