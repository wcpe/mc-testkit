package com.example.e2e

import org.bukkit.configuration.file.FileConfiguration
import top.wcpe.mc.testkit.harness.McTestkitEnv

/**
 * 桩插件业务配置（消费方私有，照抄物，纯数据）。
 *
 * 只保留**业务相关**的时间窗口项。协议胶水（场景 id、结果文件路径、serve 空闲判断）由
 * 共享库 [McTestkitHarnessPlugin] 基类经环境变量解析，不在此重复。
 *
 * @property waitForPlayerSeconds 等待首个真实玩家入服的超时（秒）。
 * @property scenarioTimeoutSeconds 单场景整体超时（秒）。
 * @property shutdownDelayTicks 判定后到关服的延迟（ticks）。
 * @property stressDurationSeconds 持续压测场景的施压时长（秒，压测编排）；首个 bot 加入起计时，到时聚合收尾。
 */
data class HarnessConfig(
    val waitForPlayerSeconds: Long,
    val scenarioTimeoutSeconds: Long,
    val shutdownDelayTicks: Long,
    val stressDurationSeconds: Long,
) {
    companion object {
        /** 从 Bukkit FileConfiguration 读出强类型配置；字段名对齐 config.yml（kebab-case）。 */
        fun from(config: FileConfiguration): HarnessConfig = HarnessConfig(
            waitForPlayerSeconds = config.getLong("wait-for-player-seconds", 180L),
            scenarioTimeoutSeconds = config.getLong("scenario-timeout-seconds", 240L),
            shutdownDelayTicks = config.getLong("shutdown-delay-ticks", 20L),
            // 压测时长优先取编排下发的 env（与 bot 同源），缺失回退 config.yml 默认
            stressDurationSeconds = McTestkitEnv.envOrNull(McTestkitEnv.STRESS_DURATION_SECONDS)?.toLongOrNull()
                ?: config.getLong("stress-duration-seconds", 60L),
        )
    }
}
