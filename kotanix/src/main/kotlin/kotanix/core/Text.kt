package kotanix.core

/** Same seed as Kotatsu's parsers: String.longHashCode() in org.koitharu.kotatsu.parsers.util.StringUtils */
private const val LONG_HASH_SEED = 1125899906842597L

fun String.longHashCode(): Long {
	var h = LONG_HASH_SEED
	for (i in indices) {
		h = 31 * h + this[i].code
	}
	return h
}

/** Stable, non-negative id for local content; matches upstream `abs(longHashCode())` semantics without the MIN_VALUE trap. */
fun stableLocalId(value: String): Long {
	val h = value.longHashCode()
	return if (h == Long.MIN_VALUE) 0L else kotlin.math.abs(h)
}

fun String.toTitleCase(): String = replaceFirstChar { it.uppercase() }

/**
 * Natural ("alphanumeric") comparator identical in behaviour to
 * org.koitharu.kotatsu.core.util.AlphanumComparator in Kotatsu-Redo 9.8.7.
 * "Page 2" < "Page 10". Used for ordering CBZ/folder pages and chapters.
 */
class AlphanumComparator : Comparator<String> {

	override fun compare(s1: String?, s2: String?): Int {
		if (s1 == null || s2 == null) return 0
		var thisMarker = 0
		var thatMarker = 0
		val s1Length = s1.length
		val s2Length = s2.length
		while (thisMarker < s1Length && thatMarker < s2Length) {
			val thisChunk = getChunk(s1, s1Length, thisMarker)
			thisMarker += thisChunk.length
			val thatChunk = getChunk(s2, s2Length, thatMarker)
			thatMarker += thatChunk.length

			var result: Int
			if (thisChunk[0].isDigit() && thatChunk[0].isDigit()) {
				result = thisChunk.length - thatChunk.length
				if (result == 0) {
					for (i in thisChunk.indices) {
						result = thisChunk[i] - thatChunk[i]
						if (result != 0) return result
					}
				}
			} else {
				result = thisChunk.compareTo(thatChunk)
			}
			if (result != 0) return result
		}
		return s1Length - s2Length
	}

	private fun getChunk(s: String, slength: Int, cmarker: Int): String {
		var marker = cmarker
		val chunk = StringBuilder()
		var c = s[marker]
		chunk.append(c)
		marker++
		if (c.isDigit()) {
			while (marker < slength) {
				c = s[marker]
				if (!c.isDigit()) break
				chunk.append(c)
				marker++
			}
		} else {
			while (marker < slength) {
				c = s[marker]
				if (c.isDigit()) break
				chunk.append(c)
				marker++
			}
		}
		return chunk.toString()
	}
}

private val FALLBACK_NAME_REGEX = Regex("[\\\\/:*?\"<>|]")


/** File-name-safe version of a manga title, same spirit as Kotatsu's toFileNameSafe(): no path separators. */
fun String.toFileNameSafe(): String =
	trim().replace(FALLBACK_NAME_REGEX, "_").trim('.', ' ').ifEmpty { "untitled" }
