package kotanix.data

import kotanix.core.*

/** Row mapping helpers shared by the stores. */
internal object Rows {

	fun readManga(stmt: SqliteStatement, base: String = ""): Manga = Manga(
		id = stmt.getLong(stmt.index("${base}manga_id")),
		title = stmt.getString(stmt.index("${base}title")).orEmpty(),
		altTitles = stmt.getString(stmt.index("${base}alt_title")).orEmpty()
			.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
		url = stmt.getString(stmt.index("${base}url")).orEmpty(),
		publicUrl = stmt.getString(stmt.index("${base}public_url")).orEmpty(),
		rating = stmt.getFloat(stmt.index("${base}rating")),
		isNsfw = stmt.getBoolean(stmt.index("${base}nsfw")),
		contentRating = stmt.getString(stmt.index("${base}content_rating")).toContentRatingOrNull(),
		coverUrl = stmt.getString(stmt.index("${base}cover_url")).orEmpty(),
		largeCoverUrl = stmt.getString(stmt.index("${base}large_cover_url")),
		state = stmt.getString(stmt.index("${base}state")).toMangaStateOrNull(),
		authors = stmt.getString(stmt.index("${base}author")).orEmpty()
			.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
		source = stmt.getString(stmt.index("${base}source")).orEmpty(),
	)

	fun readChapter(stmt: SqliteStatement): MangaChapter = MangaChapter(
		id = stmt.getLong(stmt.index("chapter_id")),
		title = stmt.getString(stmt.index("name")),
		url = stmt.getString(stmt.index("url")).orEmpty(),
		number = stmt.getFloat(stmt.index("number")),
		volume = stmt.getInt(stmt.index("volume")),
		uploadDate = stmt.getLong(stmt.index("upload_date")),
		scanlator = stmt.getString(stmt.index("scanlator")),
		branch = stmt.getString(stmt.index("branch")),
		source = stmt.getString(stmt.index("source")).orEmpty(),
		index = stmt.getInt(stmt.index("index")),
	)

	fun readTag(stmt: SqliteStatement): MangaTag = MangaTag(
		id = stmt.getLong(stmt.index("tag_id")),
		title = stmt.getString(stmt.index("title")).orEmpty(),
		key = stmt.getString(stmt.index("key")).orEmpty(),
		source = stmt.getString(stmt.index("source")).orEmpty(),
		isPinned = stmt.getBoolean(stmt.index("pinned")),
	)
}

private fun SqliteStatement.index(name: String): Int {
	for (i in 0 until columnCount()) {
		if (columnName(i) == name) return i
	}
	throw SqliteException("Missing column '$name' in result set")
}

/** Persistence for manga, chapters, tags and the local-file index. All writes are transactional. */
class MangaStore(private val db: SqliteDatabase) {

	fun upsertManga(manga: Manga): Unit = db.transaction { tx ->
		upsertMangaLocked(tx, manga)
	}

	internal fun upsertMangaLocked(tx: SqliteDatabase, manga: Manga) {
		tx.exec(
			"""INSERT INTO manga(manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source)
			   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
			   ON CONFLICT(manga_id) DO UPDATE SET
			     title=excluded.title, alt_title=excluded.alt_title, url=excluded.url, public_url=excluded.public_url,
			     rating=excluded.rating, nsfw=excluded.nsfw, content_rating=excluded.content_rating,
			     cover_url=excluded.cover_url, large_cover_url=excluded.large_cover_url, state=excluded.state,
			     author=excluded.author, source=excluded.source""",
			manga.id,
			manga.title,
			manga.altTitles.joinToString("\n").ifEmpty { null },
			manga.url,
			manga.publicUrl.ifEmpty { manga.url },
			manga.rating,
			manga.isNsfw,
			manga.contentRating?.name,
			manga.coverUrl,
			manga.largeCoverUrl,
			manga.state?.name,
			manga.authors.joinToString("\n").ifEmpty { null },
			manga.source,
		)
		for (tag in manga.tags) {
			tx.exec(
				"INSERT INTO tags(tag_id, title, `key`, source, pinned) VALUES (?,?,?,?,?) ON CONFLICT(tag_id) DO UPDATE SET title=excluded.title, `key`=excluded.`key`, source=excluded.source",
				tag.id, tag.title, tag.key, tag.source, tag.isPinned,
			)
			tx.exec(
				"INSERT OR IGNORE INTO manga_tags(manga_id, tag_id) VALUES (?,?)",
				manga.id, tag.id,
			)
		}
		manga.chapters?.let { chapters -> replaceChaptersLocked(tx, manga.id, chapters) }
	}

	internal fun replaceChaptersLocked(tx: SqliteDatabase, mangaId: Long, chapters: List<MangaChapter>) {
		tx.exec("DELETE FROM chapters WHERE manga_id = ?", mangaId)
		for ((i, chapter) in chapters.withIndex()) {
			tx.exec(
				"INSERT INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, `index`) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
				chapter.id, mangaId, chapter.title ?: "", chapter.number, chapter.volume, chapter.url,
				chapter.scanlator, chapter.uploadDate, chapter.branch, chapter.source, i,
			)
		}
	}

	fun replaceChapters(mangaId: Long, chapters: List<MangaChapter>) = db.transaction { tx ->
		replaceChaptersLocked(tx, mangaId, chapters)
	}

	fun findById(id: Long): Manga? {
		val manga = db.queryOne("SELECT * FROM manga WHERE manga_id = ?", id) { Rows.readManga(it) } ?: return null
		val tags = findTags(id)
		val chapters = findChapters(id)
		return manga.copy(tags = tags, chapters = chapters)
	}

	fun findChapters(mangaId: Long): List<MangaChapter> =
		db.query("SELECT * FROM chapters WHERE manga_id = ? ORDER BY `index` ASC, chapter_id ASC", mangaId) {
			Rows.readChapter(it)
		}

	fun findChapter(mangaId: Long, chapterId: Long): MangaChapter? =
		db.queryOne("SELECT * FROM chapters WHERE manga_id = ? AND chapter_id = ?", mangaId, chapterId) {
			Rows.readChapter(it)
		}

	fun findTags(mangaId: Long): Set<MangaTag> =
		db.query(
			"SELECT t.tag_id, t.title, t.`key`, t.source, t.pinned FROM tags t JOIN manga_tags mt ON mt.tag_id = t.tag_id WHERE mt.manga_id = ? ORDER BY t.title",
			mangaId,
		) { Rows.readTag(it) }.toSet()

	fun searchByTitle(query: String, limit: Int): List<Manga> =
		db.query(
			"SELECT * FROM manga WHERE title LIKE ? ESCAPE '!' ORDER BY title COLLATE NOCASE LIMIT ?",
			"%" + escapeLike(query) + "%", limit,
		) { Rows.readManga(it) }

	fun searchCount(query: String): Int =
		db.queryOne(
			"SELECT COUNT(*) FROM manga WHERE title LIKE ? ESCAPE '!'",
			"%" + escapeLike(query) + "%",
		) { it.getInt(0) } ?: 0

	private fun escapeLike(q: String): String = buildString(q.length + 8) {
		for (c in q) {
			when (c) {
				'!', '%', '_' -> append('!').append(c)
				else -> append(c)
			}
		}
	}

	fun upsertLocalPath(mangaId: Long, path: String) {
		db.exec(
			"INSERT INTO local_index(manga_id, path) VALUES (?,?) ON CONFLICT(manga_id) DO UPDATE SET path = excluded.path",
			mangaId, path,
		)
	}

	fun getLocalPath(mangaId: Long): String? =
		db.queryOne("SELECT path FROM local_index WHERE manga_id = ?", mangaId) { it.getString(0) }

	fun findLocalManga(): List<Pair<Long, String>> =
		db.query("SELECT manga_id, path FROM local_index ORDER BY manga_id") { it.getLong(0) to it.getString(1).orEmpty() }

	fun deleteManga(mangaId: Long) {
		db.transaction { tx ->
			tx.exec("DELETE FROM manga WHERE manga_id = ?", mangaId) // cascades
		}
	}
}
