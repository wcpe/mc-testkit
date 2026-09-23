package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.config.DependencyInjections
import top.wcpe.mc.testkit.contract.McTestkitEnv
import java.io.File
import java.util.Locale

/**
 * 被测插件注入到后端运行目录时的**固定**目标文件名（其余依赖按自身 jar 文件名注入）。
 *
 * 与 `injectBackendDependencies`（注入侧）同源：目标文件名的口径必须在预检期与注入期一致，
 * 否则预检查的不是实际会撞名的那个名字。
 */
internal const val UNDER_TEST_TARGET_FILE_NAME = "plugin-under-test.jar"

/**
 * 依赖注入声明快照（任务自动编排）。
 *
 * 注册期（配置期）从 `mcTestkit { dependencies { } }` 提取并固化的纯值（被测插件声明 + 依赖插件声明列表），
 * 实现 [java.io.Serializable] 供任务动作闭包安全捕获（配置缓存要求动作捕获图可序列化，不得捕获
 * DSL 扩展对象或 `Project`）。
 *
 * @property pluginUnderTest 待测插件 jar 声明：环境变量名或路径；null 表示未声明。
 * @property plugins 依赖插件 jar 声明列表：环境变量名或路径，按声明顺序。
 * @property mavenPlugins 依赖插件 jar 的 **Maven 坐标**声明列表（`group:artifact:version`，按声明顺序）。
 *   坐标本身是纯字符串（可序列化），其制品由 Gradle 原生依赖解析在需要该任务时落成 jar，
 *   经 [MavenCoordinateSources] 传入执行期（见 `resolveDependencyJars` 的 `resolvedCoordinates`）。
 * @property pluginUnderTestSelfJar 是否为自测模式（[pluginUnderTest] 未显式声明，由框架回退为本模块
 *   `jar` 产物）。此时解析期 `MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 优先于 jar 产物路径，且任务自动编排
 *   会把 prepare / e2e 任务自动接到 `jar` 任务上。
 */
data class DependencyDeclarations(
    val pluginUnderTest: String?,
    val plugins: List<String>,
    val pluginUnderTestSelfJar: Boolean = false,
    val mavenPlugins: List<String> = emptyList(),
) : java.io.Serializable

/**
 * 一项待注入运行目录的插件 jar（已解析为存在的文件）。
 *
 * @property declaration DSL 里写的原始值（环境变量名 / 路径 / Maven 坐标，用于报错时回指）。
 * @property jar 解析得到的 jar 文件（已确认存在）。
 * @property underTest 是否为被测插件（`pluginUnderTest`）；注入时可据此改名便于辨识。
 */
data class ResolvedPluginJar(
    val declaration: String,
    val jar: File,
    val underTest: Boolean,
)

/** 环境变量逃生口提示：声明值像环境变量名时给出。 */
private const val ENV_HINT_TEMPLATE = "可经环境变量 %s 提供其 jar / 路径"

/** 坐标已声明却未进入解析结果时的提示（缺失）。 */
private const val COORDINATE_MISSING_HINT =
    "未解析到该坐标的本地制品；可能原因：坐标拼写错误 / 该坐标的仓库未在本构建声明" +
        "（消费方启用 RepositoriesMode.PREFER_SETTINGS 时只有 settings 级仓库生效）/ 拉取凭据缺失"

/** 坐标解析抛异常时的排查指引（无法解析）。 */
private const val COORDINATE_FAILURE_GUIDANCE =
    "请检查：坐标拼写是否正确；该坐标所在的仓库是否已在本构建中声明（消费方启用 " +
        "RepositoriesMode.PREFER_SETTINGS 时只有 settings 级仓库生效，项目级 repositories 会被忽略）；" +
        "拉取该坐标所需的凭据是否已由消费方自己的 Gradle 配置提供。"

/**
 * 把「坐标 → 惰性文件集合」落成「坐标 → jar」（任务自动编排 → 依赖注入，纯函数）。
 *
 * 由任务侧在**执行期**注入取值器 `resolveOne`（内部调 Gradle 原生依赖解析），本函数只负责
 * 逐个坐标收集结果、把失败聚成一条**中文**错误——保持纯函数边界，便于用替身取值器穷举单测
 * （与 [resolveDependencyJars] 同一风格）。
 *
 * 逐个坐标独立解析（而非一次性解析全部坐标），失败时才能明确指出**是哪个坐标**没解析到。
 *
 * @param coordinates 消费方声明的坐标（`group:artifact:version`，按声明顺序）。
 * @param resolveOne 单个坐标的取值器：返回该坐标的 jar；解析失败即抛异常（原样用于报错原因）。
 * @return 坐标 → 已解析的 jar（顺序同 [coordinates]）。
 * @throws GradleException 任一坐标解析失败时抛出，文案为中文、逐个列出坐标与 Gradle 给出的原因。
 */
fun resolveMavenCoordinates(
    coordinates: List<String>,
    resolveOne: (String) -> File,
): Map<String, File> {
    val resolved = LinkedHashMap<String, File>()
    val failures = LinkedHashMap<String, String>()
    coordinates.forEach { coordinate ->
        try {
            resolved[coordinate] = resolveOne(coordinate)
        } catch (exception: Exception) {
            failures[coordinate] = failureReasonOf(exception)
        }
    }
    if (failures.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("mc-testkit 无法解析以下依赖插件的 Maven 坐标（Gradle 原生依赖解析失败）：")
                failures.forEach { (coordinate, reason) -> appendLine("  - $coordinate（$reason）") }
                append(COORDINATE_FAILURE_GUIDANCE)
            },
        )
    }
    return resolved
}

/**
 * 从解析异常里抽出**对消费方有用**的一行作为原因。
 *
 * 真实 Gradle 解析失败是**嵌套异常**：顶层 message 只有泛泛的「Could not resolve all files for
 * configuration ':detached…'」，真正「找不到哪个制品」（`Could not find io.papermc.paper:paper:99.99.99.`）
 * 在其 `cause` 链里，末尾还挂着一长串「搜过哪些位置」噪音行。故这里**遍历 cause 链**收集所有消息行，
 * 按优先级挑：先取点明制品的那行，再退而取首行实质内容；跳过搜索位置 / 依赖来源等噪音——否则报错只会
 * 把坐标名再复述一遍，却不给原因。
 */
private fun failureReasonOf(exception: Exception): String {
    val lines = generateSequence(exception as Throwable, { throwable: Throwable -> throwable.cause })
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { throwable -> throwable.message }
        .flatMap { message -> message.lineSequence() }
        .map { line -> line.trim() }
        .filter { line ->
            line.isNotEmpty() &&
                !line.startsWith("-") &&
                !line.startsWith("Searched in") &&
                !line.startsWith("Required by")
        }
        .toList()
    return lines.firstOrNull { it.contains("Could not find") || it.contains("Could not get") }
        ?: lines.firstOrNull { !it.startsWith("Could not resolve all files") }
        ?: lines.firstOrNull()
        ?: exception.javaClass.simpleName
}

/** cause 链遍历深度上限（防异常自引用成环）。 */
private const val MAX_CAUSE_DEPTH = 10

/**
 * 把依赖注入声明快照解析为待注入 jar（任务自动编排 整合器，纯函数）。
 *
 * 两类声明分别解析：
 * - **环境变量名或路径**（[DependencyDeclarations.pluginUnderTest] / [DependencyDeclarations.plugins] 项，
 *   API.md §3.1：运行期解析以求可移植，不写死本机绝对路径）：
 *   1. 先当环境变量名查（经注入的 [readEnv] 取值器）——查到非空值则按该值当路径。
 *   2. 否则把声明值本身当路径。
 * - **Maven 坐标**（[DependencyDeclarations.mavenPlugins] 项）：从注入的 [resolvedCoordinates] 取值
 *   （由任务侧在**执行期**经 Gradle 原生依赖解析落成 jar；本函数不摸 `Project`、不触网）。
 *   坐标解析失败（Gradle 抛异常）由 [resolveMavenCoordinates] 在执行期提前报中文错，走不到这里。
 *
 * 任一声明解析不到存在的 jar → 经 [DependencyInjections.requireAll] 抛**中文** `GradleException`
 * （列出缺哪些、可经哪个环境变量补 / 坐标为何没解析到，对齐 API.md §2 错误约定）。
 *
 * 顺序：被测插件（若声明）在前；随后按声明顺序的 [DependencyDeclarations.plugins]；最后按声明顺序的
 * [DependencyDeclarations.mavenPlugins]。
 *
 * 经 `readEnv` 注入取值器（任务侧传执行期环境取值器），保持纯函数边界、不耦合 Gradle `Project`，
 * 便于用临时文件 + 替身取值器穷举单测。
 *
 * @param declarations DSL 依赖注入声明快照。
 * @param resolvedCoordinates Maven 坐标 → 已解析 jar（任务侧执行期经 [resolveMavenCoordinates] 注入）。
 * @param readEnv 环境变量取值器：给名、返回值（无则 null）。
 * @return 已解析、文件均存在的待注入 jar 列表（被测插件在前，按声明顺序）。
 * @throws org.gradle.api.GradleException 任一声明解析不到存在的 jar 时抛中文错误。
 */
fun resolveDependencyJars(
    declarations: DependencyDeclarations,
    resolvedCoordinates: Map<String, File> = emptyMap(),
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
    // 注入项名 → 补充说明（环境变量逃生口 / 坐标未解析到的可能原因）
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
            hints[declaration.value] = ENV_HINT_TEMPLATE.format(declaration.value)
        }
        if (present) {
            resolved += ResolvedPluginJar(declaration.value, jar, declaration.underTest)
        }
    }

    // Maven 坐标声明：取值自执行期解析结果，缺失（未解析到）与路径 / 环境变量缺失同样进统一中文报错
    declarations.mavenPlugins.forEach { coordinate ->
        val jar = resolvedCoordinates[coordinate]
        if (jar != null && jar.isFile) {
            presence[coordinate] = true
            resolved += ResolvedPluginJar(coordinate, jar, underTest = false)
        } else {
            presence[coordinate] = false
            hints[coordinate] = COORDINATE_MISSING_HINT
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

    // 多份声明落到同一目标文件名 → 预检期中文失败（对齐 API.md §1「目标文件名冲突即在启动前失败」）
    requireDistinctTargetFileNames(resolved)
    return resolved
}

/**
 * 校验注入到后端运行目录的依赖插件**目标文件名互不重复**（API.md §1 的启动前资源预检契约）。
 *
 * 目标文件名 = 被测插件的固定名 [UNDER_TEST_TARGET_FILE_NAME] / 其余按 jar 文件名。注入期对同名目标是
 * **直接覆盖**，即后者静默顶掉前者、消费方无从察觉哪份生效；且多来源（路径 / 环境变量 / Maven 坐标）
 * 混用时撞名更易发生。故在预检期（清理运行目录之前）即中文报错，与代理插件的同名校验同口径。
 *
 * @throws GradleException 存在两份及以上声明落到同一目标文件名时抛出。
 */
private fun requireDistinctTargetFileNames(dependencies: List<ResolvedPluginJar>) {
    val conflict = dependencies
        .groupBy { targetFileNameOf(it).lowercase(Locale.ROOT) }
        .entries
        .firstOrNull { it.value.size > 1 } ?: return
    val declarations = conflict.value.map { it.declaration }
    throw GradleException(
        "mc-testkit 后端依赖插件的目标文件名冲突：「${targetFileNameOf(conflict.value.first())}」" +
            "由 ${declarations.size} 份声明解析得到；请让各依赖插件使用不同文件名，避免含糊覆盖。\n" +
            "  冲突声明：${declarations.joinToString("、")}",
    )
}

/** 该依赖注入后端运行目录后的目标文件名（注入侧同口径，见 [UNDER_TEST_TARGET_FILE_NAME]）。 */
private fun targetFileNameOf(dependency: ResolvedPluginJar): String =
    if (dependency.underTest) UNDER_TEST_TARGET_FILE_NAME else dependency.jar.name

/** 判断声明值是否像环境变量名（全大写字母 / 数字 / 下划线，且非纯数字），用于在缺失报错里给逃生口提示。 */
private fun looksLikeEnvName(value: String): Boolean =
    value.isNotEmpty() &&
        value.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        value.any { it.isLetter() }
