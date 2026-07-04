package top.wcpe.mc.testkit.provision

import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.contract.McTestkitEnv
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ServerJarProvisioner] 解析优先级单元测试（FR-02 核心行为）。
 *
 * 重点验证："设了 `*_JAR` 环境变量 → 返回该覆盖路径且**全程不发网络**"，以及 `*_VERSION` 覆盖被采纳。
 * 用「会爆炸的下载核心」做替身：两 API 与下载函数一旦被调用即抛错——任何走到网络的路径都会
 * 让测试响亮失败，从而证明 `*_JAR` 分支零网络。环境变量经注入取值器（map）模拟，不读真实环境。
 */
class ServerJarProvisionerTest {

    /** 任何方法被调用都抛错的下载核心：用于证明"不该发网络的路径确实没发"。 */
    private fun explodingService(): JarProvisionService = JarProvisionService(
        cache = JarCache(File("build/should-not-be-used")),
        paperApi = PaperDownloadsApi(fetchText = { error("不应发网络：PaperMC fetchText 被调用") }),
        bungeeApi = BungeeCordJenkinsApi(fetchText = { error("不应发网络：Jenkins fetchText 被调用") }),
        download = { _, _, _ -> error("不应发网络：download 被调用") },
    )

    /** 一个真实存在的临时 jar 文件，供 `*_JAR` 覆盖指向。 */
    private fun tempJar(): File = File.createTempFile("override-", ".jar").apply { deleteOnExit() }

    @Test
    fun `设置 WATERFALL_JAR 时返回覆盖路径且不发网络`() {
        val jar = tempJar()
        val provisioner = ServerJarProvisioner(
            service = explodingService(),
            readEnv = { name -> if (name == McTestkitEnv.WATERFALL_JAR) jar.absolutePath else null },
        )

        // 若误走下载，explodingService 会抛错；能正常返回即证明零网络
        val resolved = provisioner.resolve("waterfall")
        assertEquals(jar.absoluteFile, resolved.absoluteFile)
    }

    @Test
    fun `设置 VELOCITY_JAR 时返回覆盖路径且不发网络`() {
        val jar = tempJar()
        val provisioner = ServerJarProvisioner(
            service = explodingService(),
            readEnv = { name -> if (name == McTestkitEnv.VELOCITY_JAR) jar.absolutePath else null },
        )
        assertEquals(jar.absoluteFile, provisioner.resolve("velocity").absoluteFile)
    }

    @Test
    fun `设置 BUNGEECORD_JAR 时返回覆盖路径且不发网络`() {
        val jar = tempJar()
        val provisioner = ServerJarProvisioner(
            service = explodingService(),
            readEnv = { name -> if (name == McTestkitEnv.BUNGEECORD_JAR) jar.absolutePath else null },
        )
        assertEquals(jar.absoluteFile, provisioner.resolve("bungeecord").absoluteFile)
    }

    @Test
    fun `后端 PAPER_JAR 覆盖（前缀拼出的逃生口）返回覆盖路径且不发网络`() {
        val jar = tempJar()
        val paperJarEnv = McTestkitEnv.PREFIX + "PAPER_JAR"
        val provisioner = ServerJarProvisioner(
            service = explodingService(),
            readEnv = { name -> if (name == paperJarEnv) jar.absolutePath else null },
        )
        assertEquals(jar.absoluteFile, provisioner.resolve("paper").absoluteFile)
    }

    @Test
    fun `JAR 覆盖指向不存在文件时抛中文错误`() {
        val provisioner = ServerJarProvisioner(
            service = explodingService(),
            readEnv = { name -> if (name == McTestkitEnv.WATERFALL_JAR) "/no/such/path/x.jar" else null },
        )
        val ex = assertFailsWith<IllegalStateException> { provisioner.resolve("waterfall") }
        assertTrue(ex.message!!.contains(McTestkitEnv.WATERFALL_JAR), "应点名是哪个环境变量：${ex.message}")
        assertTrue(ex.message!!.contains("不存在"), "应提示文件不存在：${ex.message}")
    }

    @Test
    fun `不支持平台抛中文错误`() {
        val provisioner = ServerJarProvisioner(explodingService(), readEnv = { null })
        val ex = assertFailsWith<IllegalArgumentException> { provisioner.resolve("spigot") }
        assertTrue(ex.message!!.contains("不支持的平台"), "应提示平台不支持：${ex.message}")
    }

    @Test
    fun `VERSION 覆盖被采纳并传给下载核心`() {
        // 用「记录入参的下载核心」断言版本透传：env *_VERSION > 请求版本
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(
            service = captured.asService(),
            readEnv = { name -> if (name == McTestkitEnv.VELOCITY_VERSION) "3.3.0" else null },
        )
        provisioner.resolve("velocity", requestedVersion = "1.0.0")
        assertEquals("3.3.0", captured.lastVersion)
    }

    @Test
    fun `无 VERSION 覆盖时采纳请求版本`() {
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(captured.asService(), readEnv = { null })
        provisioner.resolve("paper", requestedVersion = "1.21.4")
        assertEquals("1.21.4", captured.lastVersion)
    }

    @Test
    fun `无 VERSION 覆盖且无请求版本时回退到缺省`() {
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(captured.asService(), readEnv = { null })
        provisioner.resolve("paper")
        assertEquals(McTestkitDefaults.MINECRAFT_VERSION, captured.lastVersion)
    }

    @Test
    fun `Waterfall 请求版本按 major_minor 截断后下发（完整 MC 版本会 404）`() {
        // 复现：Waterfall 在 PaperMC 仅按 major.minor 发布（如 1.20），传入后端完整版本 1.20.1
        // 会请求 .../projects/waterfall/versions/1.20.1 而 404。解析应把版本截到 1.20 再下发。
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(captured.asService(), readEnv = { null })
        provisioner.resolve("waterfall", requestedVersion = "1.20.1")
        assertEquals("1.20", captured.lastVersion)
    }

    @Test
    fun `Waterfall 截断随后端版本变化（1_21_4 取 1_21）`() {
        // 证明请求版本（= 编排传入的后端版本）确实透传并按平台粒度截断，而非固定到缺省。
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(captured.asService(), readEnv = { null })
        provisioner.resolve("waterfall", requestedVersion = "1.21.4")
        assertEquals("1.21", captured.lastVersion)
    }

    @Test
    fun `Paper 后端版本不被截断（保留补丁号）`() {
        // 反向守卫：major.minor 截断只对 Waterfall 生效，后端 Paper/Folia 仍按完整 MC 版本下发。
        val captured = CapturingService()
        val provisioner = ServerJarProvisioner(captured.asService(), readEnv = { null })
        provisioner.resolve("paper", requestedVersion = "1.20.1")
        assertEquals("1.20.1", captured.lastVersion)
    }

    /**
     * 记录下载核心收到的版本，且**全程不发网络**。
     *
     * 机制：PaperMC fetchText 返回一个合法的"版本响应"（含构建号 1），并从被请求的 URL
     * 反解出版本（URL 形如 `.../versions/<version>`）；同时预置该构建的缓存 jar，使
     * [JarProvisionService] 在"命中缓存"分支即返回、不触发 download。这样既验证版本透传，
     * 又走真实解析路径而非哨兵异常。每个实例用独立缓存根，避免用例间串扰。
     */
    private class CapturingService {
        var lastVersion: String? = null
        private val cacheRoot = File("build/capture-cache-${System.nanoTime()}")
        private val cache = JarCache(cacheRoot)

        fun asService(): JarProvisionService = JarProvisionService(
            cache = cache,
            paperApi = PaperDownloadsApi(fetchText = { url ->
                // URL 形如 .../projects/<project>/versions/<version>/builds；project.id 即平台 id
                val version = url.substringAfter("/versions/").substringBefore("/builds")
                val project = url.substringAfter("/projects/").substringBefore("/versions/")
                lastVersion = version
                // 预置构建 1 的缓存 jar（按被请求平台 + 版本），使解析命中缓存、不下载
                cache.jarFile(ProvisionPlatform.fromId(project), version, 1).apply {
                    parentFile?.mkdirs()
                    writeText("dummy")
                }
                // 合法构建列表响应：最新构建 id 为 1
                """[{"id":1,"channel":"STABLE"}]"""
            }),
            bungeeApi = BungeeCordJenkinsApi(fetchText = { error("本用例不测 BungeeCord") }),
            download = { _, _, _ -> error("不应发网络：命中缓存路径不应下载") },
        )
    }
}
