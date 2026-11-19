package kotanix.backup

import kotanix.core.*
import kotanix.data.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Android ↔ Linux backup compatibility (.bak = ZIP of JSON arrays, one file per section).
 *
 * Format contract (verified against Kotatsu-Redo 9.8.7 @ 9ef7077):
 *  - entries are named exactly as [BackupSection.entryName] (no .json suffix on write)
 *  - every entry is a JSON array; `settings`/`reader_grid`/`identity` wrap a single object: `[{...}]`
 *  - history entries carry an embedded `manga` object; favourites likewise; bookmarks embed a `manga`
 *    object plus a `tags` array and a `bookmarks` array of page bookmarks
 *  - string keys are snake_case as in the @SerialName annotations of the Android models
 *  - restore upserts manga + tags first, then the row; restore resets `deleted_at` to 0 for
 *    history/categories/favourites (upstream toEntity() does exactly that)
 */

enum class BackupSection(val entryName: String) {
	INDEX("index"),
	HISTORY("history"),
	CATEGORIES("categories"),
	FAVOURITES("favourites"),
	SETTINGS("settings"),
	SETTINGS_READER_GRID("reader_grid"),
	BOOKMARKS("bookmarks"),
	SOURCES("sources"),
	SCROBBLING("scrobbling"),
	STATS("statistics"),
	SAVED_FILTERS("saved_filters"),
	IDENTITY("identity"),
	HIDDEN_IMAGES("hidden_images"),
	DISMISSED("dismissed"),
	;

	companion object {
		fun ofEntry(name: String): BackupSection? {
			val fileName = name.replace('\\', '/').substringAfterLast('/').removeSuffix(".json").lowercase()
			return entries.firstOrNull { it.entryName == fileName }
		}
	}
}

data class RestoreReport(
	val restored: LinkedHashMap<String, Int> = LinkedHashMap(),
	val failed: MutableList<String> = mutableListOf(),
	val skippedSections: MutableList<String> = mutableListOf(),
	/** Set when a non-empty `identity` section carried a community key; the UI must surface this. */
	var identityKeyPresent: Boolean = false,
	/** Sections present in the file that this build cannot consume (scrobbling, saved filters…). */
	val unsupportedSections: MutableList<String> = mutableListOf(),
) {
	fun summary(): String = buildString {
		append("Restored: ")
		append(restored.entries.joinToString { "${it.key}=${it.value}" })
		if (skippedSections.isNotEmpty()) append("; skipped: ").append(skippedSections.joinToString(", "))
		if (failed.isNotEmpty()) append("; FAILED: ").append(failed.joinToString(" | "))
	}
}

data class BackupStats(
	val history: Int,
	val categories: Int,
	val favourites: Int,
	val bookmarks: Int,
	val stats: Int,
)

private const val MAX_BACKUP_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
private const val MAX_BACKUP_ENTRIES = 4096

object Backup {

	const val APP_ID = "kotanix.linux"

	// ---------------------------------------------------------------- export

	fun createBackup(
		output: OutputStream,
		database: SqliteDatabase,
		settingsToDump: Map<String, Any?>,
		appVersionCode: Int = 1,
	): BackupStats {
		val mangaStore = MangaStore(database)
		val historyStore = HistoryStore(database)
		val favouritesStore = FavouritesStore(database)
		val bookmarkStore = BookmarkStore(database)
		val statsStore = StatsStore(database)

		val zip = ZipOutputStream(output)

		writeEntry(zip, BackupSection.INDEX) {
			Json.stringifyPretty(
				listOf(
					linkedMapOf(
						"app_id" to APP_ID,
						"app_version" to appVersionCode.toLong(),
						"created_at" to System.currentTimeMillis(),
					),
				),
			)
		}

		var historyCount = 0
		writeEntry(zip, BackupSection.HISTORY) {
			val items = historyStore.dump()
			historyCount = items.size
			Json.stringifyPretty(items.map { historyBackupJson(it, mangaStore) })
		}

		val categories = favouritesStore.dumpCategories()
		writeEntry(zip, BackupSection.CATEGORIES) {
			Json.stringifyPretty(categories.map { categoryBackupJson(it) })
		}

		val favourites = favouritesStore.dumpFavourites()
		writeEntry(zip, BackupSection.FAVOURITES) {
			Json.stringifyPretty(favourites.mapNotNull { favouriteBackupJson(it, mangaStore) })
		}

		writeEntry(zip, BackupSection.SETTINGS) {
			Json.stringifyPretty(listOf(settingsToDump))
		}

		writeEntry(zip, BackupSection.SETTINGS_READER_GRID) {
			"[{}]"
		}

		writeEntry(zip, BackupSection.BOOKMARKS) {
			val grouped = bookmarkStore.dump().groupBy { it.mangaId }
			Json.stringifyPretty(grouped.map { (mangaId, bookmarks) -> bookmarkBackupJson(mangaId, bookmarks, mangaStore) })
		}

		writeEntry(zip, BackupSection.SOURCES) { "[]" }
		writeEntry(zip, BackupSection.SCROBBLING) { "[]" }

		val stats = statsStore.dump()
		writeEntry(zip, BackupSection.STATS) {
			Json.stringifyPretty(stats.map { statisticBackupJson(it) })
		}

		writeEntry(zip, BackupSection.SAVED_FILTERS) { "[]" }
		writeEntry(zip, BackupSection.IDENTITY) { "[{}]" }
		writeEntry(zip, BackupSection.HIDDEN_IMAGES) { "[]" }
		writeEntry(zip, BackupSection.DISMISSED) { "[]" }

		zip.finish()
		return BackupStats(
			history = historyCount,
			categories = categories.size,
			favourites = favourites.size,
			bookmarks = bookmarkStore.dump().size,
			stats = stats.size,
		)
	}

	private fun writeEntry(zip: ZipOutputStream, section: BackupSection, content: () -> String) {
		zip.putNextEntry(ZipEntry(section.entryName))
		zip.write(content().toByteArray(Charsets.UTF_8))
		zip.closeEntry()
	}

	private fun mangaBackupJson(manga: Manga): MutableMap<String, Any?> = linkedMapOf(
		"id" to manga.id,
		"title" to manga.title,
		"alt_title" to manga.altTitles.joinToString("\n").ifEmpty { null },
		"url" to manga.url,
		"public_url" to manga.publicUrl.ifEmpty { manga.url },
		"rating" to manga.rating.toDouble(),
		"nsfw" to manga.isNsfw,
		"content_rating" to manga.contentRating?.name,
		"cover_url" to manga.coverUrl,
		"large_cover_url" to manga.largeCoverUrl,
		"state" to manga.state?.name,
		"author" to manga.authors.joinToString("\n").ifEmpty { null },
		"source" to manga.source,
		"tags" to manga.tags.map { tagBackupJson(it) },
	)

	private fun tagBackupJson(tag: MangaTag): MutableMap<String, Any?> = linkedMapOf(
		"id" to tag.id,
		"title" to tag.title,
		"key" to tag.key,
		"source" to tag.source,
	)

	private fun historyBackupJson(row: HistoryWithManga, mangaStore: MangaStore): MutableMap<String, Any?> {
		val manga = mangaStore.findById(row.manga.id) ?: row.manga
		return linkedMapOf(
			"manga_id" to row.history.mangaId,
			"created_at" to row.history.createdAt,
			"updated_at" to row.history.updatedAt,
			"chapter_id" to row.history.chapterId,
			"page" to row.history.page.toLong(),
			"scroll" to row.history.scroll.toDouble(),
			"percent" to row.history.percent.toDouble(),
			"chapters" to row.history.chaptersCount.toLong(),
			"manga" to mangaBackupJson(manga),
		)
	}

	private fun categoryBackupJson(category: FavouriteCategory): MutableMap<String, Any?> = linkedMapOf(
		"category_id" to category.categoryId.toLong(),
		"created_at" to category.createdAt,
		"sort_key" to category.sortKey.toLong(),
		"title" to category.title,
		"order" to category.order,
		"track" to category.track,
		"show_in_lib" to category.isVisibleInLibrary,
	)

	private fun favouriteBackupJson(entry: FavouriteEntry, mangaStore: MangaStore): Map<String, Any?>? {
		val manga = mangaStore.findById(entry.mangaId) ?: return null
		return linkedMapOf(
			"manga_id" to entry.mangaId,
			"category_id" to entry.categoryId,
			"sort_key" to entry.sortKey.toLong(),
			"pinned" to entry.isPinned,
			"created_at" to entry.createdAt,
			"manga" to mangaBackupJson(manga),
		)
	}

	private fun bookmarkBackupJson(mangaId: Long, bookmarks: List<BookmarkEntry>, mangaStore: MangaStore): Map<String, Any?> {
		val manga = mangaStore.findById(mangaId)
		return linkedMapOf(
			"manga" to (manga?.let { mangaBackupJson(it.copy(tags = emptySet())) } ?: linkedMapOf("id" to mangaId)),
			"tags" to (manga?.tags?.map { tagBackupJson(it) } ?: emptyList()),
			"bookmarks" to bookmarks.map { b ->
				linkedMapOf(
					"manga_id" to b.mangaId,
					"page_id" to b.pageId,
					"chapter_id" to b.chapterId,
					"page" to b.page.toLong(),
					"scroll" to b.scroll.toLong(),
					"image_url" to b.imageUrl,
					"created_at" to b.createdAt,
					"percent" to b.percent.toDouble(),
				)
			},
		)
	}

	private fun statisticBackupJson(stat: StatsEntry): MutableMap<String, Any?> = linkedMapOf(
		"manga_id" to stat.mangaId,
		"started_at" to stat.startedAt,
		"duration" to stat.duration,
		"pages" to stat.pages.toLong(),
	)

	// ---------------------------------------------------------------- restore

	fun restoreBackup(
		input: InputStream,
		database: SqliteDatabase,
		settingsSink: (Map<String, String>) -> Unit,
	): RestoreReport {
		val report = RestoreReport()
		val mangaStore = MangaStore(database)
		val historyStore = HistoryStore(database)
		val favouritesStore = FavouritesStore(database)
		val bookmarkStore = BookmarkStore(database)
		val statsStore = StatsStore(database)

		val zip = ZipInputStream(input)
		val buffered = HashMap<BackupSection, String>()
		var entryCount = 0
		var totalBytes = 0L
		var entry = zip.nextEntry
		while (entry != null) {
			entryCount++
			if (entryCount > MAX_BACKUP_ENTRIES) {
				report.failed.add("archive too large: more than $MAX_BACKUP_ENTRIES entries")
				break
			}
			val section = BackupSection.ofEntry(entry.name)
			if (section == null) {
				report.skippedSections.add(entry.name)
				zip.closeEntry()
				entry = zip.nextEntry
				continue
			}
			// read entry fully, with a hard cap
			val buffer = ByteArrayOutputStream()
			val chunk = ByteArray(8192)
			while (true) {
				val read = zip.read(chunk)
				if (read < 0) break
				totalBytes += read
				if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
					report.failed.add("archive exceeds $MAX_BACKUP_UNCOMPRESSED_BYTES uncompressed bytes")
					break
				}
				buffer.write(chunk, 0, read)
			}
			if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) break
			buffered[section] = buffer.toString(Charsets.UTF_8)
			zip.closeEntry()
			entry = zip.nextEntry
		}
		zip.close()
		// Deterministic dispatch order: categories must exist before favourites reference them (FK),
		// and manga rows must exist before history/favourites/bookmarks/stats reference them.
		for (section in RESTORE_ORDER) {
			val text = buffered[section] ?: continue
			try {
				when (section) {
					BackupSection.INDEX -> Unit // only informational
					BackupSection.CATEGORIES -> restoreCategories(text, favouritesStore, report)
					BackupSection.HISTORY -> restoreHistory(text, mangaStore, historyStore, report)
					BackupSection.FAVOURITES -> restoreFavourites(text, mangaStore, favouritesStore, report)
					BackupSection.BOOKMARKS -> restoreBookmarks(text, mangaStore, bookmarkStore, report)
					BackupSection.STATS -> restoreStats(text, statsStore, report)
					BackupSection.SETTINGS -> restoreSettings(text, settingsSink, report)
					BackupSection.SETTINGS_READER_GRID -> Unit // tap-grid XML not ported yet; no residue written back
					BackupSection.IDENTITY -> {
						val map = readSingleObjectMap(text)
						if (map?.get("secret") != null) {
							report.identityKeyPresent = true
						}
					}

					BackupSection.SCROBBLING,
					BackupSection.SAVED_FILTERS,
					BackupSection.SOURCES,
					BackupSection.HIDDEN_IMAGES,
					BackupSection.DISMISSED,
					-> report.unsupportedSections.add(section.entryName)
				}
			} catch (e: Exception) {
				report.failed.add("${section.entryName}: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		return report
	}

	/** Restore order — anything not listed here restores afterwards in enum order. */
	private val RESTORE_ORDER: List<BackupSection> = listOf(
		BackupSection.INDEX,
		BackupSection.CATEGORIES,
		BackupSection.HISTORY,
		BackupSection.FAVOURITES,
		BackupSection.BOOKMARKS,
		BackupSection.STATS,
		BackupSection.SETTINGS,
		BackupSection.SETTINGS_READER_GRID,
		BackupSection.IDENTITY,
		BackupSection.SOURCES,
		BackupSection.SCROBBLING,
		BackupSection.SAVED_FILTERS,
		BackupSection.HIDDEN_IMAGES,
		BackupSection.DISMISSED,
	)

	private fun parseArrayItems(text: String): List<Map<String, Any?>> {
		val root = Json.parse(text)
		val list = root as? List<*> ?: throw JsonException("expected JSON array")
		return list.mapNotNull { it.asJsonMap() }
	}

	private fun readSingleObjectMap(text: String): Map<String, Any?>? {
		val root = Json.parse(text) as? List<*> ?: return null
		@Suppress("UNCHECKED_CAST")
		return root.firstOrNull().asJsonMap()
	}

	private fun restoreHistory(text: String, mangaStore: MangaStore, historyStore: HistoryStore, report: RestoreReport) {
		var count = 0
		val failures = mutableListOf<String>()
		for (item in parseArrayItems(text)) {
			try {
				val mangaJson = item["manga"].asJsonMap()
					?: throw JsonException("history entry missing embedded manga")
				val manga = readMangaBackup(mangaJson)
				mangaStore.upsertMangaWithChapters(manga)
				historyStore.restore(
					HistoryEntry(
						mangaId = longOf(item, "manga_id"),
						createdAt = longOf(item, "created_at"),
						updatedAt = longOf(item, "updated_at"),
						chapterId = longOf(item, "chapter_id"),
						page = intOf(item, "page"),
						scroll = floatOf(item, "scroll", 0f),
						percent = floatOf(item, "percent", HistoryStore.PROGRESS_NONE),
						chaptersCount = intOf(item, "chapters", 0),
					),
				)
				count++
			} catch (e: Exception) {
				failures.add("history[$count]: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		report.restored[BackupSection.HISTORY.entryName] = count
		report.failed.addAll(failures)
	}

	private fun restoreCategories(text: String, favouritesStore: FavouritesStore, report: RestoreReport) {
		var count = 0
		val failures = mutableListOf<String>()
		for (item in parseArrayItems(text)) {
			try {
				favouritesStore.restoreCategory(
					FavouriteCategory(
						categoryId = intOf(item, "category_id"),
						createdAt = longOf(item, "created_at"),
						sortKey = intOf(item, "sort_key", 0),
						title = stringOf(item, "title").ifEmpty { "Category ${intOf(item, "category_id")}" },
						order = stringOf(item, "order").ifEmpty { "NEWEST" },
						track = boolOf(item, "track", true),
						isVisibleInLibrary = boolOf(item, "show_in_lib", true),
						deletedAt = 0L,
					),
				)
				count++
			} catch (e: Exception) {
				failures.add("categories[$count]: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		report.restored[BackupSection.CATEGORIES.entryName] = count
		report.failed.addAll(failures)
	}

	private fun restoreFavourites(text: String, mangaStore: MangaStore, favouritesStore: FavouritesStore, report: RestoreReport) {
		var count = 0
		val failures = mutableListOf<String>()
		for (item in parseArrayItems(text)) {
			try {
				val mangaJson = item["manga"].asJsonMap()
					?: throw JsonException("favourite entry missing embedded manga")
				val manga = readMangaBackup(mangaJson)
				mangaStore.upsertMangaWithChapters(manga)
				favouritesStore.restoreFavourite(
					FavouriteEntry(
						mangaId = longOf(item, "manga_id"),
						categoryId = longOf(item, "category_id"),
						sortKey = intOf(item, "sort_key", 0),
						isPinned = boolOf(item, "pinned", false),
						createdAt = longOf(item, "created_at"),
						deletedAt = 0L,
					),
				)
				count++
			} catch (e: Exception) {
				failures.add("favourites[$count]: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		report.restored[BackupSection.FAVOURITES.entryName] = count
		report.failed.addAll(failures)
	}

	private fun restoreSettings(text: String, settingsSink: (Map<String, String>) -> Unit, report: RestoreReport) {
		val map = readSingleObjectMap(text) ?: throw JsonException("settings entry is not [{...}]")
		val flat = LinkedHashMap<String, String>()
		for ((k, v) in map) {
			when (v) {
				null -> Unit // upstream upsertAll(null) clears the key; we record absence instead
				is String -> flat[k] = v
				is Boolean -> flat[k] = v.toString()
				is Number -> flat[k] = v.toString()
				else -> flat[k] = Json.stringify(v)
			}
		}
		// Android hides these from backups; never resurrect them from a Linux build either
		for (secretKey in listOf("app_password", "proxy_password", "proxy_login", "incognito_mode")) {
			flat.remove(secretKey)
		}
		settingsSink(flat)
		report.restored[BackupSection.SETTINGS.entryName] = flat.size
	}

	private fun restoreBookmarks(text: String, mangaStore: MangaStore, bookmarkStore: BookmarkStore, report: RestoreReport) {
		var count = 0
		val failures = mutableListOf<String>()
		for (item in parseArrayItems(text)) {
			try {
				// BookmarkBackup: { manga: {...}, tags: [...], bookmarks: [...] }
				val mangaJson = item["manga"].asJsonMap()
					?: throw JsonException("bookmark entry missing manga")
				val manga = readMangaBackup(mangaJson)
				mangaStore.upsertMangaWithChapters(manga)
				for (raw in (item["bookmarks"] as? List<*>) ?: emptyList<Any?>()) {
					val b = raw.asJsonMap() ?: continue
					bookmarkStore.restore(
						BookmarkEntry(
							mangaId = longOf(b, "manga_id"),
							pageId = longOf(b, "page_id"),
							chapterId = longOf(b, "chapter_id"),
							page = intOf(b, "page"),
							scroll = intOf(b, "scroll", 0),
							imageUrl = stringOf(b, "image_url"),
							createdAt = longOf(b, "created_at"),
							percent = floatOf(b, "percent", HistoryStore.PROGRESS_NONE),
						),
					)
					count++
				}
			} catch (e: Exception) {
				failures.add("bookmarks[$count]: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		report.restored[BackupSection.BOOKMARKS.entryName] = count
		report.failed.addAll(failures)
	}

	private fun restoreStats(text: String, statsStore: StatsStore, report: RestoreReport) {
		var count = 0
		val failures = mutableListOf<String>()
		for (item in parseArrayItems(text)) {
			try {
				statsStore.restore(
					StatsEntry(
						mangaId = longOf(item, "manga_id"),
						startedAt = longOf(item, "started_at"),
						duration = longOf(item, "duration"),
						pages = intOf(item, "pages"),
					),
				)
				count++
			} catch (e: Exception) {
				failures.add("statistics[$count]: ${e.message ?: e.javaClass.simpleName}")
			}
		}
		report.restored[BackupSection.STATS.entryName] = count
		report.failed.addAll(failures)
	}

	internal fun readMangaBackup(json: Map<String, Any?>): Manga {
		val source = stringOf(json, "source").ifEmpty { UNKNOWN_SOURCE }
		val altTitles = stringOf(json, "alt_title")
			.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
		val authors = stringOf(json, "author")
			.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
		val tags = ((json["tags"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { raw ->
			val t = raw.asJsonMap() ?: return@mapNotNull null
			MangaTag(
				id = longOf(t, "id"),
				title = stringOf(t, "title"),
				key = stringOf(t, "key"),
				source = stringOf(t, "source").ifEmpty { source },
			)
		}.toSet()
		val id = longOf(json, "id")
		return Manga(
			id = id,
			title = stringOf(json, "title"),
			altTitles = altTitles,
			url = stringOf(json, "url"),
			publicUrl = stringOf(json, "public_url").ifEmpty { stringOf(json, "url") },
			rating = floatOf(json, "rating", RATING_UNKNOWN),
			isNsfw = boolOf(json, "nsfw", false),
			contentRating = stringOf(json, "content_rating").toContentRatingOrNull()
				?: if (boolOf(json, "nsfw", false)) ContentRating.ADULT else null,
			coverUrl = stringOf(json, "cover_url"),
			largeCoverUrl = stringOf(json, "large_cover_url").ifEmpty { null },
			state = stringOf(json, "state").toMangaStateOrNull(),
			authors = authors,
			source = source,
			tags = tags,
			chapters = null,
		)
	}

	private fun MangaStore.upsertMangaWithChapters(manga: Manga) {
		upsertManga(manga)
	}

	// ------------------------------------------------------------ scalars

	internal fun longOf(map: Map<String, Any?>, key: String): Long = when (val v = map[key]) {
		is Long -> v
		is Int -> v.toLong()
		is Double -> Math.rint(v).toLong()
		is String -> v.toLong()
		else -> throw JsonException("Missing or invalid long '$key'")
	}

	internal fun intOf(map: Map<String, Any?>, key: String, default: Int = 0): Int = when (val v = map[key]) {
		null -> default
		is Long -> v.toInt()
		is Int -> v
		is Double -> Math.rint(v).toInt()
		is String -> v.toIntOrNull() ?: default
		else -> default
	}

	internal fun floatOf(map: Map<String, Any?>, key: String, default: Float): Float = when (val v = map[key]) {
		null -> default
		is Number -> v.toFloat()
		is String -> v.toFloatOrNull() ?: default
		else -> default
	}

	internal fun boolOf(map: Map<String, Any?>, key: String, default: Boolean): Boolean = when (val v = map[key]) {
		is Boolean -> v
		is String -> v.toBooleanStrictOrNull() ?: default
		is Number -> v.toLong() != 0L
		else -> default
	}

	internal fun stringOf(map: Map<String, Any?>, key: String): String = when (val v = map[key]) {
		null -> ""
		is String -> v
		else -> v.toString()
	}
}
