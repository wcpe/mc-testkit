package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.config.DependencyInjections
import top.wcpe.mc.testkit.contract.McTestkitEnv
import java.io.File

/**
 * 依赖注入声明快照（任务自动编排）。
 *
 * 注册期（配置期）从 `mcTestkit { dependencies { } }` 提取并固化的纯值（被测插件声明 + 依赖插件声明列表），
 * 实现 [java.io.Serializable] 供任务动作闭包安全捕获（配置缓存要求动作捕获图可序列化，不得捕获
 * DSL 扩展对象或 `Project`）。
 *
 * @property pluginUnderTest 待测插件 jar 声明：环境变量名或路径；null 表示未声明。
 * @property plugins 依赖插件 jar 声明列表：环境变量名或路径，按声明顺序。
 * @property pluginUnderTestSelfJar 是否为自测模式（[pluginUnderTest] 未显式声明，由框架回退为本模块
 *   `jar` 产物）。此时解析期 `MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 优先于 jar 产物路径，且任务自动编排
 *   会把 prepare / e2e 任务自动接到 `jar` 任务上。
 */
data class DependencyDeclarations(
    val pluginUnderTest: String?,
    val plugins: List<String>,
    val pluginUnderTestSelfJar: Boolean = false,
) : java.io.Serializable

/**
 * 一项待注入运行目录的插件 jar（已解析为存在的文件）。
 *
 * @property declaration DSL 里写的原始值（环境变量名或路径，用于报错时回指）。
 * @property jar 解析得到的 jar 文件（已确认存在）。
 * @property underTest 是否为被测插件（`pluginUnderTest`）；注入时可据此改名便于辨识。
 */
data class ResolvedPluginJar(
    val declaration: String,
    val jar: File,
    val underTest: Boolean,
)

/**
 * 把依赖注入声明快照解析为待注入 jar（任务自动编排 整合器，纯函数）。
 *
 * 每个声明值（[DependencyDeclarations.pluginUnderTest] / [DependencyDeclarations.plugins] 项）是
 * **环境变量名或路径**（API.md §3.1：运行期解析以求可移植，不写死本机绝对路径）。解析规则：
 * 1. 先当环境变量名查（经注入的 [readEnv] 取值器）——查到非空值则按该值当路径。
 * 2. 否则把声明值本身当路径。
 * 3. 路径指向的文件不存在 → 经 [DependencyInjections.requireAll] 抛**中文** `GradleException`
 *    （列出缺哪些、可经哪个环境变量补，对齐 API.md §2 错误约定）。
 *
 * 经 `readEnv` 注入取值器（任务侧传执行期环境取值器），保持纯函数边界、不耦合 Gradle `Project`，
 * 便于用临时文件 + 替身取值器穷举单测。
 *
 * @param declarations DSL 依赖注入声明快照。
 * @param readEnv 环境变量取值器：给名、返回值（无则 null）。
 * @return 已解析、文件均存在的待注入 jar 列表（被测插件在前，按声明顺序）。
 * @throws org.gradle.api.GradleException 任一声明解析不到存在的 jar 时抛中文错误。
 */
fun resolveDependencyJars(
    declarations: DependencyDeclarations,
    readEnv: (String) -> String?,
): List<ResolvedPluginJar> {
    // 收集全部声明：被测插件（若声明）在前，其余依赖按声明顺序在后
    data class Declaration(val value: String, val underTest: Boolean, val selfJar: Boolean = false)

    val declarationList = buildList {
        declarations.pluginUnderTest?.takeIf { it.isNotBlank() }?.let {
            add(
                Declaration(
                    it,
                    underTest = true,
                    selfJar = declarations.pluginUnderTestSelfJar,
                ),
            )
        }
        declarations.plugins.forEach { add(Declaration(it, underTest = false)) }
    }

    val resolved = mutableListOf<ResolvedPluginJar>()
    // 注入项名 → 是否已提供（保持声明顺序，缺项在报错里同序列出）
    val presence = LinkedHashMap<String, Boolean>()
    // 注入项名 → 对应的环境变量逃生口提示（声明值本身像环境变量名时给出）
    val hints = LinkedHashMap<String, String>()

    declarationList.forEach { declaration ->
        val path =
            if (declaration.selfJar) {
                // 自测模式：显式覆盖环境变量优先（CI / GradleRunner 注入），否则用本模块 jar 产物
                readEnv(McTestkitEnv.PLUGIN_UNDER_TEST_JAR)?.takeIf { it.isNotBlank() } ?: declaration.value
            } else {
                readEnv(declaration.value)?.takeIf { it.isNotBlank() } ?: declaration.value
            }
        val jar = File(path)
        val present = jar.isFile
        presence[declaration.value] = present
        // 声明值看起来像环境变量名（全大写 + 下划线）时，提示消费方可经它提供
        if (looksLikeEnvName(declaration.value)) {
            hints[declaration.value] = declaration.value
        }
        if (present) {
            resolved += ResolvedPluginJar(declaration.value, jar, declaration.underTest)
        }
    }

    // 自测模式缺 jar 单独报错（指路更准：e2e 任务已自动依赖 jar，手动单跑 prepare 才会走到这）
    if (declarations.pluginUnderTestSelfJar) {
        val declared = declarations.pluginUnderTest.orEmpty()
        if (presence[declared] == false) {
            val jarPath =
                File(readEnv(McTestkitEnv.PLUGIN_UNDER_TEST_JAR)?.takeIf { it.isNotBlank() } ?: declared)
            throw GradleException(
                "mc-testkit 自测模式：未找到本模块插件 jar：${jarPath.absolutePath}\n" +
                    "  e2e 任务已自动依赖 jar 任务（先打包再测试）；若手动只跑了 prepare，请先执行「gradlew jar」。\n" +
                    "  若被测插件不在本模块，请显式声明 mcTestkit { dependencies { pluginUnderTest = <路径或环境变量名> } }。",
            )
        }
    }

    // 任一缺失 → 统一抛中文错误（列出缺哪些、怎么补）
    DependencyInjections.requireAll(presence, hints)
    return resolved
}

/** 判断声明值是否像环境变量名（全大写字母 / 数字 / 下划线，且非纯数字），用于在缺失报错里给逃生口提示。 */
private fun looksLikeEnvName(value: String): Boolean =
    value.isNotEmpty() &&
        value.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        value.any { it.isLetter() }
