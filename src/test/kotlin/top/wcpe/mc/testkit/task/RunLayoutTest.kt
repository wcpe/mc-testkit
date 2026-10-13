package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [RunLayout] 路径推导、[cleanRunDirPreservingRuntimeCaches] 与 [restoreBackendRuntimeCache] 的纯函数单测
 * （任务自动编排）。
 *
 * 用临时目录穷举：路径关系不写死本机绝对路径（NFR 可移植）、清理运行目录保留运行库缓存（NFR 幂等）、
 * 持久缓存「按后端隔离 + 只补缺失」的恢复语义（NFR 幂等可重跑）。
 */
class RunLayoutTest {

    @TempDir
    lateinit var tmp: File

    private fun layout(): RunLayout = RunLayout(
        buildDir = File(tmp, "build"),
        gradleUserHome = File(tmp, "gradle-home"),
        rootDir = File(tmp, "root"),
    )

    /** 在 [dir] 下按相对路径写文件（自动建父目录），返回写入的文件。 */
    private fun writeFile(dir: File, relativePath: String, content: String): File =
        File(dir, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }

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

    @Test
    @DisplayName("后端持久缓存目录应按后端名隔离在 server-base 下")
    fun derivePersistentBackendCacheDirPerBackend() {
        val layout = layout()

        // 路径必须落在 server-base 之下并与后端名同名（回写与恢复两侧靠这个下标对齐）
        assertEquals(
            File(tmp, "root/.gradle/mc-testkit/server-base/s1"),
            layout.persistentBackendCacheDir("s1"),
        )
        assertEquals(
            layout.persistentServerBaseDir,
            layout.persistentBackendCacheDir("s1").parentFile,
        )
        // 隔离性：不同后端的缓存目录必须互不相同，否则多版本矩阵会互相污染运行库
        assertNotEquals(
            layout.persistentBackendCacheDir("s1"),
            layout.persistentBackendCacheDir("s2"),
            "不同后端的持久缓存目录不得相同",
        )
    }

    @Test
    @DisplayName("持久缓存目录不存在时恢复应安全跳过且不改动运行目录")
    fun restoreSkipsWhenCacheDirectoryMissing() {
        val layout = layout()
        val runDir = File(tmp, "run").apply { mkdirs() }
        writeFile(runDir, "libraries/keep.jar", "keep")

        // 缓存目录从未被回写（首次运行），不抛异常且不创建任何东西
        restoreBackendRuntimeCache(layout.persistentBackendCacheDir("s1"), runDir)

        assertFalse(
            layout.persistentBackendCacheDir("s1").exists(),
            "缓存目录不存在时恢复不应把它创建出来",
        )
        assertEquals(
            listOf("libraries"),
            runDir.listFiles()!!.map { it.name }.sorted(),
            "缓存目录不存在时运行目录内容不应被改动",
        )
        assertEquals("keep", File(runDir, "libraries/keep.jar").readText(), "已有文件内容不应被改动")
    }

    @Test
    @DisplayName("运行目录不存在时应被创建并从缓存恢复全部四个缓存子目录")
    fun restoreCreatesRunDirectoryAndAllCacheEntries() {
        val layout = layout()
        val cacheDir = layout.persistentBackendCacheDir("s1")
        // 四个保留子目录各放一份内容，验证恢复是「全量四目录」而不是只挑其中一个
        RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.forEach { name ->
            writeFile(cacheDir, "$name/sentinel.txt", "$name-sentinel")
        }
        val runDir = File(tmp, "run") // 刻意不创建

        restoreBackendRuntimeCache(cacheDir, runDir)

        RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.forEach { name ->
            val restored = File(runDir, "$name/sentinel.txt")
            assertTrue(restored.isFile, "运行目录不存在时应被创建并恢复 $name")
            assertEquals("$name-sentinel", restored.readText(), "$name 内容应原样恢复")
        }
    }

    @Test
    @DisplayName("恢复时只补缺失且不覆盖运行目录里已有的同名文件")
    fun restoreMergesMissingWithoutOverwritingExisting() {
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        writeFile(cacheDir, "libraries/old.jar", "cached-old")
        writeFile(cacheDir, "libraries/only-in-cache.jar", "cached-new")
        writeFile(cacheDir, "cache/downloaded.dat", "cached-download")
        val runDir = File(tmp, "run").apply { mkdirs() }
        // 运行目录里已有的是本轮真实产物，比缓存新——必须保留
        writeFile(runDir, "libraries/old.jar", "fresh-run")
        writeFile(runDir, "libraries/extra.jar", "run-only")

        restoreBackendRuntimeCache(cacheDir, runDir)

        assertEquals(
            "fresh-run",
            File(runDir, "libraries/old.jar").readText(),
            "运行目录里已有的同名文件不得被旧缓存覆盖",
        )
        assertEquals(
            "cached-new",
            File(runDir, "libraries/only-in-cache.jar").readText(),
            "缓存里独有的文件应被补上",
        )
        assertEquals(
            "run-only",
            File(runDir, "libraries/extra.jar").readText(),
            "运行目录里独有的文件应保留",
        )
        assertEquals(
            "cached-download",
            File(runDir, "cache/downloaded.dat").readText(),
            "子目录已存在时应同样逐项补齐缺失文件",
        )
    }

    @Test
    @DisplayName("恢复深层嵌套的运行库目录结构时应逐级补齐缺失文件")
    fun restoreCreatesNestedLibraryDirectories() {
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        // paperclip 的运行库按 Maven 坐标展开成多层目录，必须逐级补齐而不是只建一层
        writeFile(cacheDir, "libraries/a/b/c.jar", "deep-cached")
        writeFile(cacheDir, "libraries/a/b/d.jar", "deep-cached-2")
        writeFile(cacheDir, "assets/objects/e/f/object.bin", "deep-asset")
        val runDir = File(tmp, "run").apply { mkdirs() }
        writeFile(runDir, "libraries/a/b/d.jar", "fresh-run")

        restoreBackendRuntimeCache(cacheDir, runDir)

        assertEquals("deep-cached", File(runDir, "libraries/a/b/c.jar").readText(), "深层缺失文件应补齐")
        assertEquals("fresh-run", File(runDir, "libraries/a/b/d.jar").readText(), "深层已有文件不得被覆盖")
        assertEquals("deep-asset", File(runDir, "assets/objects/e/f/object.bin").readText(), "深层资源同样应补齐")
    }

    @Test
    @DisplayName("回写持久缓存后经清理再从缓存恢复应把运行库内容带回运行目录")
    fun restoreAfterCleanAndSyncRoundTrip() {
        val layout = layout()
        val cacheDir = layout.persistentBackendCacheDir("s1")
        // 用真实的后端运行目录（run-<backendName>）：回写侧靠目录名反推后端名，路径不对就串不到一起
        val runDir = layout.backendRunDir("s1").apply { mkdirs() }
        // 第一轮：运行目录已由 paperclip 拉好运行库与缓存
        writeFile(runDir, "libraries/a/b/c.jar", "round-trip-cached")
        writeFile(runDir, "cache/vanilla-server.jar", "round-trip-cache-entry")
        writeFile(runDir, "versions/1.20.1/1.20.1.jar", "round-trip-version")

        // ① 回写：调**生产函数**（而非测试里另写一份等价逻辑），确保断言的正是真实回写行为
        val written = syncBackendRuntimeCaches(layout)
        assertEquals(
            setOf("s1"),
            written.keys,
            "回写应识别出后端 s1（按 run-<name> 目录名归位），实际=$written",
        )
        assertTrue(File(cacheDir, "libraries/a/b/c.jar").isFile, "回写后缓存应含运行库")

        // ② 清理：模拟下一轮运行目录重建（保留子目录本身，但先整目录删掉以贴近 clean 的真实场景）
        runDir.deleteRecursively()
        runDir.mkdirs()
        cleanRunDirPreservingRuntimeCaches(runDir)

        // ③ 恢复：修复前缺这一步，运行目录重建后仍是冷启动（paperclip 会重下约 50MB 原版 jar）
        restoreBackendRuntimeCache(cacheDir, runDir)

        assertEquals(
            "round-trip-cached",
            File(runDir, "libraries/a/b/c.jar").readText(),
            "回写→清理→恢复后运行库内容应回到运行目录",
        )
        assertEquals(
            "round-trip-cache-entry",
            File(runDir, "cache/vanilla-server.jar").readText(),
            "回写→清理→恢复后下载缓存内容应回到运行目录",
        )
        assertEquals(
            "round-trip-version",
            File(runDir, "versions/1.20.1/1.20.1.jar").readText(),
            "回写→清理→恢复后版本目录内容应回到运行目录",
        )
    }

    @Test
    @DisplayName("两个后端的持久缓存互相隔离恢复时不得串味")
    fun restoreKeepsBackendCachesIsolated() {
        val layout = layout()
        val cacheS1 = layout.persistentBackendCacheDir("s1")
        val cacheS2 = layout.persistentBackendCacheDir("s2")
        // 两个后端（Java 8 旧版 / Java 16+ 新版）各自拉到版本敏感的运行库
        writeFile(cacheS1, "libraries/lib-s1.jar", "s1-library")
        writeFile(cacheS1, "cache/vanilla-s1.jar", "s1-cache")
        writeFile(cacheS2, "libraries/lib-s2.jar", "s2-library")
        writeFile(cacheS2, "libraries/lib-s1.jar", "s2-same-name-different-content")

        // 恢复 s2：只能拿到 s2 自己的缓存，同名文件也应是 s2 的内容（不得混入 s1 的版本）
        val runS2 = File(tmp, "run-s2").apply { mkdirs() }
        restoreBackendRuntimeCache(cacheS2, runS2)

        assertEquals("s2-library", File(runS2, "libraries/lib-s2.jar").readText(), "应恢复 s2 自己的运行库")
        assertEquals(
            "s2-same-name-different-content",
            File(runS2, "libraries/lib-s1.jar").readText(),
            "同名文件必须取 s2 缓存的版本，不得被 s1 覆盖",
        )
        assertFalse(
            File(runS2, "cache/vanilla-s1.jar").exists(),
            "恢复 s2 不应把 s1 的下载缓存带进 s2 运行目录",
        )
        assertFalse(File(tmp, "run-s1").exists(), "恢复 s2 不应凭空创建 s1 的运行目录")
    }

    @Test
    @DisplayName("回写应按后端分别归位到各自的缓存目录而不得混用一份")
    fun syncWritesEachBackendIntoItsOwnCacheDirectory() {
        val layout = layout()
        // 两个后端（不同版本、Java 8 与 Java 16+）各自拉到版本敏感的运行库，且存在同名文件
        val runS1 = layout.backendRunDir("s1").apply { mkdirs() }
        val runS2 = layout.backendRunDir("s2").apply { mkdirs() }
        writeFile(runS1, "libraries/lib.jar", "s1-library")
        writeFile(runS1, "cache/vanilla.jar", "s1-cache")
        writeFile(runS2, "libraries/lib.jar", "s2-library")

        val written = syncBackendRuntimeCaches(layout)

        assertEquals(setOf("s1", "s2"), written.keys, "两个后端都应被回写，实际=$written")
        // 关键：同名文件各自落在自己的后端目录里，不得后写的覆盖先写的
        assertEquals(
            "s1-library",
            File(layout.persistentBackendCacheDir("s1"), "libraries/lib.jar").readText(),
            "s1 的运行库应落在 server-base/s1/ 下",
        )
        assertEquals(
            "s2-library",
            File(layout.persistentBackendCacheDir("s2"), "libraries/lib.jar").readText(),
            "s2 的同名运行库应落在 server-base/s2/ 下，不得被 s1 覆盖",
        )
        assertEquals(
            "s1-cache",
            File(layout.persistentBackendCacheDir("s1"), "cache/vanilla.jar").readText(),
            "s1 的下载缓存应落在自己的目录下",
        )
        assertFalse(
            File(layout.persistentBackendCacheDir("s2"), "cache/vanilla.jar").exists(),
            "s2 未拉到的下载缓存不应被 s1 的内容顶替",
        )
    }

    @Test
    @DisplayName("回写应忽略代理运行目录不得把它当成后端")
    fun syncIgnoresProxyRunDirectory() {
        val layout = layout()
        // 代理运行目录同样以 run- 开头，若被当成后端会凭空造出 server-base/proxy/
        val proxyRunDir = layout.proxyRunDir.apply { mkdirs() }
        writeFile(proxyRunDir, "libraries/proxy-lib.jar", "proxy-library")

        val written = syncBackendRuntimeCaches(layout)

        assertEquals(emptyMap(), written, "只有代理运行目录时不应回写任何后端缓存，实际=$written")
        assertFalse(
            File(layout.persistentServerBaseDir, "proxy").exists(),
            "不得把 run-proxy 当成名为 proxy 的后端",
        )
    }

    @Test
    @DisplayName("恢复返回值应为实际补上的文件数")
    fun restoreReturnsNumberOfRestoredFiles() {
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        writeFile(cacheDir, "libraries/a.jar", "a")
        writeFile(cacheDir, "libraries/nested/b.jar", "b")
        writeFile(cacheDir, "cache/c.dat", "c")
        val runDir = File(tmp, "run").apply { mkdirs() }
        // 运行目录已有 a.jar（本轮产物）：不计入恢复数，也不得被覆盖
        writeFile(runDir, "libraries/a.jar", "fresh-run")

        val restored = restoreBackendRuntimeCache(cacheDir, runDir)

        assertEquals(2, restored, "只应统计真正补上的文件（nested/b.jar 与 cache/c.dat）")
        // 幂等：再恢复一次已无缺失，计数回到 0——这也是调用方据以判断「本轮是否真的用上缓存」的依据
        // （0 表示冷启动，若照打「已从持久缓存恢复」会让人误以为缓存生效了）
        assertEquals(0, restoreBackendRuntimeCache(cacheDir, runDir), "运行目录已齐全时恢复数应为 0")
    }

    @Test
    @DisplayName("缓存目录不存在时恢复返回值应为 0")
    fun restoreReturnsZeroWhenCacheDirectoryMissing() {
        val runDir = File(tmp, "run").apply { mkdirs() }

        assertEquals(
            0,
            restoreBackendRuntimeCache(File(tmp, "missing-cache"), runDir),
            "缓存目录不存在时恢复数应为 0（调用方据此不打误导性的恢复日志）",
        )
    }

    @Test
    @DisplayName("缓存目录存在但保留子目录缺失时恢复应安全跳过")
    fun restoreSkipsMissingCacheEntriesSafely() {
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        // 只有 libraries：另外三个保留子目录在缓存里不存在，应逐个跳过而不是报错
        writeFile(cacheDir, "libraries/a.jar", "a")
        val runDir = File(tmp, "run").apply { mkdirs() }

        val restored = restoreBackendRuntimeCache(cacheDir, runDir)

        assertEquals(1, restored, "只应恢复缓存里确实存在的 libraries")
        assertTrue(File(runDir, "libraries/a.jar").isFile, "存在的子目录应正常恢复")
        assertFalse(File(runDir, "cache").exists(), "缓存里没有的子目录不应被凭空创建")
        assertFalse(File(runDir, "assets").exists(), "缓存里没有的子目录不应被凭空创建")
        assertFalse(File(runDir, "versions").exists(), "缓存里没有的子目录不应被凭空创建")
    }
}
