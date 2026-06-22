package com.example.e2e

import org.bukkit.configuration.file.FileConfiguration
import java.io.File

/**
 * 桩插件配置（照抄物，纯数据）。
 *
 * 只保留**框架无关**的通用字段：场景名、结果文件、各类超时。
 * 业务特定字段（商店 id、奖励物、货币等）不进通用骨架——消费方在自己分叉里加。
 *
 * @property scenario 本次执行的场景。
 * @property resultFile 结果文件（桩写出、编排读取判定）。
 * @property waitForPlayerSeconds 等待首个真实玩家入服的超时（秒）。
 * @property scenarioTimeoutSeconds 单场景整体超时（秒）。
 * @property shutdownDelayTicks 判定后到关服的延迟（ticks）。
 * @property stressDurationSeconds 持续压测场景的施压时长（秒，FR-11）；首个 bot 加入起计时，到时聚合收尾。
 */
data class HarnessConfig(
    val scenario: ScenarioName,
    val resultFile: File,
    val waitForPlayerSeconds: Long,
    val scenarioTimeoutSeconds: Long,
    val shutdownDelayTicks: Long,
    val stressDurationSeconds: Long,
) {
    companion object {
        /**
         * 从 Bukkit FileConfiguration 读出强类型配置；字段名对齐 config.yml（kebab-case）。
         *
         * 场景与结果文件路径**优先取编排下发的环境变量**（mc-testkit 冻结契约 docs/API.md §3.3：
         * `MC_TESTKIT_E2E_SCENARIO` / `MC_TESTKIT_E2E_RESULT_FILE`），缺失才回退 config.yml 默认——
         * 这样编排能把结果写到 verify 读取处（二者对齐），无需编排知道桩的配置格式。
         */
        fun from(config: FileConfiguration): HarnessConfig {
            val scenarioId = envOrNull("MC_TESTKIT_E2E_SCENARIO")
                ?: config.getString("scenario", ScenarioName.SMOKE.id)!!
            val resultPath = envOrNull("MC_TESTKIT_E2E_RESULT_FILE")
                ?: config.getString("result-file")
                ?: error("缺少结果文件路径：编排应经 MC_TESTKIT_E2E_RESULT_FILE 下发，或在 config.yml 配 result-file")
            return HarnessConfig(
                scenario = ScenarioName.from(scenarioId),
                resultFile = File(resultPath),
                waitForPlayerSeconds = config.getLong("wait-for-player-seconds", 180L),
                scenarioTimeoutSeconds = config.getLong("scenario-timeout-seconds", 240L),
                shutdownDelayTicks = config.getLong("shutdown-delay-ticks", 20L),
                // 压测时长优先取编排下发的 env（与 bot 同源），缺失回退 config.yml 默认
                stressDurationSeconds = envOrNull("MC_TESTKIT_E2E_STRESS_DURATION_SECONDS")?.toLongOrNull()
                    ?: config.getLong("stress-duration-seconds", 60L),
            )
        }

        /** 读环境变量，去空白；空 / 未设返回 null。 */
        private fun envOrNull(name: String): String? =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
