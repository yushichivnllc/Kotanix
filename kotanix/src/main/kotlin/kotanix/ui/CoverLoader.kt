package kotanix.ui

import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.local.LocalMangaParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/** Loads covers for LOCAL manga (kotanix-local:// URLs) without any network access. */
object CoverLoader {

	private const val MAX_COVER = 8L * 1024 * 1024

	/** Best-effort; returns null on any failure (caller falls back to a placeholder). */
	fun load(manga: Manga): BufferedImage? {
		if (manga.source != LOCAL_SOURCE) return null
		val cover = manga.coverUrl.takeIf { it.isNotBlank() } ?: return null
		val loc = LocalLocation2.parse(cover) ?: return null
		return runCatching {
			val bytes = when {
				loc.second == null -> loc.first.inputStream().use { it.readNBytes(MAX_COVER.toInt()) }
				loc.first.isFile && loc.first.extension.lowercase() in setOf("cbz", "zip") ->
					ZipFile(loc.first).use { zip ->
						val e = zip.getEntry(loc.second) ?: return null
						zip.getInputStream(e).use { it.readNBytes(MAX_COVER.toInt()) }
					}
				else -> return null
			}
			ImageIO.read(ByteArrayInputStream(bytes))
		}.getOrNull()
	}
}

/** Minimal kotanix-local:/// URL splitter: (baseFile, entryOrNull). */
private object LocalLocation2 {
	fun parse(url: String): Pair<File, String?>? {
		val prefix = "kotanix-local:///"
		if (!url.startsWith(prefix)) return null
		val rest = url.removePrefix(prefix)
		val bang = rest.indexOf('!')
		return if (bang < 0) {
			File(rest) to null
		} else {
			File(rest.substring(0, bang)) to rest.substring(bang + 1)
		}
	}
}
