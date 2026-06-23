package top.wcpe.mc.testkit.provision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JsonLite] 极简 JSON 解析器单元测试（FR-02）。
 *
 * 喂固定文本穷举对象 / 数组 / 标量 / 转义 / 嵌套 / 非法输入；纯函数，不打网络。
 */
class JsonLiteTest {

    @Test
    fun `解析对象与标量类型`() {
        val obj = JsonLite.asObject(JsonLite.parse("""{"name":"paper","build":42,"ratio":1.5,"ok":true,"none":null}"""))
        assertEquals("paper", obj["name"])
        assertEquals(42L, obj["build"])
        assertEquals(1.5, obj["ratio"])
        assertEquals(true, obj["ok"])
        assertTrue(obj.containsKey("none"))
        assertNull(obj["none"])
    }

    @Test
    fun `解析数组并保持顺序`() {
        val arr = JsonLite.asArray(JsonLite.parse("""[1,2,3,10]"""))
        assertEquals(listOf(1L, 2L, 3L, 10L), arr)
    }

    @Test
    fun `解析嵌套对象与数组`() {
        val obj = JsonLite.asObject(JsonLite.parse("""{"builds":[{"build":7},{"build":8}]}"""))
        val builds = JsonLite.asArray(obj["builds"])
        assertEquals(2, builds.size)
        assertEquals(8L, JsonLite.asObject(builds[1])["build"])
    }

    @Test
    fun `解析字符串转义`() {
        val obj = JsonLite.asObject(JsonLite.parse("""{"k":"a\"b\\c\n\tA"}"""))
        assertEquals("a\"b\\c\n\tA", obj["k"])
    }

    @Test
    fun `忽略空白`() {
        val obj = JsonLite.asObject(JsonLite.parse("  {  \"a\" : 1 , \"b\" : 2 }  "))
        assertEquals(1L, obj["a"])
        assertEquals(2L, obj["b"])
    }

    @Test
    fun `空对象与空数组`() {
        assertTrue(JsonLite.asObject(JsonLite.parse("{}")).isEmpty())
        assertTrue(JsonLite.asArray(JsonLite.parse("[]")).isEmpty())
    }

    @Test
    fun `非法文本抛中文错误`() {
        // 尾随多余字符
        val ex1 = assertFailsWith<IllegalArgumentException> { JsonLite.parse("{}garbage") }
        assertTrue(ex1.message!!.contains("多余字符"), "应提示多余字符：${ex1.message}")
        // 未闭合
        assertFailsWith<IllegalArgumentException> { JsonLite.parse("""{"a":1""") }
        // 非法 token
        assertFailsWith<IllegalArgumentException> { JsonLite.parse("nope") }
    }

    @Test
    fun `畸形数字字面量抛中文错误而非英文 NumberFormatException`() {
        // 1.2.3 会被消费为一个 number 串，toDouble 抛 NumberFormatException → 应转中文错误
        val ex = assertFailsWith<IllegalArgumentException> { JsonLite.parse("""{"v":1.2.3}""") }
        assertTrue(ex.message!!.contains("非法数字字面量"), "应为中文非法数字错误：${ex.message}")
    }

    @Test
    fun `解析 form-feed 转义为 U+000C`() {
        // \f 转义应解析为换页符（修复后该分支用 '\u000C' 字面量，行为不变）
        val obj = JsonLite.asObject(JsonLite.parse("""{"k":"a\fb"}"""))
        assertEquals("a\u000Cb", obj["k"])
    }
}
