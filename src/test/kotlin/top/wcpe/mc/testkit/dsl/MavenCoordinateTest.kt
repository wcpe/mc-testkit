package top.wcpe.mc.testkit.dsl

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Maven 坐标声明校验单测（依赖注入 DSL，配置期）。
 *
 * 覆盖：三段式合法 / 非法、动态版本与版本区间被拒、`-SNAPSHOT` 放行。纯函数、零 Gradle 工程。
 */
class MavenCoordinateTest {

    @ParameterizedTest(name = "[{index}] 坐标 {0} 应判为合法")
    @ValueSource(
        strings = [
            "com.example:foo-plugin:1.2.0",
            "top.wcpe.mc:harness-core:0.1.1",
            "com.example:foo:1.2.0-SNAPSHOT",
            "a:b:1",
            "com.example:foo_bar-baz:26.2.0",
        ],
    )
    @DisplayName("合法三段式坐标（含 -SNAPSHOT）应通过校验并原样返回")
    fun acceptValidCoordinates(coordinate: String) {
        assertEquals(coordinate, MavenCoordinate.requireValid(coordinate))
        assertEquals(null, MavenCoordinate.invalidReason(coordinate))
    }

    @ParameterizedTest(name = "[{index}] 坐标 {0} 应判为非法")
    @ValueSource(
        strings = [
            "",
            "   ",
            " com.example:foo:1.0.0",
            "com.example:foo:1.0.0 ",
            "com.example:foo",
            "com.example",
            "com.example:foo:1.0.0:extra",
            "com.example::1.0.0",
            "com.example:foo:",
            ":foo:1.0.0",
            "com.example:foo:1.0.0 beta",
            "com example:foo:1.0.0",
        ],
    )
    @DisplayName("非三段式 / 空段 / 含空白的坐标应被拒")
    fun rejectMalformedCoordinates(coordinate: String) {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid(coordinate) }
        assertTrue("Maven 坐标" in ex.message!!, ex.message)
        assertTrue("group:artifact:version" in ex.message!!, "报错应指明期望形态：${ex.message}")
    }

    @ParameterizedTest(name = "[{index}] 动态版本 {0} 应被拒")
    @ValueSource(strings = ["com.example:foo:1.0.+", "com.example:foo:+", "com.example:foo:latest.release", "com.example:foo:latest.integration", "com.example:foo:latest"])
    @DisplayName("动态版本（+ / latest.*）应被拒")
    fun rejectDynamicVersions(coordinate: String) {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid(coordinate) }
        assertTrue("可复现性" in ex.message!!, "报错应说明破坏可复现：${ex.message}")
    }

    @ParameterizedTest(name = "[{index}] 版本区间 {0} 应被拒")
    @ValueSource(strings = ["com.example:foo:[1.0,2.0)", "com.example:foo:(1.0,2.0]", "com.example:foo:[1.0,)", "com.example:foo:1.0,2.0"])
    @DisplayName("版本区间表达式应被拒")
    fun rejectVersionRanges(coordinate: String) {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid(coordinate) }
        assertTrue("可复现性" in ex.message!!, "报错应说明破坏可复现：${ex.message}")
    }

    @Test
    @DisplayName("版本含通配符 * 应被拒")
    fun rejectWildcardVersion() {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid("com.example:foo:1.*") }
        assertTrue("通配符" in ex.message!!, ex.message)
    }

    @Test
    @DisplayName("非法坐标的报错应给出可直接照抄的合法示例")
    fun rejectionMessageGuidesWriting() {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid("com.example:foo") }
        assertTrue("mavenPlugin(" in ex.message!!, "报错应给出 mavenPlugin(...) 用法示例：${ex.message}")
    }

    @ParameterizedTest(name = "[{index}] 含路径穿越的坐标 {0} 应被拒")
    @ValueSource(
        strings = [
            "com.example:foo:../../evil",
            "com.example:../bar:1.0.0",
            "com.example:foo/bar:1.0.0",
            "com.example:foo:1.0.0/../../x",
            "com\\example:foo:1.0.0",
            "com.example:.:1.0.0",
            "com.example:..:1.0.0",
        ],
    )
    @DisplayName("含路径分隔符或相对路径段的坐标应被拒（坐标会被用作缓存镜像路径）")
    fun rejectCoordinatesWithPathTraversal(coordinate: String) {
        val ex = assertFailsWith<GradleException> { MavenCoordinate.requireValid(coordinate) }
        assertTrue("路径" in ex.message!!, "报错应说明是路径相关原因：${ex.message}")
    }
}
