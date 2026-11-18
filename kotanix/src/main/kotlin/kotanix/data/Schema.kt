package kotanix.data

/**
 * DDL for the desktop database.
 *
 * Kotanix does NOT attach to, copy or migrate the Android `kotatsu-db` Room file.
 * It is a fresh database whose tables mirror the upstream entities at schema
 * version 28 (MangaDatabase.DATABASE_VERSION in Kotatsu-Redo 9.8.7), restricted
 * to what the Linux port uses. Because the file is always created fresh at v28,
 * there is no migration chain to port "as runnable code" — the backup/restore
 * path is the data-interchange story with Android (see Backup.kt).
 *
 * `PRAGMA user_version = 28` records the schema level this file was created at.
 */
object Schema {

	const val DATABASE_VERSION = 28
	const val DATABASE_FILE_NAME = "kotatsu-db"

	private val TABLES: List<String> = listOf(
		"""
		CREATE TABLE IF NOT EXISTS manga (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			title TEXT NOT NULL,
			alt_title TEXT,
			url TEXT NOT NULL,
			public_url TEXT NOT NULL,
			rating REAL NOT NULL,
			nsfw INTEGER NOT NULL,
			content_rating TEXT,
			cover_url TEXT NOT NULL,
			large_cover_url TEXT,
			state TEXT,
			author TEXT,
			source TEXT NOT NULL
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS tags (
			tag_id INTEGER PRIMARY KEY NOT NULL,
			title TEXT NOT NULL,
			`key` TEXT NOT NULL,
			source TEXT NOT NULL,
			pinned INTEGER NOT NULL DEFAULT 0
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS manga_tags (
			manga_id INTEGER NOT NULL,
			tag_id INTEGER NOT NULL,
			PRIMARY KEY (manga_id, tag_id),
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE,
			FOREIGN KEY (tag_id) REFERENCES tags(tag_id) ON DELETE CASCADE
		)
		""",
		"CREATE INDEX IF NOT EXISTS index_manga_tags_tag_id ON manga_tags(tag_id)",
		"""
		CREATE TABLE IF NOT EXISTS chapters (
			chapter_id INTEGER NOT NULL,
			manga_id INTEGER NOT NULL,
			name TEXT NOT NULL,
			number REAL NOT NULL,
			volume INTEGER NOT NULL,
			url TEXT NOT NULL,
			scanlator TEXT,
			upload_date INTEGER NOT NULL,
			branch TEXT,
			source TEXT NOT NULL,
			`index` INTEGER NOT NULL,
			PRIMARY KEY (manga_id, chapter_id),
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"CREATE INDEX IF NOT EXISTS index_chapters_manga_id ON chapters(manga_id)",
		"""
		CREATE TABLE IF NOT EXISTS history (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			created_at INTEGER NOT NULL,
			updated_at INTEGER NOT NULL,
			chapter_id INTEGER NOT NULL,
			page INTEGER NOT NULL,
			scroll REAL NOT NULL,
			percent REAL NOT NULL,
			deleted_at INTEGER NOT NULL DEFAULT 0,
			chapters INTEGER NOT NULL DEFAULT 0,
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"CREATE INDEX IF NOT EXISTS index_history_updated_at ON history(updated_at)",
		"""
		CREATE TABLE IF NOT EXISTS favourite_categories (
			category_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			created_at INTEGER NOT NULL,
			sort_key INTEGER NOT NULL,
			title TEXT NOT NULL,
			`order` TEXT NOT NULL,
			track INTEGER NOT NULL,
			show_in_lib INTEGER NOT NULL,
			deleted_at INTEGER NOT NULL DEFAULT 0
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS favourites (
			manga_id INTEGER NOT NULL,
			category_id INTEGER NOT NULL,
			sort_key INTEGER NOT NULL DEFAULT 0,
			pinned INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL,
			deleted_at INTEGER NOT NULL DEFAULT 0,
			PRIMARY KEY (manga_id, category_id),
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE,
			FOREIGN KEY (category_id) REFERENCES favourite_categories(category_id) ON DELETE CASCADE
		)
		""",
		"CREATE INDEX IF NOT EXISTS index_favourites_manga_id ON favourites(manga_id)",
		"CREATE INDEX IF NOT EXISTS index_favourites_category_id ON favourites(category_id)",
		"""
		CREATE TABLE IF NOT EXISTS preferences (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			mode INTEGER NOT NULL,
			cf_brightness REAL NOT NULL,
			cf_contrast REAL NOT NULL,
			cf_invert INTEGER NOT NULL,
			cf_grayscale INTEGER NOT NULL,
			cf_book INTEGER NOT NULL,
			title_override TEXT,
			cover_override TEXT,
			content_rating_override TEXT,
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS bookmarks (
			manga_id INTEGER NOT NULL,
			page_id INTEGER NOT NULL,
			chapter_id INTEGER NOT NULL,
			page INTEGER NOT NULL,
			scroll INTEGER NOT NULL,
			image TEXT NOT NULL,
			created_at INTEGER NOT NULL,
			percent REAL NOT NULL,
			PRIMARY KEY (manga_id, page_id),
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"CREATE INDEX IF NOT EXISTS index_bookmarks_manga_id ON bookmarks(manga_id)",
		"""
		CREATE TABLE IF NOT EXISTS local_index (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			path TEXT NOT NULL,
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS stats (
			manga_id INTEGER NOT NULL,
			started_at INTEGER NOT NULL,
			duration INTEGER NOT NULL,
			pages INTEGER NOT NULL,
			PRIMARY KEY (manga_id, started_at),
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS scrobblings (
			scrobbler INTEGER NOT NULL,
			id INTEGER NOT NULL,
			manga_id INTEGER NOT NULL,
			target_id INTEGER NOT NULL,
			status TEXT,
			chapter INTEGER NOT NULL,
			comment TEXT,
			rating REAL NOT NULL,
			PRIMARY KEY (scrobbler, id, manga_id)
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS suggestions (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			relevance REAL NOT NULL,
			created_at INTEGER NOT NULL,
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS tracks (
			manga_id INTEGER PRIMARY KEY NOT NULL,
			last_chapter_id INTEGER NOT NULL,
			chapters_new INTEGER NOT NULL,
			last_check_time INTEGER NOT NULL,
			last_chapter_date INTEGER NOT NULL,
			last_result INTEGER NOT NULL,
			last_error TEXT,
			FOREIGN KEY (manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS source_presets (
			preset_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			title TEXT NOT NULL,
			languages TEXT NOT NULL,
			sources TEXT NOT NULL,
			created_at INTEGER NOT NULL,
			sort_key INTEGER NOT NULL,
			deleted_at INTEGER NOT NULL DEFAULT 0
		)
		""",
		"""
		CREATE TABLE IF NOT EXISTS sources (
			source TEXT PRIMARY KEY NOT NULL,
			enabled INTEGER NOT NULL DEFAULT 1,
			sort_key INTEGER NOT NULL DEFAULT 0,
			added_in INTEGER NOT NULL DEFAULT 0,
			used_at INTEGER NOT NULL DEFAULT 0,
			pinned INTEGER NOT NULL DEFAULT 0,
			cf_state INTEGER NOT NULL DEFAULT 0
		)
		""",
	)

	fun createAll(db: SqliteDatabase) {
		for (stmt in TABLES) {
			db.exec(stmt.trimIndent())
		}
		db.exec("PRAGMA user_version = $DATABASE_VERSION")
	}

	/** True when the file is brand new (no user tables). */
	fun isEmpty(db: SqliteDatabase): Boolean =
		db.queryOne("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") {
			it.getLong(0)
		} == 0L

	fun checkVersion(db: SqliteDatabase) {
		val v = SqliteDatabase.userVersion(db)
		if (v != DATABASE_VERSION) {
			throw SqliteException(
				"kotatsu-db has user_version=$v, expected $DATABASE_VERSION. " +
					"Kotanix does not migrate the Android database file; restore a .bak backup instead of pointing Kotanix at the Android file.",
			)
		}
	}

	/** Opens (or creates) the desktop database at [path]; seeds the default favourite category like DatabasePrePopulateCallback does. */
	fun openOrCreate(path: String, defaultCategoryTitle: String): SqliteDatabase {
		val db = SqliteDatabase.open(path)
		if (isEmpty(db)) {
			db.transaction {
				createAll(it)
				// android: read_later — category sort_key 1, NEWEST order, track=true, show_in_lib=true
				it.exec(
					"INSERT INTO favourite_categories(created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) VALUES (?,?,?,?,?,?,?)",
					System.currentTimeMillis(), 1, defaultCategoryTitle, "NEWEST", true, true, 0L,
				)
			}
		} else {
			checkVersion(db)
		}
		return db
	}
}
