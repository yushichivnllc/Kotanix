package kotanix

import kotanix.backup.Backup
import kotanix.core.*
import kotanix.data.*
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream

fun main() {
	Sqlite.load()
	val fixture = File("/home/user/Kotanix/app/src/androidTest/assets/kotatsu_test.bak")
	check(fixture.isFile) { "fixture missing: $fixture" }

	val db = SqliteDatabase.openInMemory()
	Schema.createAll(db)
	var settings: Map<String, String>? = null
	val report = Backup.restoreBackup(FileInputStream(fixture), db) { settings = it }
	println("restore report: ${report.summary()} identityKey=${report.identityKeyPresent} unsupported=${report.unsupportedSections}")
	check(report.failed.isEmpty()) { "restore failures: ${report.failed}" }
	check(report.restored["history"] == 6) { "expected 6 history, got ${report.restored["history"]}" }
	check(report.restored["categories"] == 2) { "expected 2 categories, got ${report.restored["categories"]}" }
	check(report.restored["favourites"] == 15) { "expected 15 favourites, got ${report.restored["favourites"]}" }

	val store = MangaStore(db)
	val horimiya = store.findById(-1991720469215445116L)
	check(horimiya != null) { "Horimiya missing after restore" }
	check(horimiya!!.title == "Horimiya") { "title mismatch: ${horimiya.title}" }
	check(horimiya.source == "MANGATOWN") { "source mismatch: ${horimiya.source}" }
	check(horimiya.url == "/manga/horimiya/") { "url mismatch: ${horimiya.url}" }
	check(horimiya.tags.size == 4) { "expected 4 tags, got ${horimiya.tags.size}" }
	check(kotlin.math.abs(horimiya.rating - 0.96599996f) < 0.001f) { "rating mismatch: ${horimiya.rating}" }
	println("manga + tags OK: ${horimiya.title} rating=${horimiya.rating} tags=${horimiya.tags.map { it.title }}")

	val hist = HistoryStore(db).get(-1991720469215445116L)
	check(hist != null) { "Horimiya history missing" }
	check(hist!!.page == 2 && hist.chapterId == -501138097088052928L) { "reading position lost: $hist" }
	check(hist.percent == -1f) { "percent default wrong: ${hist.percent}" }
	println("history position OK: page=${hist.page} chapterId=${hist.chapterId}")

	val favs = FavouritesStore(db).dumpFavourites()
	check(favs.size == 15)
	val horimiyaFav = favs.filter { it.mangaId == -1991720469215445116L }
	check(horimiyaFav.size == 1 && horimiyaFav[0].categoryId == 1L) { "Horimiya favourite wrong: $horimiyaFav" }
	val cats = FavouritesStore(db).dumpCategories()
	check(cats.size == 2) { "categories wrong: ${cats.map { it.title }}" }
	val catTitles = cats.map { it.title }.toSet()
	check(catTitles == setOf("Прочитать позже", "Эманон")) { "Cyrillic category titles corrupted: $catTitles" }
	println("favourites OK: ${favs.size} rows across categories ${cats.map { "${it.categoryId}:${it.title} track=${it.track} showInLib=${it.isVisibleInLibrary}" }}")

	// export -> re-import round trip into a fresh database
	val bak = ByteArrayOutputStream()
	Backup.createBackup(bak, db, emptyMap(), appVersionCode = 1)
	val db2 = SqliteDatabase.openInMemory()
	Schema.createAll(db2)
	val report2 = Backup.restoreBackup(bak.toByteArray().inputStream(), db2) { }
	check(report2.failed.isEmpty()) { "round-trip restore failures: ${report2.failed}" }
	check(report2.restored["history"] == 6 && report2.restored["favourites"] == 15 && report2.restored["categories"] == 2) {
		"round-trip counts wrong: ${report2.summary()}"
	}
	val h2 = HistoryStore(db2).get(-1991720469215445116L)!!
	check(h2.page == 2 && h2.chapterId == -501138097088052928L) { "round-trip position lost: $h2" }
	println("export→import round-trip OK: history=${report2.restored["history"]} favourites=${report2.restored["favourites"]}")

	db2.close()
	db.close()
	println("BACKUP SMOKE TEST PASSED")
}
