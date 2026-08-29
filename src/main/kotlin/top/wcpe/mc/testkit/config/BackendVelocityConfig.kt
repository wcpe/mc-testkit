package top.wcpe.mc.testkit.config

import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File

/**
 * 让后端进 Velocity modern forwarding 模式（FR-05，见 ADR-0010）。
 *
 * 经 Velocity（modern forwarding）跑场景时，两件事缺一不可，否则后端拒绝代理转发或玩家身份不一致：
 * 1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（离线 bot 经离线代理进服）。
 * 2. `config/paper-global.yml`（1.19+）：Velocity modern 配置——
 *    `secret` 须与代理 `forwarding.secret` 同值（都取 [McTestkitDefaults.VELOCITY_FORWARDING_SECRET]），
 *    后端据此校验代理转发的玩家身份。
 *
 * 1.17–1.18 因 FR13 兼容 Velocity 3.1.1 改走 legacy forwarding：复用 BungeeCord 后端配置，并显式关闭
 * `paper.yml settings.velocity-support.enabled`；1.19+ 仍保持 modern，不受影响。
 * YAML 用真实读写深合并（[editYaml] / [setNested]，snakeyaml），保留未涉及键；纯函数式，便于临时目录单测。
 */
object BackendVelocityConfig {

    /**
     * 对运行目录落地 Velocity modern forwarding 两件套（[secret] 默认取共享 secret，与代理同源）。
     *
     * @param version 后端 Minecraft 版本（决定 paper 配置文件名 + server.properties 键过滤，FR-21）。
     */
    fun apply(runDir: File, version: String, secret: String = McTestkitDefaults.VELOCITY_FORWARDING_SECRET) {
        if (usesLegacyForwarding(version)) {
            configureLegacyForwarding(runDir, version)
            return
        }
        val rawOverrides = mapOf(
            ServerProperties.ONLINE_MODE to "false",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
        )
        // 按版本过滤不支持的键（FR-21）
        val overrides = ServerProperties.versionAwareOverrides(version, rawOverrides)
        ServerProperties.edit(runDir, overrides, comment = "mc-testkit E2E Velocity 后端 server.properties")
        // 1.19+ 使用 paper-global.yml；旧版已在上方切换 legacy forwarding。
        if (MinecraftVersionGroup.needsPaperGlobal(version)) {
            editYaml(File(runDir, "config/paper-global.yml")) { root ->
                setNested(root, listOf("proxies", "velocity", "enabled"), true)
                setNested(root, listOf("proxies", "velocity", "online-mode"), false)
                setNested(root, listOf("proxies", "velocity", "secret"), secret)
            }
        }
    }

    private fun configureLegacyForwarding(runDir: File, version: String) {
        BackendBungeeCordConfig.apply(runDir, version)
        editYaml(File(runDir, "paper.yml")) { root ->
            setNested(root, listOf("settings", "velocity-support", "enabled"), false)
        }
    }

    private fun usesLegacyForwarding(version: String): Boolean =
        version.split('.').take(2).joinToString(".") in setOf("1.17", "1.18")
}
