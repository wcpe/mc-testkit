package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.config.DependencyInjections
import top.wcpe.mc.testkit.dsl.DependenciesSpec
import java.io.File

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
 * 把 `mcTestkit { dependencies { } }` 声明解析为待注入 jar（FR-04 整合器，纯函数）。
 *
 * 每个声明值（[DependenciesSpec.pluginUnderTest] / [DependenciesSpec.plugins] 项）是**环境变量名或路径**
 * （API.md §3.1：运行期解析以求可移植，不写死本机绝对路径）。解析规则：
 * 1. 先当环境变量名查（经注入的 [readEnv] 取值器）——查到非空值则按该值当路径。
 * 2. 否则把声明值本身当路径。
 * 3. 路径指向的文件不存在 → 经 [DependencyInjections.requireAll] 抛**中文** `GradleException`
 *    （列出缺哪些、可经哪个环境变量补，对齐 API.md §2 错误约定）。
 *
 * 经 `readEnv` 注入取值器（任务侧传 `project.providers.environmentVariable(name).orNull`），保持纯函数
 * 边界、不耦合 Gradle `Project`，便于用临时文件 + 替身取值器穷举单测。
 *
 * @param dependencies DSL 依赖注入声明。
 * @param readEnv 环境变量取值器：给名、返回值（无则 null）。
 * @return 已解析、文件均存在的待注入 jar 列表（被测插件在前，按声明顺序）。
 * @throws org.gradle.api.GradleException 任一声明解析不到存在的 jar 时抛中文错误。
 */
fun resolveDependencyJars(
    dependencies: DependenciesSpec,
    readEnv: (String) -> String?,
): List<ResolvedPluginJar> {
    // 收集全部声明：被测插件（若声明）在前，其余依赖按声明顺序在后
    data class Declaration(val value: String, val underTest: Boolean)

    val declarations = buildList {
        dependencies.pluginUnderTest?.takeIf { it.isNotBlank() }?.let { add(Declaration(it, underTest = true)) }
        dependencies.plugins.forEach { add(Declaration(it, underTest = false)) }
    }

    val resolved = mutableListOf<ResolvedPluginJar>()
    // 注入项名 → 是否已提供（保持声明顺序，缺项在报错里同序列出）
    val presence = LinkedHashMap<String, Boolean>()
    // 注入项名 → 对应的环境变量逃生口提示（声明值本身像环境变量名时给出）
    val hints = LinkedHashMap<String, String>()

    declarations.forEach { declaration ->
        val byEnv = readEnv(declaration.value)?.takeIf { it.isNotBlank() }
        val path = byEnv ?: declaration.value
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

    // 任一缺失 → 统一抛中文错误（列出缺哪些、怎么补）
    DependencyInjections.requireAll(presence, hints)
    return resolved
}

/** 判断声明值是否像环境变量名（全大写字母 / 数字 / 下划线，且非纯数字），用于在缺失报错里给逃生口提示。 */
private fun looksLikeEnvName(value: String): Boolean =
    value.isNotEmpty() &&
        value.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        value.any { it.isLetter() }
