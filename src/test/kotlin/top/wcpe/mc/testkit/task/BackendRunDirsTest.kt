package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [backendRunDirsByName] 的纯函数单测（任务自动编排：持久缓存的按后端回写枚举）。
 *
 * 该函数决定 `syncE2eRuntimeCache` 往 `server-base/<backendName>/` 分别回写哪些运行目录，
 * 故重点锚定两件事：①只认 `run-<name>` 形态的目录；②**排除 `run-proxy`**
 * （代理目录同前缀，误认会凭空造出 `server-base/proxy/` 并把代理的运行库混进后端缓存）。
 *
 * 用临时目录构造工作根，不依赖本机绝对路径（NFR 可移植）。
 */
class BackendRunDirsTest {

    @TempDir
    lateinit var tmp: File

    private fun layout(): RunLayout = RunLayout(
        buildDir = File(tmp, "build"),
        gradleUserHome = File(tmp, "gradle-home"),
        rootDir = File(tmp, "root"),
    )

    /** 在工作根下建目录（或普通文件），返回该路径。 */
    private fun workEntry(layout: RunLayout, name: String, directory: Boolean = true): File =
        File(layout.workRoot, name).apply {
            parentFile.mkdirs()
            if (directory) mkdirs() else writeText("sentinel")
        }

    @Test
    @DisplayName("只识别 run-<后端名> 形态的目录")
    fun recognizeOnlyBackendRunDirectories() {
        val layout = layout()
        workEntry(layout, "run-s1")

        val result = backendRunDirsByName(layout)

        assertEquals(setOf("s1"), result.keys, "应识别出后端运行目录 run-s1")
        assertEquals(File(layout.workRoot, "run-s1"), result.getValue("s1"), "映射值应为该运行目录本身")
    }

    @Test
    @DisplayName("排除 run-proxy 代理运行目录")
    fun excludeProxyRunDirectory() {
        val layout = layout()
        workEntry(layout, "run-s1")
        workEntry(layout, "run-proxy")

        val result = backendRunDirsByName(layout)

        assertEquals(setOf("s1"), result.keys, "run-proxy 是代理目录，不得被当成本名叫 proxy 的后端")
    }

    @Test
    @DisplayName("忽略非目录文件与历史共享的 run 目录")
    fun ignoreFilesAndLegacySharedRunDirectory() {
        val layout = layout()
        // 同名却是普通文件（残留产物）：必须按 isDirectory 过滤掉
        workEntry(layout, "run-s1", directory = false)
        // 历史共享运行目录：无后端名下标，无法归属到某个后端，故不参与按后端回写
        workEntry(layout, "run")
        workEntry(layout, "results")

        assertTrue(
            backendRunDirsByName(layout).isEmpty(),
            "非目录文件、run 与 results 都不应被识别为后端运行目录",
        )
    }

    @Test
    @DisplayName("工作根不存在时应返回空映射且不抛异常")
    fun returnEmptyWhenWorkRootMissing() {
        val layout = layout() // build/mc-testkit 尚未创建

        assertTrue(
            backendRunDirsByName(layout).isEmpty(),
            "工作根不存在时应安全返回空映射",
        )
    }

    @Test
    @DisplayName("多个后端运行目录应全部返回且 key 为去掉前缀的后端名")
    fun mapAllBackendRunDirectoriesByBackendName() {
        val layout = layout()
        workEntry(layout, "run-s1")
        workEntry(layout, "run-paper1165")
        workEntry(layout, "run-proxy")
        workEntry(layout, "results")

        val result = backendRunDirsByName(layout)

        assertEquals(setOf("s1", "paper1165"), result.keys, "key 应为去掉 run- 前缀的后端名")
        assertEquals(File(layout.workRoot, "run-paper1165"), result.getValue("paper1165"))
    }

    @Test
    @DisplayName("运行目录名与 backendRunDir 推导出的目录名应一一对齐")
    fun alignWithBackendRunDirNaming() {
        val layout = layout()
        // 回写侧靠「目录名 → 后端名」反推下标，恢复侧靠 backendRunDir(backendName) 拼目录，
        // 两侧必须同源；此处建出 backendRunDir 给出的目录，断言能被枚举回来
        layout.backendRunDir("s1").mkdirs()

        val result = backendRunDirsByName(layout)

        assertEquals(
            File(layout.workRoot, "run-s1"),
            result["s1"],
            "backendRunDir 推导出的目录名应能被 backendRunDirsByName 反推回同一后端名",
        )
    }
}
