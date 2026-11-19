package kotanix.core

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * The offline build environment has no org.json / kotlinx-serialization available,
 * and Kotanix must read Android backup files (`.bak`) exactly as they were written.
 * Two properties are load-bearing:
 *
 *  1. Integer literals that fit in a Long are parsed as [Long]. Manga IDs routinely
 *     exceed 2^53, so going through Double would corrupt them.
 *  2. `\uXXXX` escapes are decoded with proper surrogate-pair handling.
 *
 * Data model of a parsed document:
 *   [Map]<String, Any?> for objects, [List]<Any?> for arrays,
 *   [String], [Long], [Double], [Boolean], or null.
 * Numbers with a fractional/exponent part become [Double]; pure integer literals
 * become [Long] (or [Double] on overflow).
 */
object Json {

	fun parse(text: String): Any? {
		val p = Parser(text)
		p.skipWs()
		val v = p.parseValue()
		p.skipWs()
		if (!p.atEnd()) throw JsonException("Trailing content at offset ${p.pos}")
		return v
	}

	fun parseObject(text: String): MutableMap<String, Any?> =
		parse(text) as? MutableMap<String, Any?>
			?: throw JsonException("Expected JSON object")

	fun stringify(value: Any?): String {
		val sb = StringBuilder(256)
		writeValue(value, sb)
		return sb.toString()
	}

	/** Serialises with two-space indentation; used for human-inspectable output files. */
	fun stringifyPretty(value: Any?): String {
		val sb = StringBuilder(256)
		writePretty(value, sb, 0)
		return sb.toString()
	}

	private fun writeValue(value: Any?, sb: StringBuilder) {
		when (value) {
			null -> sb.append("null")
			is Boolean -> sb.append(if (value) "true" else "false")
			is Int, is Short, is Byte, is Long -> sb.append(value.toString())
			is Float, is Double -> writeDouble((value as Number).toDouble(), sb)
			is Number -> sb.append(value.toString())
			is String -> writeString(value, sb)
			is Map<*, *> -> {
				sb.append('{')
				var first = true
				for ((k, v) in value) {
					if (!first) sb.append(',')
					first = false
					writeString(k.toString(), sb)
					sb.append(':')
					writeValue(v, sb)
				}
				sb.append('}')
			}
			is Iterable<*> -> {
				sb.append('[')
				var first = true
				for (v in value) {
					if (!first) sb.append(',')
					first = false
					writeValue(v, sb)
				}
				sb.append(']')
			}
			is Array<*> -> writeValue(value.asList(), sb)
			else -> writeString(value.toString(), sb)
		}
	}

	private fun writePretty(value: Any?, sb: StringBuilder, indent: Int) {
		when (value) {
			is Map<*, *> -> {
				if (value.isEmpty()) { sb.append("{}"); return }
				sb.append("{\n")
				var first = true
				for ((k, v) in value) {
					if (!first) sb.append(",\n")
					first = false
					sb.append("  ".repeat(indent + 1))
					writeString(k.toString(), sb)
					sb.append(": ")
					writePretty(v, sb, indent + 1)
				}
				sb.append('\n').append("  ".repeat(indent)).append('}')
			}
			is Iterable<*> -> {
				if (!value.iterator().hasNext()) { sb.append("[]"); return }
				sb.append("[\n")
				var first = true
				for (v in value) {
					if (!first) sb.append(",\n")
					first = false
					sb.append("  ".repeat(indent + 1))
					writePretty(v, sb, indent + 1)
				}
				sb.append('\n').append("  ".repeat(indent)).append(']')
			}
			else -> writeValue(value, sb)
		}
	}

	private fun writeDouble(v: Double, sb: StringBuilder) {
		when {
			v.isNaN() -> sb.append("NaN") // upstream writes these with allowSpecialFloatingPointValues
			v.isInfinite() -> sb.append(if (v > 0) "Infinity" else "-Infinity")
			v == Math.rint(v) && Math.abs(v) < 1e15 -> sb.append(v.toLong().toString())
			else -> sb.append(v.toString())
		}
	}

	private fun writeString(s: String, sb: StringBuilder) {
		sb.append('"')
		for (c in s) {
			when (c) {
				'"' -> sb.append("\\\"")
				'\\' -> sb.append("\\\\")
				'\b' -> sb.append("\\b")
				'\u000C' -> sb.append("\\f")
				'\n' -> sb.append("\\n")
				'\r' -> sb.append("\\r")
				'\t' -> sb.append("\\t")
				else -> if (c < ' ') {
					sb.append(String.format("\\u%04x", c.code))
				} else {
					sb.append(c)
				}
			}
		}
		sb.append('"')
	}

	private class Parser(private val text: String) {
		var pos = 0

		fun atEnd(): Boolean = pos >= text.length

		fun skipWs() {
			while (pos < text.length) {
				when (text[pos]) {
					' ', '\t', '\n', '\r' -> pos++
					else -> return
				}
			}
		}

		fun parseValue(): Any? {
			if (atEnd()) throw JsonException("Unexpected end of input")
			return when (val c = text[pos]) {
				'{' -> parseObjectValue()
				'[' -> parseArrayValue()
				'"' -> parseStringValue()
				't' -> expectLiteral("true", true)
				'f' -> expectLiteral("false", false)
				'n' -> expectLiteral("null", null)
				'N' -> expectLiteral("NaN", Double.NaN)
				'I' -> expectLiteral("Infinity", Double.POSITIVE_INFINITY)
				'-', in '0'..'9' -> parseNumber()
				else -> throw JsonException("Unexpected character '$c' at offset $pos")
			}
		}

		private fun expectLiteral(literal: String, value: Any?): Any? {
			if (text.regionMatches(pos, literal, 0, literal.length)) {
				pos += literal.length
				return value
			}
			if (text.regionMatches(pos, "-" + literal, 0, literal.length + 1)) {
				pos += literal.length + 1
				return if (value is Double && value == Double.POSITIVE_INFINITY) Double.NEGATIVE_INFINITY else value
			}
			throw JsonException("Invalid literal at offset $pos")
		}

		private fun parseObjectValue(): MutableMap<String, Any?> {
			pos++ // {
			val map = LinkedHashMap<String, Any?>()
			skipWs()
			if (!atEnd() && text[pos] == '}') { pos++; return map }
			while (true) {
				skipWs()
				if (atEnd() || text[pos] != '"') throw JsonException("Expected object key at offset $pos")
				val key = parseStringValue()
				skipWs()
				if (atEnd() || text[pos] != ':') throw JsonException("Expected ':' at offset $pos")
				pos++
				skipWs()
				map[key] = parseValue()
				skipWs()
				if (atEnd()) throw JsonException("Unterminated object")
				when (text[pos]) {
					',' -> { pos++; continue }
					'}' -> { pos++; return map }
					else -> throw JsonException("Expected ',' or '}' at offset $pos")
				}
			}
		}

		private fun parseArrayValue(): List<Any?> {
			pos++ // [
			val list = ArrayList<Any?>()
			skipWs()
			if (!atEnd() && text[pos] == ']') { pos++; return list }
			while (true) {
				skipWs()
				list.add(parseValue())
				skipWs()
				if (atEnd()) throw JsonException("Unterminated array")
				when (text[pos]) {
					',' -> { pos++; continue }
					']' -> { pos++; return list }
					else -> throw JsonException("Expected ',' or ']' at offset $pos")
				}
			}
		}

		private fun parseStringValue(): String {
			pos++ // opening quote
			val sb = StringBuilder()
			while (true) {
				if (atEnd()) throw JsonException("Unterminated string")
				val c = text[pos++]
				when (c) {
					'"' -> return sb.toString()
					'\\' -> {
						if (atEnd()) throw JsonException("Unterminated escape")
						when (val e = text[pos++]) {
							'"' -> sb.append('"')
							'\\' -> sb.append('\\')
							'/' -> sb.append('/')
							'b' -> sb.append('\b')
							'f' -> sb.append('\u000C')
							'n' -> sb.append('\n')
							'r' -> sb.append('\r')
							't' -> sb.append('\t')
							'u' -> {
								val hi = parseHex4()
								if (hi in 0xD800..0xDBFF && pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] == 'u') {
									pos += 2
									val lo = parseHex4()
									if (lo in 0xDC00..0xDFFF) {
										sb.appendCodePoint(((hi - 0xD800) shl 10) or (lo - 0xDC00) + 0x10000)
									} else {
										sb.append('�').append('�')
									}
								} else {
									sb.append(hi.toChar())
								}
							}
							else -> throw JsonException("Invalid escape '\\$e' at offset ${pos - 1}")
						}
					}
					else -> sb.append(c)
				}
			}
		}

		private fun parseHex4(): Int {
			if (pos + 4 > text.length) throw JsonException("Truncated \\u escape at offset $pos")
			var v = 0
			for (i in 0 until 4) {
				val c = text[pos++]
				val d = c.digitToIntOrNull(16) ?: throw JsonException("Invalid hex '$c' at offset ${pos - 1}")
				v = v * 16 + d
			}
			return v
		}

		private fun parseNumber(): Any? {
			val start = pos
			if (!atEnd() && text[pos] == '-') pos++
			var isIntegral = true
			while (!atEnd()) {
				when (text[pos]) {
					in '0'..'9' -> pos++
					'.', 'e', 'E', '+', '-' -> { isIntegral = false; pos++ }
					else -> break
				}
			}
			val literal = text.substring(start, pos)
			return if (isIntegral) {
				literal.toLongOrNull() ?: literal.toDoubleOrNull()
					?: throw JsonException("Invalid number '$literal' at offset $start")
			} else {
				literal.toDoubleOrNull()
					?: throw JsonException("Invalid number '$literal' at offset $start")
			}
		}
	}
}

class JsonException(message: String) : RuntimeException(message)

/** Typed view over a parsed object. Missing keys fall back to caller-provided defaults. */
class JsonObject(private val map: Map<String, Any?>) {

	fun string(key: String): String? = map[key] as? String
	fun stringOr(key: String, default: String): String = map[key] as? String ?: default

	fun long(key: String): Long? = when (val v = map[key]) {
		is Long -> v
		is Int -> v.toLong()
		is Double -> if (v.isFinite()) Math.rint(v).toLong() else null
		else -> null
	}

	fun longOr(key: String, default: Long): Long = long(key) ?: default
	fun intOr(key: String, default: Int): Int = long(key)?.toInt() ?: default
	fun floatOr(key: String, default: Float): Float = when (val v = map[key]) {
		is Number -> v.toFloat()
		else -> default
	}

	fun boolOr(key: String, default: Boolean): Boolean = when (val v = map[key]) {
		is Boolean -> v
		else -> default
	}

	fun obj(key: String): JsonObject? = (map[key] as? Map<*, *>)?.let { JsonObject(it as Map<String, Any?>) }
	fun list(key: String): List<Any?> = map[key] as? List<Any?> ?: emptyList()
	fun objectOf(key: String): MutableMap<String, Any?> =
		(map[key] as? Map<*, *>)?.let { LinkedHashMap(it as Map<String, Any?>) } ?: LinkedHashMap()
}

fun Any?.asObject(): JsonObject = JsonObject(this as? Map<String, Any?> ?: emptyMap())
