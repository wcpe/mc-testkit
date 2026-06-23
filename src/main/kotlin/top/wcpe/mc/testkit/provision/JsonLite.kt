package top.wcpe.mc.testkit.provision

/**
 * 极简 JSON 解析器（递归下降）。
 *
 * 只够解析 PaperMC 下载 API 与 SpigotMC Jenkins 的小响应：对象 / 数组 / 字符串 / 数 / 布尔 / null。
 * 不做格式化输出、不做注解绑定——本项目下载逻辑只读取少数字段，无需引入 Jackson 等重型件
 * （架构不变量：下载模块保持精简、不镀金）。纯函数，可喂固定文本穷举单测。
 *
 * 解析结果用 JDK 原生类型表达：对象→`Map<String, Any?>`、数组→`List<Any?>`、
 * 字符串→`String`、整数→`Long`、小数→`Double`、布尔→`Boolean`、null→`null`。
 */
internal object JsonLite {

    /**
     * 解析一段 JSON 文本为原生对象树。
     *
     * @param text JSON 文本。
     * @return 解析结果（见类注释的类型映射）。
     * @throws IllegalArgumentException 文本非合法 JSON（含尾随多余字符）时抛出，文案为中文。
     */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 文本解析后存在多余字符（位置 ${parser.position}）。" }
        return value
    }

    /** 把任意 [parse] 结果当作对象访问；不是对象则抛中文错误。 */
    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("期望 JSON 对象，实际为 ${value?.javaClass?.simpleName ?: "null"}。")

    /** 把任意 [parse] 结果当作数组访问；不是数组则抛中文错误。 */
    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?> =
        value as? List<Any?> ?: error("期望 JSON 数组，实际为 ${value?.javaClass?.simpleName ?: "null"}。")

    /** 递归下降解析器：持有文本与游标，逐字符消费。 */
    private class Parser(private val text: String) {
        var position: Int = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) {
                position++
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "JSON 文本意外结束。" }
            return when (val ch = text[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (ch == '-' || ch.isDigit()) parseNumber() else error("位置 $position 处出现非法字符 '$ch'。")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (val ch = nextOrFail()) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("对象中期望 ',' 或 '}'，实际为 '$ch'（位置 ${position - 1}）。")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (val ch = nextOrFail()) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("数组中期望 ',' 或 ']'，实际为 '$ch'（位置 ${position - 1}）。")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                val ch = nextOrFail()
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscape())
                    else -> builder.append(ch)
                }
            }
        }

        private fun parseEscape(): Char {
            return when (val ch = nextOrFail()) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(position + 4 <= text.length) { "Unicode 转义不完整（位置 $position）。" }
                    val hex = text.substring(position, position + 4)
                    position += 4
                    hex.toInt(16).toChar()
                }
                else -> error("非法转义字符 '\\$ch'（位置 ${position - 1}）。")
            }
        }

        private fun parseNumber(): Any {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) {
                position++
            }
            val literal = text.substring(start, position)
            require(literal.isNotEmpty() && literal != "-") { "非法数字字面量 '$literal'（位置 $start）。" }
            // 含小数点或指数视为浮点，否则视为整数（构建号 / 端口等都是整数）；
            // 畸形字面量（如 1.2.3）toDouble/toLong 会抛 NumberFormatException，转中文错误（对齐错误约定）
            return try {
                if (literal.any { it == '.' || it == 'e' || it == 'E' }) {
                    literal.toDouble()
                } else {
                    literal.toLong()
                }
            } catch (ex: NumberFormatException) {
                throw IllegalArgumentException("非法数字字面量 '$literal'（位置 $start）。", ex)
            }
        }

        private fun parseBoolean(): Boolean {
            return when {
                text.startsWith("true", position) -> {
                    position += 4
                    true
                }
                text.startsWith("false", position) -> {
                    position += 5
                    false
                }
                else -> error("非法布尔字面量（位置 $position）。")
            }
        }

        private fun parseNull(): Any? {
            require(text.startsWith("null", position)) { "非法 null 字面量（位置 $position）。" }
            position += 4
            return null
        }

        private fun peek(): Char {
            require(!atEnd()) { "JSON 文本意外结束。" }
            return text[position]
        }

        private fun nextOrFail(): Char {
            require(!atEnd()) { "JSON 文本意外结束。" }
            return text[position++]
        }

        private fun expect(expected: Char) {
            val actual = nextOrFail()
            require(actual == expected) { "期望字符 '$expected'，实际为 '$actual'（位置 ${position - 1}）。" }
        }
    }
}
