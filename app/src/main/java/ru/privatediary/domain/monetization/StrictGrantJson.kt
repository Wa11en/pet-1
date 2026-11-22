package ru.privatediary.domain.monetization

/**
 * Small strict parser for the flat, versioned grant schema. Unlike JSONObject's Android and
 * JVM implementations it consistently rejects duplicate keys, type coercion and extensions.
 * No nested documents are allowed, so parsing has bounded memory and no recursion.
 */
internal object StrictGrantJson {
    fun parse(source: String): Map<String, Any> = Reader(source).readObject()

    private class Reader(private val source: String) {
        private var position = 0

        fun readObject(): Map<String, Any> {
            whitespace()
            expect('{')
            whitespace()
            val fields = linkedMapOf<String, Any>()
            if (!take('}')) {
                while (true) {
                    require(fields.size < 6) { "Too many grant fields" }
                    val key = readString()
                    require(key !in fields) { "Duplicate grant field" }
                    whitespace()
                    expect(':')
                    whitespace()
                    fields[key] = if (peek() == '"') readString() else readInteger()
                    whitespace()
                    if (take('}')) break
                    expect(',')
                    whitespace()
                }
            }
            whitespace()
            require(position == source.length) { "Trailing JSON content" }
            return fields
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                val char = source[position++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        require(position < source.length) { "Unfinished escape" }
                        when (val escaped = source[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(position + 4 <= source.length) { "Unfinished Unicode escape" }
                                val digits = source.substring(position, position + 4)
                                require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape" }
                                result.append(digits.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        }
                    }
                    char.code < 0x20 -> throw IllegalArgumentException("Control character in JSON")
                    else -> result.append(char)
                }
            }
            throw IllegalArgumentException("Unfinished JSON string")
        }

        private fun readInteger(): Long {
            val start = position
            take('-')
            if (!take('0')) {
                require(peek() in '1'..'9') { "Expected JSON integer" }
                do { position++ } while (peek() in '0'..'9')
            }
            return source.substring(start, position).toLongOrNull()
                ?: throw IllegalArgumentException("JSON integer out of range")
        }

        private fun whitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') position++
        }

        private fun peek(): Char? = source.getOrNull(position)

        private fun take(char: Char): Boolean = (peek() == char).also { if (it) position++ }

        private fun expect(char: Char) {
            require(take(char)) { "Invalid JSON structure" }
        }
    }
}
