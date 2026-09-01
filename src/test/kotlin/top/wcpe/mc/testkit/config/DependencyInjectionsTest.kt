package top.wcpe.mc.testkit.config

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitEnv
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 依赖注入缺失校验单元测试（环境契约，环境契约高风险区）。
 *
 * 验证「缺失 → 明确中文报错；齐全 → 不报错」，且机制通用、不写死具体依赖名。
 */
class DependencyInjectionsTest {

    @Test
    @DisplayName("存在缺失注入项时应抛出中文错误并列出缺项")
    fun missingInjectionsThrowChineseError() {
        val ex = assertFailsWith<GradleException> {
            DependencyInjections.requireAll(
                injections = linkedMapOf(
                    "SampleLib" to true,
                    "DataSource" to false,
                    "Redis" to false,
                ),
            )
        }
        val message = ex.message!!
        // 列出两个缺项，不提齐全项
        assertTrue(message.contains("DataSource"), "报错应含缺项 DataSource：$message")
        assertTrue(message.contains("Redis"), "报错应含缺项 Redis：$message")
        assertTrue(!message.contains("SampleLib"), "已提供项不应出现在缺失报错里：$message")
        // 含中文（机制说明怎么补）
        assertTrue(message.any { it.code in 0x4E00..0x9FFF }, "报错应为中文：$message")
    }

    @Test
    @DisplayName("缺失注入项带环境变量提示时错误应包含变量名")
    fun missingInjectionErrorIncludesHintEnvironmentVariable() {
        val ex = assertFailsWith<GradleException> {
            DependencyInjections.requireAll(
                injections = linkedMapOf("ServerTemplate" to false),
                hintEnvByName = mapOf("ServerTemplate" to McTestkitEnv.SERVER_TEMPLATE_DIR),
            )
        }
        assertTrue(
            ex.message!!.contains(McTestkitEnv.SERVER_TEMPLATE_DIR),
            "报错应给出对应环境变量逃生口：${ex.message}",
        )
    }

    @Test
    @DisplayName("全部注入项齐全时应通过校验且不报错")
    fun completeInjectionsPassValidation() {
        // 不抛异常即通过
        DependencyInjections.requireAll(
            injections = linkedMapOf(
                "SampleLib" to true,
                "DataSource" to true,
            ),
        )
    }

    @Test
    @DisplayName("注入项为空时应视为齐全且不报错")
    fun emptyInjectionsPassValidation() {
        DependencyInjections.requireAll(injections = emptyMap())
    }
}
