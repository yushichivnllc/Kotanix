package kotanix.local

import kotanix.core.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.TreeSet

/**
 * Reads manga stored on disk, in both layouts Kotatsu-Redo understands:
 *
 *   manga root (dir or .cbz/.zip)
 *   |-- index.json            (optional; when present, chapter order/metadata come from it)
 *   |-- Page 1.png
 *   |-- Chapter 1/            (dir, or .cbz/.zip)
 *   |   |-- Page 1.1.png
 *
 * Pages are ordered with the same natural comparator as upstream ("Page 2" < "Page 10").
 *
 * Page/chapter URLs use the `kotanix-local://` scheme understood only by Kotanix:
 *   - folder page:  kotanix-local:///abs/path/to/page.png
 *   - archive page: kotanix-local:///abs/path/to/archive.cbz!entry/path.png
 *   - nested cbz:   kotanix-local:///abs/path/to/archive.cbz!chapter.cbz!entry.png
 */
class LocalMangaParser private constructor(val root: File) {

	val mangaId: Long get() = root.absolutePath.longHashCode()

	/** Manga info. When [reportIssue] is provided, non-fatal index problems are reported, not thrown. */
	fun getManga(withDetails: Boolean = true, reportIssue: (String) -> Unit = {}): Manga {
		val index = readIndex()
		if (index != null) {
			return mangaFromIndex(index, withDetails, reportIssue)
		}
		val title = root.nameWithoutExtension.toLocalTitle()
		val chapters = if (withDetails) findChapters() else null
		return Manga(
			id = mangaId,
			title = title,
			url = localUrl(root.absolutePath),
			publicUrl = localUrl(root.absolutePath),
			source = LOCAL_SOURCE,
			coverUrl = findFirstImageUrl().orEmpty(),
			chapters = chapters,
			rating = RATING_UNKNOWN,
		)
	}

	fun getPages(chapter: MangaChapter): List<MangaPage> {
		val loc = LocalLocation.parse(chapter.url) ?: return emptyList()
		val index = readIndex()
		val chapterIndexObj = index?.chapters?.get(chapter.id.toString())
		val pattern = (chapterIndexObj?.get("entries") as? String)?.let { runCatching { Regex(it) }.getOrNull() }

		if (isArchive(loc.baseFile)) {
			val chapterEntry = loc.segments.firstOrNull()
			if (chapterEntry != null && hasZipExtension(chapterEntry)) {
				// chapter is a nested .cbz entry inside the archive
				val bytes = readEntryBytes(loc.baseFile, listOf(chapterEntry), MAX_CHAPTER_BYTES)
					?: return emptyList()
				return listImagesInZipBytes(bytes, chapterEntry)
						.filter { pattern == null || pattern.matches(nameWithoutExt(it.substringAfterLast('/'))) }
						.map { inner -> archivePage(loc.baseFile, listOf(chapterEntry, inner)) }
			}
			// listing inside the archive, either the whole archive or a directory prefix
			val prefix = chapterEntry.orEmpty().trim('/')
			return listArchiveImages(loc.baseFile, prefix, topOnly = true)
				.filter { pattern == null || pattern.matches(nameWithoutExt(it.substringAfterLast('/'))) }
				.map { name -> archivePage(loc.baseFile, listOf(name)) }
		}

		// folder tree
		val dir = if (loc.segments.isEmpty()) {
			loc.baseFile
		} else {
			File(loc.baseFile, loc.segments.joinToString(File.separator))
		}
		if (dir.isFile && hasZipExtension(dir.name)) {
			// chapter is a .cbz file inside a folder manga
			return listImagesInZipBytes(dir.readBytes(), dir.absolutePath)
				.filter { pattern == null || pattern.matches(nameWithoutExt(it)) }
				.map { inner -> archivePage(dir, listOf(inner)) }
		}
		if (!dir.isDirectory) return emptyList()
		val comparator = AlphanumComparator()
		return dir.listFiles()
			?.filter { it.isFile && isImageName(it.name) }
			?.sortedWith(compareBy(comparator) { it.name })
			?.filter { pattern == null || pattern.matches(nameWithoutExt(it.name)) }
			?.map { f -> MangaPage(id = f.absolutePath.longHashCode(), url = localUrl(f.absolutePath), source = LOCAL_SOURCE) }
			.orEmpty()
	}

	/** Reads the whole page into memory. [MAX_PAGE_BYTES] cap — bigger pages are rejected deliberately. */
	fun readPage(page: MangaPage): ByteArray {
		val loc = LocalLocation.parse(page.url)
			?: throw IllegalArgumentException("Not a kotanix-local URL: ${page.url}")
		if (loc.segments.isEmpty()) {
			return FileInputStream(loc.baseFile).use { it.readCapped(MAX_PAGE_BYTES, loc.baseFile.absolutePath) }
		}
		if (isArchive(loc.baseFile)) {
			val head = loc.segments.first()
			if (hasZipExtension(head) && loc.segments.size > 1) {
				// nested chapter archive inside the base archive
				val chapterBytes = readEntryBytes(loc.baseFile, listOf(head), MAX_CHAPTER_BYTES)
					?: throw IllegalStateException("Missing chapter entry $head in ${loc.baseFile}")
				val innerName = loc.segments.drop(1).joinToString("/")
				return readEntryFromZipBytes(chapterBytes, innerName, MAX_PAGE_BYTES, page.url)
			}
			return readEntryBytes(loc.baseFile, loc.segments, MAX_PAGE_BYTES)
				?: throw IllegalStateException("Missing entry in ${loc.baseFile}: ${loc.segments}")
		}
		// folder page stored under an archive-style marker (cannot happen with our writer, but be tolerant)
		return FileInputStream(File(loc.baseFile, loc.segments.joinToString(File.separator))).use {
			it.readCapped(MAX_PAGE_BYTES, page.url)
		}
	}

	// ---------------------------------------------------------------- manga info

	private fun mangaFromIndex(index: MangaIndex, withDetails: Boolean, reportIssue: (String) -> Unit): Manga {
		val chapters = if (withDetails) {
			index.chapters?.values
				?.mapNotNull { ch ->
					val id = (ch["id"] as? String)?.toLongOrNull() ?: return@mapNotNull null
					val fileName = ch["file"] as? String
					val rawUrl = fileName?.let { localUrl(File(root, it).absolutePath) }
						?: (ch["url"] as? String)?.let { chUrl ->
							if (isArchive(root)) "${localUrl(root.absolutePath)}!${chUrl.trim('/')}" else localUrl(File(root, chUrl).absolutePath)
						} ?: localUrl(root.absolutePath)
					MangaChapter(
						id = id,
						title = (ch["name"] as? String)?.takeIf { it.isNotEmpty() }
							?: (ch["url"] as? String)?.substringAfterLast('/')?.substringBeforeLast('.'),
						number = (ch["number"] as? Number)?.toFloat() ?: 0f,
						volume = (ch["volume"] as? Number)?.toInt() ?: 0,
						uploadDate = (ch["uploadDate"] as? Number)?.toLong() ?: 0L,
						scanlator = ch["scanlator"] as? String,
						branch = ch["branch"] as? String,
						url = rawUrl,
						source = LOCAL_SOURCE,
						index = (ch["index"] as? Number)?.toInt() ?: -1,
					)
				}
				?.sortedBy { it.number }
		} else {
			null
		}
		val coverEntry = index.coverEntry
		val cover = when {
			coverEntry == null -> findFirstImageUrl()
			isArchive(root) -> "${localUrl(root.absolutePath)}!${coverEntry.trim('/')}"
			else -> localUrl(File(root, coverEntry).absolutePath)
		}.orEmpty()
		if (index.mangaId != null && index.mangaId != mangaId) {
			reportIssue("index.json 'id' does not match this path; the index id is used for history/favourites")
		}
		return Manga(
			id = index.mangaId ?: mangaId,
			title = index.title?.takeIf { it.isNotEmpty() } ?: root.nameWithoutExtension.toLocalTitle(),
			url = localUrl(root.absolutePath),
			publicUrl = index.publicUrl?.takeIf { it.isNotEmpty() } ?: localUrl(root.absolutePath),
			rating = index.rating,
			contentRating = index.contentRating,
			isNsfw = index.isNsfw,
			state = index.state,
			authors = index.authors,
			altTitles = index.altTitles,
			description = index.description,
			coverUrl = cover,
			source = LOCAL_SOURCE,
			chapters = chapters,
			tags = index.tags,
		)
	}

	private fun findChapters(): List<MangaChapter> {
		val chapterKeys = LinkedHashSet<String>()
		if (isArchive(root)) {
			ZipFile(root).use { zip ->
				var rootHasImages = false
				val imageParents = TreeSet(AlphanumComparator())
				val nestedZips = TreeSet(AlphanumComparator())
				zip.entries().asSequence().forEach { e ->
					if (e.isDirectory) return@forEach
					val name = e.name
					when {
						isImageName(name) -> {
							val parent = name.substringBeforeLast('/', "")
							if (parent.isEmpty()) rootHasImages = true else imageParents.add(parent)
						}

						hasZipExtension(name) -> nestedZips.add(name)
					}
				}
				if (rootHasImages) chapterKeys.add("")
				chapterKeys.addAll(imageParents)
				chapterKeys.addAll(nestedZips)
			}
		} else {
			val relativer = root.toPath()
			root.walkTopDown().forEach { f ->
				if (!f.isFile) return@forEach
				when {
					isImageName(f.name) -> {
						val parent = f.parentFile
						if (parent == root) {
							chapterKeys.add("")
						} else {
							chapterKeys.add(relativer.relativize(parent.toPath()).toString())
						}
					}

					hasZipExtension(f.name) -> chapterKeys.add(relativer.relativize(f.toPath()).toString())
				}
			}
		}
		val sorted = chapterKeys.toList().sortedWith(AlphanumComparator())
		return sorted.mapIndexed { index, rel ->
			val chapterId = "$index$rel".longHashCode()
			val url = when {
				rel.isEmpty() -> localUrl(root.absolutePath)
				isArchive(root) -> "${localUrl(root.absolutePath)}!$rel"
				hasZipExtension(rel) -> localUrl(File(root, rel).absolutePath)
				else -> localUrl(File(root, rel).absolutePath)
			}
			MangaChapter(
				id = chapterId,
				title = if (rel.isEmpty()) root.nameWithoutExtension.toLocalTitle()
				else File(rel).nameWithoutExtension.toLocalTitle(),
				number = 0f,
				volume = 0,
				url = url,
				source = LOCAL_SOURCE,
				index = index,
			)
		}
	}

	private fun findFirstImageUrl(): String? {
		val comparator = AlphanumComparator()
		if (isArchive(root)) {
			ZipFile(root).use { zip ->
				val first = zip.entries().asSequence()
					.filter { !it.isDirectory && isImageName(it.name) }
					.sortedBy { it.name }
					.firstOrNull() ?: return null
				return "${localUrl(root.absolutePath)}!${first.name}"
			}
		}
		for (f in root.listFiles()?.sortedWith(compareBy(comparator) { it.name }).orEmpty()) {
			if (f.isFile && isImageName(f.name)) return localUrl(f.absolutePath)
		}
		root.walkTopDown().forEach { f ->
			if (f.isFile && isImageName(f.name)) return localUrl(f.absolutePath)
		}
		return null
	}

	// ---------------------------------------------------------------- zip helpers

	private fun archivePage(base: File, segments: List<String>): MangaPage {
		val url = localUrl(base.absolutePath) + segments.joinToString("!", prefix = "!") { it }
		return MangaPage(id = url.longHashCode(), url = url, source = LOCAL_SOURCE)
	}

	/** Names of image entries directly under [prefix] inside [base], natural-sorted. */
	private fun listArchiveImages(base: File, prefix: String, topOnly: Boolean): List<String> {
		val normalized = if (prefix.isEmpty()) "" else "$prefix/"
		ZipFile(base).use { zip ->
			return zip.entries().asSequence()
				.filter { !it.isDirectory }
				.map { it.name }
				.filter { name ->
					if (normalized.isEmpty()) {
						!topOnly || !name.contains('/')
					} else {
						val rest = name.removePrefix(normalized)
						rest != name && (!topOnly || !rest.contains('/'))
					}
				}
				.filter { isImageName(it) }
				.sortedWith(AlphanumComparator())
				.toList()
		}
	}

	private fun listImagesInZipBytes(bytes: ByteArray, label: String): List<String> {
		val out = ArrayList<String>()
		ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
			var e = zin.nextEntry
			while (e != null) {
				if (!e.isDirectory && isImageName(e.name) && !e.name.contains('/')) {
					out.add(e.name)
				}
				e = zin.nextEntry
			}
		}
		return out.sortedWith(AlphanumComparator())
	}

	private fun readEntryBytes(base: File, entrySegments: List<String>, cap: Long): ByteArray? {
		if (entrySegments.isEmpty()) return null
		val entryName = entrySegments.joinToString("/") { it.trim('/') }
		return ZipFile(base).use { zip ->
			val entry = zip.getEntry(entryName) ?: return null
			zip.getInputStream(entry).use { it.readCapped(cap, entryName) }
		}
	}

	private fun readEntryFromZipBytes(bytes: ByteArray, entryName: String, cap: Long, label: String): ByteArray {
		ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
			var e = zin.nextEntry
			while (e != null) {
				if (!e.isDirectory && e.name == entryName) {
					return zin.readCapped(cap, label)
				}
				e = zin.nextEntry
			}
		}
		throw IllegalStateException("Entry not found: $entryName")
	}

	private fun java.io.InputStream.readCapped(cap: Long, label: String): ByteArray {
		val out = ByteArrayOutputStream(8192)
		val buf = ByteArray(8192)
		var total = 0L
		while (true) {
			val n = read(buf)
			if (n < 0) break
			total += n
			if (total > cap) throw IllegalStateException("Entry exceeds $cap bytes: $label")
			out.write(buf, 0, n)
		}
		return out.toByteArray()
	}

	private fun nameWithoutExt(name: String) = name.substringBeforeLast('.')

	private fun readIndex(): MangaIndex? {
		val text = when {
			isArchive(root) -> ZipFile(root).use { zip ->
				val e = zip.getEntry(ENTRY_NAME_INDEX) ?: return null
				zip.getInputStream(e).use { String(it.readCapped(MAX_INDEX_BYTES, ENTRY_NAME_INDEX), Charsets.UTF_8) }
			}
			else -> {
				val f = File(root, ENTRY_NAME_INDEX)
				if (!f.isFile || f.length() > MAX_INDEX_BYTES) return null
				f.readText()
			}
		} ?: return null
		if (text.length < 3) return null
		return runCatching { MangaIndex.parse(text) }.getOrNull()
	}

	companion object {
		const val SCHEME = "kotanix-local://"
		const val ENTRY_NAME_INDEX = "index.json"
		const val MAX_PAGE_BYTES = 64L * 1024 * 1024
		const val MAX_CHAPTER_BYTES = 512L * 1024 * 1024
		const val MAX_INDEX_BYTES = 16L * 1024 * 1024
		private val ZIP_EXTENSIONS = setOf("cbz", "zip")
		private val IMAGE_EXTENSIONS = setOf(
			"jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl", "heic", "heif",
		)

		fun localUrl(absolutePath: String) = "$SCHEME///$absolutePath"

		fun isArchive(file: File) = file.isFile && hasZipExtension(file.name)

		fun get(file: File): LocalMangaParser? {
			if (!file.canRead()) return null
			return when {
				file.isDirectory -> LocalMangaParser(file)
				file.isFile && hasZipExtension(file.name) -> LocalMangaParser(file)
				file.isFile && hasPkMagic(file) -> LocalMangaParser(file)
				else -> null
			}
		}

		fun hasZipExtension(name: String): Boolean {
			val ext = name.substringAfterLast('.', "").lowercase()
			return ext in ZIP_EXTENSIONS
		}

		fun isImageName(name: String): Boolean {
			val ext = name.substringAfterLast('.', "").lowercase()
			return ext in IMAGE_EXTENSIONS
		}

		fun hasPkMagic(file: File): Boolean = runCatching {
			FileInputStream(file).use { input ->
				val head = ByteArray(4)
				val n = input.read(head)
				n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
			}
		}.getOrDefault(false)

		fun isLocalUrl(url: String) = url.startsWith(SCHEME)

		fun String.toLocalTitle(): String =
			substringBeforeLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
	}
}

/** A resolved location: a base filesystem path plus 0..2 archive segments. */
internal data class LocalLocation(
	val baseFile: File,
	val segments: List<String>,
	val url: String,
) {
	companion object {
		fun parse(url: String): LocalLocation? {
			if (!url.startsWith(LocalMangaParser.SCHEME)) return null
			val rest = url.removePrefix(LocalMangaParser.SCHEME)
			val marker = findArchiveMarker(rest)
			if (marker < 0) return LocalLocation(File(rest), emptyList(), url)
			val base = rest.substring(0, marker)
			val segments = rest.substring(marker + 1).split('!').filter { it.isNotEmpty() }
			return LocalLocation(File(base), segments, url)
		}

		/** Index in `rest` of the '!' that separates the base .cbz/.zip path from its entries. */
		private fun findArchiveMarker(rest: String): Int {
			for ((index, c) in rest.withIndex()) {
				if (c != '!') continue
				val lower = rest.substring(0, index).lowercase()
				if (lower.endsWith(".cbz") || lower.endsWith(".zip")) return index
			}
			return -1
		}
	}
}

/** Parsed subset of local index.json. Numbers stay Numbers; strings stay strings. */
internal class MangaIndex(
	val mangaId: Long?,
	val title: String?,
	val altTitles: Set<String>,
	val publicUrl: String?,
	val authors: Set<String>,
	val rating: Float,
	val contentRating: ContentRating?,
	val isNsfw: Boolean,
	val state: MangaState?,
	val description: String?,
	val coverEntry: String?,
	val tags: Set<MangaTag>,
	/** chapters keyed by chapter id, augmented with a string "id" field */
	val chapters: Map<String, Map<String, Any?>>?,
) {

	companion object {
		fun parse(text: String): MangaIndex {
			val root = Json.parse(text) as? Map<String, Any?>
				?: throw JsonException("index.json is not an object")
			val source = (root["source"] as? String) ?: LOCAL_SOURCE
			val tagsAny = root["tags"] as? List<*> ?: emptyList<Any?>()
			val tags = tagsAny.mapNotNull { raw ->
				val t = raw as? Map<String, Any?> ?: return@mapNotNull null
				val key = t["key"] as? String ?: return@mapNotNull null
				MangaTag(
					id = key.longHashCode(),
					title = (t["title"] as? String)?.let { it.replaceFirstChar(Char::uppercase) } ?: key,
					key = key,
					source = source,
				)
			}.toSet()
			val chaptersJson = root["chapters"] as? Map<String, Any?>
			val chapters = chaptersJson?.mapValues { (id, v) ->
				((v as? Map<String, Any?>) ?: emptyMap()) + ("id" to id)
			}
			return MangaIndex(
				mangaId = (root["id"] as? Number)?.toLong() ?: (root["id"] as? String)?.toLongOrNull(),
				title = root["title"] as? String,
				altTitles = (root["alt_titles"] as? List<*>)?.filterIsInstance<String>()?.toSet()
					?: (root["title_alt"] as? String)?.let { setOf(it) }
					?: emptySet(),
				publicUrl = root["public_url"] as? String,
				authors = (root["authors"] as? List<*>)?.filterIsInstance<String>()?.toSet()
					?: (root["author"] as? String)?.let { setOf(it) }
					?: emptySet(),
				rating = (root["rating"] as? Number)?.toFloat() ?: RATING_UNKNOWN,
				contentRating = (root["content_rating"] as? String).toContentRatingOrNull()
					?: if ((root["nsfw"] as? Boolean) == true) ContentRating.ADULT else null,
				isNsfw = (root["nsfw"] as? Boolean) ?: false,
				state = (root["state"] as? String).toMangaStateOrNull(),
				description = root["description"] as? String,
				coverEntry = root["cover_entry"] as? String,
				tags = tags,
				chapters = chapters,
			)
		}
	}
}
