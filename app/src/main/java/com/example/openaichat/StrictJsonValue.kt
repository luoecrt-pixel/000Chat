package com.example.openaichat

/** RFC 8259 syntax check independent of Android's lenient org.json parser. */
internal object StrictJsonValue {
    fun isObjectOrArray(source: String): Boolean {
        val parser = Parser(source)
        return try {
            parser.space()
            if (parser.peek() != '{' && parser.peek() != '[') false
            else { parser.value(0); parser.space(); parser.done() }
        } catch (_: IllegalArgumentException) { false }
    }
    private class Parser(private val source: String) {
        private var index = 0
        fun peek(): Char? = source.getOrNull(index)
        fun done() = index == source.length
        fun space() { while (peek()?.let { it == ' ' || it == '\t' || it == '\n' || it == '\r' } == true) index++ }
        private fun expect(c: Char) { require(peek() == c); index++ }
        fun value(depth: Int) {
            // Deep values remain readable prose instead of overflowing a renderer stack.
            require(depth <= 200); space()
            when (peek()) {
                '{' -> {
                    index++; space()
                    if (peek() == '}') { index++; return }
                    while (true) {
                        string(); space(); expect(':'); value(depth + 1); space()
                        if (peek() == '}') { index++; return }
                        expect(','); space()
                    }
                }
                '[' -> {
                    index++; space()
                    if (peek() == ']') { index++; return }
                    while (true) {
                        value(depth + 1); space()
                        if (peek() == ']') { index++; return }
                        expect(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun literal(value: String) {
            require(source.startsWith(value, index)); index += value.length
        }
        private fun string() {
            expect('"')
            while (true) {
                val c = peek() ?: throw IllegalArgumentException("Unclosed JSON string")
                index++
                if (c == '"') return
                require(c >= ' ')
                if (c == '\\') {
                    val escaped = peek() ?: throw IllegalArgumentException("Unclosed JSON escape")
                    index++
                    if (escaped == 'u') repeat(4) {
                        val hex = peek(); require(hex != null && (hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F')); index++
                    } else require(escaped in "\"\\/bfnrt")
                }
            }
        }
        private fun digit() = peek()?.let { it in '0'..'9' } == true
        private fun number() {
            if (peek() == '-') index++
            if (peek() == '0') index++ else {
                require(peek()?.let { it in '1'..'9' } == true)
                while (digit()) index++
            }
            if (peek() == '.') { index++; require(digit()); while (digit()) index++ }
            if (peek() == 'e' || peek() == 'E') {
                index++; if (peek() == '+' || peek() == '-') index++
                require(digit()); while (digit()) index++
            }
        }
    }
}
