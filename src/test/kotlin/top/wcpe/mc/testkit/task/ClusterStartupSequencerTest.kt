package top.wcpe.mc.testkit.task

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterStartupSequencerTest {

    @Test
    fun `同版本第二后端必须等待首个后端就绪`() {
        val predecessors = sameVersionStartupPredecessors(
            listOf("first" to "1.18.2", "second" to "1.18.2"),
        )

        assertEquals(mapOf("second" to "first"), predecessors)
    }

    @Test
    fun `不同版本后端不引入无关等待`() {
        val predecessors = sameVersionStartupPredecessors(
            listOf("legacy" to "1.18.2", "modern" to "1.20.1"),
        )

        assertEquals(emptyMap(), predecessors)
    }

    @Test
    fun `同版本后端复用已就绪节点的运行缓存`() {
        val source = Files.createTempDirectory("mc-testkit-source").toFile()
        val target = Files.createTempDirectory("mc-testkit-target").toFile()
        source.resolve("cache/mojang_1.18.2.jar").apply {
            parentFile.mkdirs()
            writeText("已准备")
        }

        copyRuntimeCaches(source, target)

        assertTrue(target.resolve("cache/mojang_1.18.2.jar").isFile)
    }
}
