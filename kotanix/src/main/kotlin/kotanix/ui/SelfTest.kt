package kotanix.ui

import kotanix.backup.Backup
import kotanix.backup.RestoreReport
import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.data.*
import kotanix.local.LocalMangaParser
import java.io.File
import java.io.FileInputStream

/**
 * Headless verification path for the UI pipeline — runs the same store/parser calls the
 * Swing components make, against a temporary database in [tempRoot]. No window is created.
 * Returns process exit code (0 = all checks passed).
 */
object SelfTest {

	fun run(fixture: String?): Int {
		val temp = createTempDir("kotanix-selftest-") ?: run {
			System.err.println("SELFTEST FAIL: cannot create temp dir")
			return 1
		}
		try {
			return runIn(temp, fixture)
		} catch (e: Throwable) {
			System.err.println("SELFTEST FAIL: ${e.javaClass.simpleName}: ${e.message}")
			e.printStackTrace(System.err)
			return 1
		} finally {
			temp.deleteRecursively()
		}
	}

	private fun createTempDir(prefix: String): File? = try {
		java.nio.file.Files.createTempDirectory(prefix).toFile()
	} catch (_: Exception) {
		null
	}

	private fun runIn(temp: File, fixture: String?): Int {
		val checks = mutableListOf<Pair<String, () -> Unit>>()
		fun check(name: String, block: () -> Unit) {
			checks += name to block
		}

		Sqlite.load()
		val dbFile = File(temp, "selftest.db")
		val db = SqliteDatabase.open(dbFile.absolutePath)
		Schema.createAll(db)
		var failures = 0

		val mangaStore = MangaStore(db)
		val historyStore = HistoryStore(db)
		val favouritesStore = FavouritesStore(db)

		val fixtureFile = fixture?.let { File(it) }

		check("fixture exists: ${fixtureFile?.path}") {
			checkNotNull(fixtureFile) { "missing --selftest=<path-to-.bak> argument or file not found" }
			require(fixtureFile.isFile) { "not a file: $fixtureFile" }
		}
		check("restore backup fixture") {
			val report = FileInputStream(fixtureFile!!).use {
				Backup.restoreBackup(it, db) { /* settings sink: not asserted here */ }
			}
			println("  restore: ${report.summary()}")
			require((report.restored["history"] ?: 0) > 0) { "expected history entries in restored fixture" }
		}
		check("history rows visible via getRecent") {
			val recent = historyStore.getRecent(10)
			require(recent.isNotEmpty()) { "history empty after restore" }
			println("  recent[0] = ${recent[0].manga.title.take(40)} (chapter=${recent[0].history.chapterId} page=${recent[0].history.page})")
		}
		check("favourites categories listed") {
			val cats = favouritesStore.listCategories()
			require(cats.isNotEmpty()) { "no categories after restore" }
			println("  categories: " + cats.joinToString(", ") { it.title })
		}
		check("favourite manga listed") {
			val cat = favouritesStore.defaultCategoryId()
			require(cat > 0) { "no default favourite category" }
			val favs = favouritesStore.getFavouriteManga(cat)
			require(favs.isNotEmpty()) { "no favourites in default category after restore" }
			println("  favourites[0] = ${favs[0].title.take(40)}")
		}

		// Local manga import path (same calls as MainFrame.importLocalManga)
		var localManga: Manga? = null
		val localRoot = buildLocalFixture(temp)
		check("local fixture parsed and stored") {
			val parser = LocalMangaParser.get(localRoot)
				?: error("LocalMangaParser.get returned null for $localRoot")
			val manga = parser.getManga(withDetails = true)
			mangaStore.upsertManga(manga)
			manga.chapters?.let { mangaStore.replaceChapters(manga.id, it) }
			mangaStore.upsertLocalPath(manga.id, localRoot.absolutePath)
			localManga = mangaStore.findById(manga.id)
			require(localManga != null) { "manga not readable after upsert" }
			require(localManga!!.chapters.orEmpty().isNotEmpty()) { "no chapters stored" }
			println("  local: ${manga.title} (${manga.chapters!!.size} chapters)")
		}
		check("local path round-trip") {
			val manga = localManga!!
			require(mangaStore.getLocalPath(manga.id) == localRoot.absolutePath) { "local_index mismatch" }
		}

		for ((name, block) in checks) {
			try {
				block()
				println("PASS: $name")
			} catch (e: Throwable) {
				failures++
				println("FAIL: $name -> ${e.message}")
			}
		}

		db.close()
		println()
		return if (failures == 0) {
			println("SELFTEST OK — ${checks.size} checks")
			0
		} else {
			println("SELFTEST FAILED — $failures of ${checks.size} checks")
			1
		}
	}

	private fun buildLocalFixture(temp: File): File {
		val root = File(temp, "local-fixture")
		root.mkdirs()
		// One CBZ with 3 pages
		val cbz = File(root, "demo-001.cbz")
		java.util.zip.ZipOutputStream(cbz.outputStream()).use { zip ->
			for (i in 1..3) {
				zip.putNextEntry(java.util.zip.ZipEntry("page_%03d.png".format(i)))
				zip.write(tinyPngBytes())
				zip.closeEntry()
			}
		}
		// A cover image
		File(root, "cover.png").outputStream().use { it.write(tinyPngBytes()) }
		return root
	}

	/** 1x1 PNG (red pixel). */
	private fun tinyPngBytes(): ByteArray = byteArrayOf(
		0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
		0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
		0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
		0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
		0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
		0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00,
		0x00, 0x00, 0x03, 0x00, 0x01, 0xFD.toByte(), 0x7E, 0x77,
		0x8A.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
		0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
	)

}
