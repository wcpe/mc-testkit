package top.wcpe.mc.testkit.dsl

import org.gradle.api.GradleException

/**
 * 把 [VersionMatrixSpec] 展开为 extension 上的 backend + scenario 声明。
 *
 * 在 [top.wcpe.mc.testkit.McTestkitPlugin] 的 `afterEvaluate` 中、拓扑解析与任务注册**之前**调用，
 * 使矩阵与手写 backend/scenario 走同一套拓扑校验与任务编排。
 */
object VersionMatrixExpander {

    /**
     * 展开全部已声明的版本矩阵（幂等：只在空 backend 侧追加，不重复展开）。
     *
     * @throws GradleException 矩阵名空、无条目、key 重复、与既有 backend/scenario 撞名时（中文报错）。
     */
    fun expandAll(extension: McTestkitExtension) {
        extension.declaredVersionMatrices.forEach { matrix -> expandOne(extension, matrix) }
    }

    private fun expandOne(extension: McTestkitExtension, matrix: VersionMatrixSpec) {
        if (matrix.name.isBlank()) {
            throw GradleException("mcTestkit versionMatrix 名称不能为空。")
        }
        if (matrix.entries.isEmpty()) {
            throw GradleException(
                "mcTestkit versionMatrix「${matrix.name}」未声明任何版本条目；" +
                    "请用 entry(\"1.20.1\") 或 versions(\"1.20.1\", \"1.21.1\") 添加版本。",
            )
        }
        if (matrix.portBase < 1 || matrix.portBase > 65500) {
            throw GradleException(
                "mcTestkit versionMatrix「${matrix.name}」portBase=${matrix.portBase} 非法；" +
                    "请使用 1–65500 之间的端口基数（条目端口 = portBase + 序号）。",
            )
        }

        val seenKeys = mutableSetOf<String>()
        val existingBackends = extension.declaredBackends.map { it.name }.toMutableSet()
        val existingScenarios = extension.declaredScenarios.map { it.name }.toMutableSet()

        matrix.entries.forEachIndexed { index, entry ->
            if (entry.version.isBlank()) {
                throw GradleException("mcTestkit versionMatrix「${matrix.name}」第 ${index + 1} 条 version 为空。")
            }
            if (entry.key.isBlank()) {
                throw GradleException(
                    "mcTestkit versionMatrix「${matrix.name}」条目 ${entry.version} 的 key 为空；" +
                        "请显式指定 key 或使用可推导数字的版本号。",
                )
            }
            if (!seenKeys.add(entry.key)) {
                throw GradleException(
                    "mcTestkit versionMatrix「${matrix.name}」条目 key「${entry.key}」重复；" +
                        "请为每个条目指定唯一 key。",
                )
            }

            val backendName = matrix.backendNamePrefix + entry.key
            if (backendName in existingBackends) {
                throw GradleException(
                    "mcTestkit versionMatrix「${matrix.name}」将生成后端「$backendName」，但该名称已存在；" +
                        "请改 backendNamePrefix 或条目 key。",
                )
            }
            extension.backend(backendName) {
                platform = matrix.platform
                version = entry.version
                port = matrix.portBase + index
            }
            existingBackends += backendName

            val scenarioName = if (entry.bot) "full-${entry.key}" else "smoke-${entry.key}"
            if (scenarioName in existingScenarios) {
                throw GradleException(
                    "mcTestkit versionMatrix「${matrix.name}」将生成场景「$scenarioName」，但该名称已存在；" +
                        "请改条目 key 避免与手写场景撞名。",
                )
            }
            if (entry.bot) {
                extension.scenario(scenarioName) {
                    backend = backendName
                    bot {
                        username = matrix.botUsernamePrefix + entry.key
                        action = matrix.botAction
                    }
                }
            } else {
                extension.scenario(scenarioName) {
                    backend = backendName
                }
            }
            existingScenarios += scenarioName
        }
    }
}
