package top.wcpe.mc.testkit.config

import org.gradle.api.GradleException

/**
 * 依赖注入缺失校验（环境契约）。
 *
 * 被测插件常依赖数据源 / Redis 等服务正确注入才启动；缺失若静默继续，会到运行期才炸得莫名其妙。
 * 本对象提供**通用机制**：给定「必需注入项（名 → 是否已提供）」，缺失即在配置期抛**中文**
 * [GradleException]，说明缺什么、怎么补——**不写死**具体依赖插件 / 数据源 / Redis 名（那是消费方
 * 在 `dependencies { }` 声明的，本对象只负责"缺失 → 中文报错"，对齐 docs/API.md §2）。
 */
object DependencyInjections {

    /**
     * 要求所有注入项均已提供，否则抛中文错误。
     *
     * @param injections 注入项名 → 是否已提供（`true` 已提供 / `false` 缺失）。用 `LinkedHashMap`
     *   可让缺项在报错里保持声明顺序。
     * @param hintEnvByName 可选：每个注入项名 → 对应的环境变量逃生口（如 `MC_TESTKIT_E2E_*_JAR` /
     *   `SERVER_TEMPLATE_DIR`），在缺失报错里提示消费方如何补。
     * @throws GradleException 存在缺失注入项时抛出，文案为中文、列出缺哪些 / 怎么补。
     */
    fun requireAll(
        injections: Map<String, Boolean>,
        hintEnvByName: Map<String, String> = emptyMap(),
    ) {
        val missing = injections.filterValues { !it }.keys
        if (missing.isEmpty()) {
            return
        }
        val details = missing.joinToString("\n") { name ->
            val hint = hintEnvByName[name]
            if (hint != null) {
                "  - $name（可经环境变量 $hint 提供其 jar / 路径）"
            } else {
                "  - $name"
            }
        }
        throw GradleException(
            "mc-testkit 缺少必需的依赖注入，无法启动被测后端：\n$details\n" +
                "请在运行环境备齐上述依赖（提供对应 jar / 服务），或经 mcTestkit { dependencies { } } 与相应环境变量注入后重试。",
        )
    }
}
