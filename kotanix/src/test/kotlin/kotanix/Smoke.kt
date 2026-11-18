package kotanix

import kotanix.core.*
import kotanix.data.*
import kotanix.local.LocalMangaParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {
	Sqlite.load()
	val tmp = createTempDir()
	val db = SqliteDatabase.openInMemory()
	Schema.createAll(db)
	val store = MangaStore(db)
	val manga = Manga(
		id = 12345L, title = "Test Manga", url = "https://example.invalid/manga/1",
		source = LOCAL_SOURCE, coverUrl = "",
		chapters = listOf(
			MangaChapter(id = 1, title = "Ch 1", url = "https://example.invalid/manga/1/ch1", number = 1f, source = LOCAL_SOURCE, index = 0),
			MangaChapter(id = 2, title = "Ch 2", url = "https://example.invalid/manga/1/ch2", number = 2f, source = LOCAL_SOURCE, index = 1),
		),
	)
	store.upsertManga(manga)
	store.replaceChapters(manga.id, manga.chapters!!)
	val loaded = store.findById(12345L)!!
	check(loaded.title == "Test Manga") { "manga round-trip failed" }
	val chapters = store.findChapters(12345L)
	check(chapters.size == 2) { "chapters not persisted: $chapters" }
	println("manga round-trip OK: ${chapters.size} chapters")

	val history = HistoryStore(db)
	history.upsert(HistoryEntry(
		mangaId = 12345L, createdAt = 1000L, updatedAt = 1000L,
		chapterId = 1, page = 3, scroll = 0.5f, percent = 0.42f, chaptersCount = 2,
	))
	var recent = history.getRecent(10)
	check(recent.size == 1 && recent[0].history.page == 3) { "history round-trip failed: $recent" }
	// resume: update position, created_at must be preserved
	history.upsert(HistoryEntry(
		mangaId = 12345L, createdAt = 1000L, updatedAt = 2000L,
		chapterId = 2, page = 7, scroll = 0.25f, percent = 0.83f, chaptersCount = 2,
	))
	val after = history.getRecent(10).first()
	check(after.history.page == 7 && after.history.chapterId == 2L) { "position update failed: $after" }
	check(after.history.createdAt == 1000L) { "created_at not preserved: ${after.history.createdAt}" }
	println("history round-trip OK: page=${after.history.page} chapter=${after.history.chapterId} created_at=${after.history.createdAt}")

	val cbz = File(tmp, "Test_Manga.cbz")
	ZipOutputStream(cbz.outputStream()).use { zip ->
		zip.putNextEntry(ZipEntry("page10.png")); zip.write(TINY_PNG); zip.closeEntry()
		zip.putNextEntry(ZipEntry("page2.png")); zip.write(TINY_PNG); zip.closeEntry()
		zip.putNextEntry(ZipEntry("page1.png")); zip.write(TINY_PNG); zip.closeEntry()
	}
	val parser = LocalMangaParser.get(cbz)!!
	val lm = parser.getManga(withDetails = true)
	check(lm.chapters?.size == 1) { "expected single chapter for root-level cbz, got ${lm.chapters}" }
	val pages = parser.getPages(lm.chapters!!.single())
	val names = pages.map { it.url.substringAfterLast('!') }
	check(names == listOf("page1.png", "page2.png", "page10.png")) { "natural order broken: $names" }
	val bytes = parser.readPage(pages[0])
	check(bytes.contentEquals(TINY_PNG)) { "page bytes mismatch" }
	println("local parser OK: ${pages.size} pages, order=$names, bytes=${bytes.size}")

	println("SMOKE TEST PASSED")
}

private fun createTempDir(): File {
	val d = File.createTempFile("kotanix-smoke", "")
	d.delete()
	d.mkdirs()
	return d
}

private val TINY_PNG = byteArrayOf(
	0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
	0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
