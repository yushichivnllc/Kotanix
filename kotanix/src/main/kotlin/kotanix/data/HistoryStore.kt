package kotanix.data

import kotanix.core.*

/** Reading history (table `history`). Favourites and bookmarks live in their own stores. */
class HistoryStore(private val db: SqliteDatabase) {

	companion object {
		const val PROGRESS_NONE = -1f
	}

	fun upsert(entry: HistoryEntry) {
		db.exec(
			"""INSERT INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters)
			   VALUES (?,?,?,?,?,?,?,?,?)
			   ON CONFLICT(manga_id) DO UPDATE SET
			     updated_at = excluded.updated_at, chapter_id = excluded.chapter_id,
			     page = excluded.page, scroll = excluded.scroll, percent = excluded.percent,
			     deleted_at = 0, chapters = excluded.chapters""",
			entry.mangaId, entry.createdAt, entry.updatedAt, entry.chapterId,
			entry.page, entry.scroll, entry.percent, 0L, entry.chaptersCount,
		)
	}

	/** Upsert used when restoring a backup: created_at/updated_at are preserved from the file. */
	fun restore(entry: HistoryEntry) {
		db.exec(
			"""INSERT INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters)
			   VALUES (?,?,?,?,?,?,?,?,?)
			   ON CONFLICT(manga_id) DO UPDATE SET
			     created_at = excluded.created_at, updated_at = excluded.updated_at,
			     chapter_id = excluded.chapter_id, page = excluded.page,
			     scroll = excluded.scroll, percent = excluded.percent,
			     deleted_at = 0, chapters = excluded.chapters""",
			entry.mangaId, entry.createdAt, entry.updatedAt, entry.chapterId,
			entry.page, entry.scroll, entry.percent, 0L, entry.chaptersCount,
		)
	}

	fun get(mangaId: Long): HistoryEntry? =
		db.queryOne("SELECT * FROM history WHERE manga_id = ? AND deleted_at = 0", mangaId) { it.readHistory() }


	/** Soft delete — upstream flips deleted_at instead of removing the row (sync merge relies on it). */
	fun remove(mangaId: Long, at: Long = System.currentTimeMillis()) {
		db.exec("UPDATE history SET deleted_at = ? WHERE manga_id = ?", at, mangaId)
	}

	/** Non-deleted rows only — exactly what upstream's backup dump() serialises. */
	fun dump(): List<HistoryWithManga> =
		db.query(
			"""SELECT m.manga_id, m.title, m.alt_title, m.url, m.public_url, m.rating, m.nsfw, m.content_rating, m.cover_url, m.large_cover_url, m.state, m.author, m.source,
			          h.created_at, h.updated_at, h.chapter_id, h.page, h.scroll, h.percent, h.chapters
			   FROM history h JOIN manga m ON m.manga_id = h.manga_id
			   WHERE h.deleted_at = 0
			   ORDER BY h.updated_at DESC""",
		) { stmt -> readHistoryWithManga(stmt) }

	fun getRecent(limit: Int): List<HistoryWithManga> =
		db.query(
			"""SELECT m.manga_id, m.title, m.alt_title, m.url, m.public_url, m.rating, m.nsfw, m.content_rating, m.cover_url, m.large_cover_url, m.state, m.author, m.source,
			          h.created_at, h.updated_at, h.chapter_id, h.page, h.scroll, h.percent, h.chapters
			   FROM history h JOIN manga m ON m.manga_id = h.manga_id
			   WHERE h.deleted_at = 0
			   ORDER BY h.updated_at DESC
			   LIMIT ?""",
			limit,
		) { stmt -> readHistoryWithManga(stmt) }

	fun search(query: String, limit: Int): List<HistoryWithManga> =
		db.query(
			"""SELECT m.manga_id, m.title, m.alt_title, m.url, m.public_url, m.rating, m.nsfw, m.content_rating, m.cover_url, m.large_cover_url, m.state, m.author, m.source,
			          h.created_at, h.updated_at, h.chapter_id, h.page, h.scroll, h.percent, h.chapters
			   FROM history h JOIN manga m ON m.manga_id = h.manga_id
			   WHERE h.deleted_at = 0 AND m.title LIKE ? ESCAPE '!'
			   ORDER BY h.updated_at DESC
			   LIMIT ?""",
			"%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", limit,
		) { stmt -> readHistoryWithManga(stmt) }

	fun count(): Int =
		db.queryOne("SELECT COUNT(*) FROM history WHERE deleted_at = 0") { it.getInt(0) } ?: 0

	private fun SqliteStatement.readHistory() = HistoryEntry(
		mangaId = getLong(indexOf("manga_id")),
		createdAt = getLong(indexOf("created_at")),
		updatedAt = getLong(indexOf("updated_at")),
		chapterId = getLong(indexOf("chapter_id")),
		page = getInt(indexOf("page")),
		scroll = getFloat(indexOf("scroll")),
		percent = getFloat(indexOf("percent")),
		chaptersCount = getInt(indexOf("chapters")),
	)

	private fun readHistoryWithManga(stmt: SqliteStatement): HistoryWithManga {
		val manga = Manga(
			id = stmt.getLong(stmt.indexOf("manga_id")),
			title = stmt.getString(stmt.indexOf("title")).orEmpty(),
			altTitles = stmt.getString(stmt.indexOf("alt_title")).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
			url = stmt.getString(stmt.indexOf("url")).orEmpty(),
			publicUrl = stmt.getString(stmt.indexOf("public_url")).orEmpty(),
			rating = stmt.getFloat(stmt.indexOf("rating")),
			isNsfw = stmt.getBoolean(stmt.indexOf("nsfw")),
			contentRating = stmt.getString(stmt.indexOf("content_rating")).toContentRatingOrNull(),
			coverUrl = stmt.getString(stmt.indexOf("cover_url")).orEmpty(),
			largeCoverUrl = stmt.getString(stmt.indexOf("large_cover_url")),
			state = stmt.getString(stmt.indexOf("state")).toMangaStateOrNull(),
			authors = stmt.getString(stmt.indexOf("author")).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
			source = stmt.getString(stmt.indexOf("source")).orEmpty(),
		)
		val history = HistoryEntry(
			mangaId = manga.id,
			createdAt = stmt.getLong(stmt.indexOf("created_at")),
			updatedAt = stmt.getLong(stmt.indexOf("updated_at")),
			chapterId = stmt.getLong(stmt.indexOf("chapter_id")),
			page = stmt.getInt(stmt.indexOf("page")),
			scroll = stmt.getFloat(stmt.indexOf("scroll")),
			percent = stmt.getFloat(stmt.indexOf("percent")),
			chaptersCount = stmt.getInt(stmt.indexOf("chapters")),
		)
		// Tags are intentionally not fetched for list rows (grid needs only title/cover/progress).
		// The details screen loads the full manga — and its tags — via MangaStore.findById().
		return HistoryWithManga(manga, history, emptySet())
	}
}

private fun SqliteStatement.indexOf(name: String): Int {
	for (i in 0 until columnCount()) {
		if (columnName(i) == name) return i
	}
	throw SqliteException("Missing column '$name'")
}
