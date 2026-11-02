package top.wcpe.mc.testkit.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bot 原版 OP 提权的 `ops.json` 写入（ADR-0027）纯函数穷举单测。
 *
 * 离线 UUID 的期望值除用 JDK `UUID.nameUUIDFromBytes` 对照外，还硬编码了一份由独立实现（Python
 * `hashlib.md5` + `uuid.UUID(bytes=…)`）算出的值——只跟 JDK 自己对比等于自己验自己，抓不到算法抄错。
 */
class OpsJsonTest {

    @TempDir
    lateinit var runDir: File

    @Test
    @DisplayName("离线模式 UUID 应等于独立实现算出的已知值（Notch / Steve / test_bot）")
    fun offlineUuidMatchesIndependentlyComputedValues() {
        // 期望值由 Python 独立算出：md5(b"OfflinePlayer:<name>") 后设 version=3 / IETF variant
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", OpsJson.offlineUuid("Notch"), "Notch 的离线 UUID 不符")
        assertEquals("5627dd98-e6be-3c21-b8a8-e92344183641", OpsJson.offlineUuid("Steve"), "Steve 的离线 UUID 不符")
        assertEquals("523e3ecb-de8e-371d-a437-37ebd2674c1d", OpsJson.offlineUuid("test_bot"), "test_bot 的离线 UUID 不符")
    }

    @Test
    @DisplayName("离线模式 UUID 应与 JDK nameUUIDFromBytes 的派生结果一致且为小写带连字符格式")
    fun offlineUuidMatchesJdkNameUuidFromBytes() {
        listOf("Notch", "Steve", "test_bot", "P1", "Admin").forEach { name ->
            val expected = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()
            assertEquals(expected, OpsJson.offlineUuid(name), "$name 的离线 UUID 应与 JDK 派生一致")
        }
        val uuid = OpsJson.offlineUuid("Notch")
        assertEquals(36, uuid.length, "UUID 文本应为 8-4-4-4-12 共 36 字符")
        assertEquals(uuid.lowercase(), uuid, "UUID 应为小写")
        assertEquals(3, UUID.fromString(uuid).version(), "离线 UUID 应为 version 3")
        assertEquals(2, UUID.fromString(uuid).variant(), "离线 UUID 应为 IETF variant")
    }

    @Test
    @DisplayName("写入时应生成 level 4 且绕过人数上限的条目，文档顶层为数组")
    fun writeEmitsMaxLevelEntryAsJsonArray() {
        val added = OpsJson.write(runDir, listOf("test_bot"))

        val text = File(runDir, OpsJson.FILE_NAME).readText(Charsets.UTF_8).trim()
        assertEquals(listOf("test_bot"), added, "应回报真正新增的用户名")
        assertTrue(text.startsWith("[") && text.endsWith("]"), "ops.json 顶层应为数组，实际：$text")
        assertTrue(
            "\"uuid\":\"${OpsJson.offlineUuid("test_bot")}\"" in text,
            "条目应带离线 UUID，实际：$text",
        )
        assertTrue("\"name\":\"test_bot\"" in text, "条目应带用户名，实际：$text")
        assertTrue("\"level\":4" in text, "条目应为 level 4，实际：$text")
        assertTrue("\"bypassesPlayerLimit\":true" in text, "条目应绕过人数上限，实际：$text")
    }

    @Test
    @DisplayName("已有 ops.json 含别的玩家时应保留其原条目并追加缺失的 bot")
    fun writeMergesWithExistingEntries() {
        val templateEntry = """{"uuid":"${OpsJson.offlineUuid("ExistingOwner")}","name":"ExistingOwner",""" +
            """"level":4,"bypassesPlayerLimit":false}"""
        val opsFile = File(runDir, OpsJson.FILE_NAME)
        opsFile.writeText("[\n  $templateEntry\n]\n", Charsets.UTF_8)

        val added = OpsJson.write(runDir, listOf("test_bot"))

        val text = opsFile.readText(Charsets.UTF_8)
        assertEquals(listOf("test_bot"), added, "只应追加缺失的 bot")
        assertTrue("ExistingOwner" in text, "消费方模板自带的 OP 不得被抹掉，实际：$text")
        assertTrue("\"bypassesPlayerLimit\":false" in text, "原条目的字段值应原样保留，实际：$text")
        assertTrue("\"name\":\"test_bot\"" in text, "缺失的 bot 应被追加，实际：$text")
    }

    @Test
    @DisplayName("用户名为空集合时应什么都不做：不创建文件也不删除已有文件")
    fun writeDoesNothingWhenNamesEmpty() {
        val opsFile = File(runDir, OpsJson.FILE_NAME)

        val added = OpsJson.write(runDir, emptyList())

        assertTrue(added.isEmpty(), "空名单不应回报新增项")
        assertFalse(opsFile.exists(), "空名单不应创建 ops.json")

        opsFile.writeText("[]\n", Charsets.UTF_8)
        OpsJson.write(runDir, listOf("  ", ""))
        assertTrue(opsFile.isFile, "空白用户名不应删除已有文件")
        assertEquals("[]\n", opsFile.readText(Charsets.UTF_8), "空白用户名不应改写已有文件")
    }

    @Test
    @DisplayName("重复写入应幂等：第二次不新增条目也不产生重复")
    fun writeIsIdempotentOnRepeatedCalls() {
        OpsJson.write(runDir, listOf("test_bot", "Admin"))
        val firstText = File(runDir, OpsJson.FILE_NAME).readText(Charsets.UTF_8)

        val secondAdded = OpsJson.write(runDir, listOf("test_bot", "Admin"))
        val secondText = File(runDir, OpsJson.FILE_NAME).readText(Charsets.UTF_8)

        assertTrue(secondAdded.isEmpty(), "第二次写入不应新增任何用户名")
        assertEquals(firstText, secondText, "第二次写入不应改动文件内容")
        assertEquals(1, Regex("\"name\":\"test_bot\"").findAll(secondText).count(), "test_bot 不应重复出现")
        assertEquals(1, Regex("\"name\":\"Admin\"").findAll(secondText).count(), "Admin 不应重复出现")
    }

    @Test
    @DisplayName("写入结果应为可解析的 JSON：条目数与用户名一一对应")
    fun writeProducesParsableDocument() {
        OpsJson.write(runDir, listOf("test_bot", "Admin", "P1"))

        val text = File(runDir, OpsJson.FILE_NAME).readText(Charsets.UTF_8)
        val names = Regex("\"name\":\"([^\"]*)\"").findAll(text).map { it.groupValues[1] }.toList()
        val uuids = Regex("\"uuid\":\"([^\"]*)\"").findAll(text).map { it.groupValues[1] }.toList()
        val levels = Regex("\"level\":(\\d+)").findAll(text).map { it.groupValues[1] }.toList()

        assertEquals(listOf("test_bot", "Admin", "P1"), names, "用户名应与写入顺序一致")
        assertEquals(3, uuids.size, "每个条目都应有 uuid")
        uuids.forEach { assertEquals(it, UUID.fromString(it).toString(), "uuid 应为合法的带连字符小写格式") }
        assertEquals(listOf("4", "4", "4"), levels, "每个条目都应为 level 4")
        assertEquals(3, text.count { it == '{' }, "对象括号应配平，实际：$text")
        assertEquals(3, text.count { it == '}' }, "对象括号应配平，实际：$text")
    }

    @Test
    @DisplayName("用户名含引号或反斜杠时应正确转义而不撕坏 JSON")
    fun writeEscapesSpecialCharactersInName() {
        val tricky = "a\"b\\c"

        OpsJson.write(runDir, listOf(tricky))

        val text = File(runDir, OpsJson.FILE_NAME).readText(Charsets.UTF_8)
        assertTrue("\"name\":\"a\\\"b\\\\c\"" in text, "用户名中的引号与反斜杠应被转义，实际：$text")
        assertEquals(1, Regex("\"name\":").findAll(text).count(), "转义后条目数应仍为 1")
    }

    @Test
    @DisplayName("已有文件不是 JSON 数组时应抛中文错误而不是静默覆盖")
    fun writeRejectsNonArrayDocument() {
        val opsFile = File(runDir, OpsJson.FILE_NAME)
        opsFile.writeText("{\"not\":\"an array\"}\n", Charsets.UTF_8)

        val exception = assertFailsWith<IllegalArgumentException> { OpsJson.write(runDir, listOf("test_bot")) }

        assertTrue(exception.message!!.contains("ops.json"), "错误信息应指出文件名，实际：${exception.message}")
        assertTrue(exception.message!!.contains("JSON 数组"), "错误信息应说明顶层须为数组，实际：${exception.message}")
        assertTrue(opsFile.readText(Charsets.UTF_8).contains("not"), "不得静默覆盖消费方已有的文件内容")
    }
}
