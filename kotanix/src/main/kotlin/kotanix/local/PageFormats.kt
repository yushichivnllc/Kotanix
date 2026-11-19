package kotanix.local

import javax.imageio.ImageIO

/**
 * Page image format detection — used to explain *why* a page cannot be shown.
 *
 * Two honest sources of truth are combined:
 *
 *  - The runtime decides what it can decode: [supportedExtensions] is derived from
 *    `ImageIO.getReaderFormatNames()`, so it stays correct if an ImageIO plugin is
 *    ever vendored into the app. Nothing here hardcodes "WebP is unsupported".
 *  - [sniff] reads magic bytes. Filenames lie (a `.png` that is really a WebP would
 *    otherwise be reported as "PNG không giải mã được", which would be misleading).
 *
 * As of 2026-11 there is no WebP/AVIF/JXL/HEIF decoder on the classpath (the JDK's
 * ImageIO ships JPG/JPEG/PNG/GIF/BMP/TIFF/WBMP only — verified with
 * `ImageIO.getReaderFormatNames()` on the vendored Temurin 21.0.8 runtime). Kotatsu
 * CBZ exports and Mangalib-derived dumps are predominantly WebP, so local chapters
 * in those formats open with a clear message instead of a bare decode failure.
 */
object PageFormats {

	/** Extensions (lowercase, no dot) the runtime ImageIO claims to decode. */
	val supportedExtensions: Set<String> by lazy {
		ImageIO.getReaderFormatNames().map { it.lowercase(java.util.Locale.ROOT) }.toSet()
	}

	fun extensionOf(name: String): String =
		name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)

	fun isSupportedExt(name: String): Boolean = extensionOf(name) in supportedExtensions

	/** Count of pages whose extension the runtime cannot decode, grouped by display format. Empty when all readable. */
	fun unsupportedSummary(pageNames: List<String>): Map<String, Int> =
		pageNames
			.map { extensionOf(it) }
			.filter { it.isNotEmpty() && it !in supportedExtensions }
			.groupingBy { displayFormat(it) }
			.eachCount()

	/**
	 * Magic-byte sniff of the leading bytes of an image file. Returns a display format
	 * name ("WebP", "AVIF", "JPEG XL"…) or null when the bytes are not recognised.
	 */
	fun sniff(bytes: ByteArray): String? {
		fun startsWith(vararg values: Byte): Boolean =
			bytes.size >= values.size && values.indices.all { bytes[it] == values[it] }
		fun ascii(off: Int, len: Int): String? =
			if (bytes.size >= off + len) String(bytes, off, len, Charsets.US_ASCII) else null

		return when {
			startsWith(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) -> "JPEG"
			startsWith(0x89.toByte(), 0x50, 0x4E, 0x47) -> "PNG"
			startsWith(0x47, 0x49, 0x46) -> "GIF" // GIF87a / GIF89a
			startsWith(0x42, 0x4D) -> "BMP" // BM
			startsWith(
				0x52, 0x49, 0x46, 0x46, // RIFF
			) && ascii(8, 4) == "WEBP" -> "WebP"
			startsWith(0x49, 0x49, 0x2A, 0x00) || startsWith(0x4D, 0x4D, 0x00, 0x2A) -> "TIFF"
			startsWith(0xFF.toByte(), 0x0A) -> "JPEG XL" // raw codestream
			ascii(4, 4) == "ftyp" -> when (ascii(8, 4)) { // ISO-BMFF brands
				"avif", "avis" -> "AVIF"
				"jxl " -> "JPEG XL"
				"heic", "heix", "hevc", "mif1", "msf1", "heif" -> "HEIF"
				else -> ascii(8, 4)?.trim()?.uppercase(java.util.Locale.ROOT) ?: "ISO-BMFF"
			}
			else -> null
		}
	}

	/** Best-effort display format for an error message: sniffed magic bytes win over the filename. */
	fun label(name: String, bytes: ByteArray?): String =
		bytes?.let { sniff(it) } ?: displayFormat(extensionOf(name))

	/** Display name for an extension/format token. */
	fun displayFormat(token: String): String = when (token.lowercase(java.util.Locale.ROOT)) {
		"" -> "?"
		"jpg" -> "JPEG"
		"jpeg" -> "JPEG"
		"tif" -> "TIFF"
		"tiff" -> "TIFF"
		"png" -> "PNG"
		"gif" -> "GIF"
		"bmp" -> "BMP"
		"wbmp" -> "WBMP"
		"webp" -> "WebP"
		"avif" -> "AVIF"
		"jxl" -> "JPEG XL"
		"heic", "heif" -> "HEIF"
		"qoi" -> "QOI"
		"psd" -> "PSD"
		else -> token.uppercase(java.util.Locale.ROOT)
	}
}
