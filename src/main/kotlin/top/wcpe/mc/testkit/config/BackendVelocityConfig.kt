package top.wcpe.mc.testkit.config

import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File

/**
 * 让后端进 Velocity modern forwarding 模式（FR-05，见 ADR-0010）。
 *
 * 经 Velocity（modern forwarding）跑场景时，两件事缺一不可，否则后端拒绝代理转发或玩家身份不一致：
 * 1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（离线 bot 经离线代理进服）。
 * 2. `config/paper-global.yml`：`proxies.velocity.{enabled=true, online-mode=false, secret=<共享>}`——
 *    `secret` 须与代理 `forwarding.secret` 同值（都取 [McTestkitDefaults.VELOCITY_FORWARDING_SECRET]），
 *    后端据此校验代理转发的玩家身份。
 *
 * **不写** `spigot.yml settings.bungeecord`（那是 BungeeCord 模式专属，与 Velocity modern 互斥）。
 * YAML 用真实读写深合并（[editYaml] / [setNested]，snakeyaml），保留未涉及键；纯函数式，便于临时目录单测。
 */
object BackendVelocityConfig {

    /** 对运行目录落地 Velocity modern forwarding 两件套（[secret] 默认取共享 secret，与代理同源）。 */
    fun apply(runDir: File, secret: String = McTestkitDefaults.VELOCITY_FORWARDING_SECRET) {
        ServerProperties.edit(
            runDir,
            mapOf(
                ServerProperties.ONLINE_MODE to "false",
                ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ),
            comment = "mc-testkit E2E Velocity 后端 server.properties",
        )
        // config/paper-global.yml：proxies.velocity.{enabled,online-mode,secret}（深合并，不误改 bungee-cord 同级键）
        editYaml(File(runDir, "config/paper-global.yml")) { root ->
            setNested(root, listOf("proxies", "velocity", "enabled"), true)
            setNested(root, listOf("proxies", "velocity", "online-mode"), false)
            setNested(root, listOf("proxies", "velocity", "secret"), secret)
        }
    }
}
