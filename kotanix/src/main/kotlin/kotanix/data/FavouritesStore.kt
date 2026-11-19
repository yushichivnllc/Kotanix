package kotanix.data

import kotanix.core.*

class FavouritesStore(private val db: SqliteDatabase) {

	fun listCategories(includeHidden: Boolean = false): List<FavouriteCategory> =
		db.query(
			"SELECT * FROM favourite_categories WHERE deleted_at = 0" +
				if (includeHidden) "" else " AND show_in_lib = 1" +
				" ORDER BY sort_key ASC, category_id ASC",
		) { stmt ->
			FavouriteCategory(
				categoryId = stmt.getInt(stmt.indexOfColumn("category_id")),
				createdAt = stmt.getLong(stmt.indexOfColumn("created_at")),
				sortKey = stmt.getInt(stmt.indexOfColumn("sort_key")),
				title = stmt.getString(stmt.indexOfColumn("title")).orEmpty(),
				order = stmt.getString(stmt.indexOfColumn("order")).orEmpty(),
				track = stmt.getBoolean(stmt.indexOfColumn("track")),
				isVisibleInLibrary = stmt.getBoolean(stmt.indexOfColumn("show_in_lib")),
				deletedAt = stmt.getLong(stmt.indexOfColumn("deleted_at")),
			)
		}

	fun defaultCategoryId(): Long =
		db.queryOne("SELECT category_id FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC, category_id ASC LIMIT 1") {
			it.getLong(0)
		} ?: -1L

	fun createCategory(title: String, sortKey: Int = 0): Int {
		db.exec(
			"INSERT INTO favourite_categories(created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) VALUES (?,?,?,?,?,?,?)",
			System.currentTimeMillis(), sortKey, title, "NEWEST", true, true, 0L,
		)
		return db.lastInsertRowId().toInt()
	}

	fun renameCategory(categoryId: Int, title: String) {
		db.exec("UPDATE favourite_categories SET title = ? WHERE category_id = ?", title, categoryId)
	}

	/** Soft delete: the row stays so a later sync does not resurrect it (android parity). */
	fun deleteCategory(categoryId: Int, at: Long = System.currentTimeMillis()) {
		db.transaction { tx ->
			tx.exec("UPDATE favourite_categories SET deleted_at = ? WHERE category_id = ?", at, categoryId)
			tx.exec("UPDATE favourites SET deleted_at = ? WHERE category_id = ? AND deleted_at = 0", at, categoryId)
		}
	}

	fun getFavouritesText(categoryId: Long): List<Long> =
		db.query(
			"SELECT manga_id FROM favourites WHERE category_id = ? AND deleted_at = 0",
			categoryId,
		) { it.getLong(0) }

	fun isFavourite(mangaId: Long): Boolean =
		db.queryOne("SELECT COUNT(*) FROM favourites WHERE manga_id = ? AND deleted_at = 0", mangaId) { it.getLong(0) }!! > 0L

	/** Toggle the favourite. Adds to [categoryId] (or the default category), or removes everywhere. Returns true when now favourite. */
	fun toggleFavourite(mangaId: Long, categoryId: Long? = null): Boolean = db.transaction { tx ->
		val existing = tx.queryOne(
			"SELECT manga_id FROM favourites WHERE manga_id = ? AND deleted_at = 0 LIMIT 1", mangaId,
		) { it.getLong(0) }
		if (existing != null) {
			val now = System.currentTimeMillis()
			tx.exec("UPDATE favourites SET deleted_at = ? WHERE manga_id = ? AND deleted_at = 0", now, mangaId)
			false
		} else {
			val target = categoryId ?: tx.queryOne(
				"SELECT category_id FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC, category_id ASC LIMIT 1",
			) { it.getLong(0) }
				requireNotNull(target) { "No favourite category exists" }
			// revive a soft-deleted row if present, else insert
			val revived = tx.exec(
				"UPDATE favourites SET deleted_at = 0, created_at = ? WHERE manga_id = ? AND category_id = ?",
				System.currentTimeMillis(), mangaId, target,
			)
			if (revived == 0) {
				tx.exec(
					"INSERT INTO favourites(manga_id, category_id, sort_key, pinned, created_at, deleted_at) VALUES (?,?,?,?,?,?)",
					mangaId, target, 0, false, System.currentTimeMillis(), 0L,
				)
			}
			true
		}
	}

	fun getFavouriteManga(categoryId: Long): List<Manga> =
		db.query(
			"""SELECT m.* FROM favourites f JOIN manga m ON m.manga_id = f.manga_id
			   WHERE f.category_id = ? AND f.deleted_at = 0
			   ORDER BY f.pinned DESC, f.created_at DESC""",
			categoryId,
		) { stmt -> Rows.readManga(stmt) }

	/** All favourites in a category with tags excluded (covers list rendering). */
	fun countAllFavourites(): Int =
		db.queryOne("SELECT COUNT(DISTINCT manga_id) FROM favourites WHERE deleted_at = 0") { it.getInt(0) } ?: 0

	/** Backup dump — non-deleted rows only, matching upstream dump() (deleted_at = 0). */
	fun dumpFavourites(): List<FavouriteEntry> =
		db.query("SELECT manga_id, category_id, sort_key, pinned, created_at, deleted_at FROM favourites WHERE deleted_at = 0") { stmt ->
			FavouriteEntry(
				mangaId = stmt.getLong(stmt.indexOfColumn("manga_id")),
				categoryId = stmt.getLong(stmt.indexOfColumn("category_id")),
				sortKey = stmt.getInt(stmt.indexOfColumn("sort_key")),
				isPinned = stmt.getBoolean(stmt.indexOfColumn("pinned")),
				createdAt = stmt.getLong(stmt.indexOfColumn("created_at")),
				deletedAt = stmt.getLong(stmt.indexOfColumn("deleted_at")),
			)
		}

	fun dumpCategories(): List<FavouriteCategory> =
		db.query("SELECT * FROM favourite_categories WHERE deleted_at = 0") { stmt ->
			FavouriteCategory(
				categoryId = stmt.getInt(stmt.indexOfColumn("category_id")),
				createdAt = stmt.getLong(stmt.indexOfColumn("created_at")),
				sortKey = stmt.getInt(stmt.indexOfColumn("sort_key")),
				title = stmt.getString(stmt.indexOfColumn("title")).orEmpty(),
				order = stmt.getString(stmt.indexOfColumn("order")).orEmpty(),
				track = stmt.getBoolean(stmt.indexOfColumn("track")),
				isVisibleInLibrary = stmt.getBoolean(stmt.indexOfColumn("show_in_lib")),
				deletedAt = stmt.getLong(stmt.indexOfColumn("deleted_at")),
			)
		}

	/** Backup restore: unconditionally upsert a favourite row (including deleted_at from the file). */
	fun restoreFavourite(entry: FavouriteEntry) {
		db.exec(
			"""INSERT INTO favourites(manga_id, category_id, sort_key, pinned, created_at, deleted_at)
			   VALUES (?,?,?,?,?,?)
			   ON CONFLICT(manga_id, category_id) DO UPDATE SET
			     sort_key = excluded.sort_key, pinned = excluded.pinned,
			     created_at = excluded.created_at, deleted_at = excluded.deleted_at""",
			entry.mangaId, entry.categoryId, entry.sortKey, entry.isPinned, entry.createdAt, entry.deletedAt,
		)
	}

	fun restoreCategory(category: FavouriteCategory) {
		db.exec(
			"""INSERT INTO favourite_categories(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at)
			   VALUES (?,?,?,?,?,?,?,?)
			   ON CONFLICT(category_id) DO UPDATE SET
			     created_at = excluded.created_at, sort_key = excluded.sort_key, title = excluded.title,
			     `order` = excluded.`order`, track = excluded.track, show_in_lib = excluded.show_in_lib,
			     deleted_at = excluded.deleted_at""",
			category.categoryId, category.createdAt, category.sortKey, category.title,
			category.order, category.track, category.isVisibleInLibrary, category.deletedAt,
		)
	}
}

private fun SqliteStatement.indexOfColumn(name: String): Int {
	for (i in 0 until columnCount()) {
		if (columnName(i) == name) return i
	}
	throw SqliteException("Missing column '$name'")
}

class BookmarkStore(private val db: SqliteDatabase) {

	fun toggle(entry: BookmarkEntry): Boolean {
		val existing = get(entry.mangaId, entry.pageId)
		return if (existing != null) {
			db.exec("DELETE FROM bookmarks WHERE manga_id = ? AND page_id = ?", entry.mangaId, entry.pageId)
			false
		} else {
			db.exec(
				"INSERT INTO bookmarks(manga_id, page_id, chapter_id, page, scroll, image, created_at, percent) VALUES (?,?,?,?,?,?,?,?)",
				entry.mangaId, entry.pageId, entry.chapterId, entry.page, entry.scroll,
				entry.imageUrl, entry.createdAt, entry.percent,
			)
			true
		}
	}

	fun get(mangaId: Long, pageId: Long): BookmarkEntry? =
		db.queryOne("SELECT * FROM bookmarks WHERE manga_id = ? AND page_id = ?", mangaId, pageId) { it.readBookmark() }

	fun getForManga(mangaId: Long): List<BookmarkEntry> =
		db.query("SELECT * FROM bookmarks WHERE manga_id = ? ORDER BY created_at DESC", mangaId) { it.readBookmark() }

	fun getAll(): List<BookmarkEntry> =
		db.query("SELECT * FROM bookmarks ORDER BY created_at DESC") { it.readBookmark() }

	fun dump(): List<BookmarkEntry> = getAll()

	fun restore(entry: BookmarkEntry) {
		db.exec(
			"""INSERT INTO bookmarks(manga_id, page_id, chapter_id, page, scroll, image, created_at, percent)
			   VALUES (?,?,?,?,?,?,?,?)
			   ON CONFLICT(manga_id, page_id) DO UPDATE SET
			     chapter_id = excluded.chapter_id, page = excluded.page, scroll = excluded.scroll,
			     image = excluded.image, created_at = excluded.created_at, percent = excluded.percent""",
			entry.mangaId, entry.pageId, entry.chapterId, entry.page, entry.scroll,
			entry.imageUrl, entry.createdAt, entry.percent,
		)
	}

	private fun SqliteStatement.readBookmark() = BookmarkEntry(
		mangaId = getLong(indexOfColumn("manga_id")),
		pageId = getLong(indexOfColumn("page_id")),
		chapterId = getLong(indexOfColumn("chapter_id")),
		page = getInt(indexOfColumn("page")),
		scroll = getInt(indexOfColumn("scroll")),
		imageUrl = getString(indexOfColumn("image")).orEmpty(),
		createdAt = getLong(indexOfColumn("created_at")),
		percent = getFloat(indexOfColumn("percent")),
	)
}

class StatsStore(private val db: SqliteDatabase) {

	fun record(mangaId: Long, durationMs: Long, pages: Int, at: Long = System.currentTimeMillis()) {
		db.exec(
			"INSERT INTO stats(manga_id, started_at, duration, pages) VALUES (?,?,?,?) ON CONFLICT(manga_id, started_at) DO UPDATE SET duration = excluded.duration, pages = excluded.pages",
			mangaId, at, durationMs, pages,
		)
	}

	fun totalPagesRead(): Long =
		db.queryOne("SELECT COALESCE(SUM(pages), 0) FROM stats") { it.getLong(0) } ?: 0L

	fun totalTimeMs(): Long =
		db.queryOne("SELECT COALESCE(SUM(duration), 0) FROM stats") { it.getLong(0) } ?: 0L

	fun totalTitlesStarted(): Int =
		db.queryOne("SELECT COUNT(DISTINCT manga_id) FROM stats") { it.getInt(0) } ?: 0

	fun dump(): List<StatsEntry> =
		db.query("SELECT manga_id, started_at, duration, pages FROM stats") { stmt ->
			StatsEntry(
				mangaId = stmt.getLong(0),
				startedAt = stmt.getLong(1),
				duration = stmt.getLong(2),
				pages = stmt.getInt(3),
			)
		}

	fun restore(entry: StatsEntry) {
		db.exec(
			"INSERT INTO stats(manga_id, started_at, duration, pages) VALUES (?,?,?,?) ON CONFLICT(manga_id, started_at) DO UPDATE SET duration = excluded.duration, pages = excluded.pages",
			entry.mangaId, entry.startedAt, entry.duration, entry.pages,
		)
	}
}
