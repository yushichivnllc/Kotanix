package kotanix

import kotanix.core.*
import kotanix.data.Sqlite
import kotanix.local.LocalMangaParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Folder / nested layout coverage for the local parser. Complements Smoke.kt (root-level CBZ).
 * Mirrors the layouts Kotatsu-Redo documents for local manga storage.
 */
fun main() {
	Sqlite.load()
	val tmp = createTempDir()

	// ---- 1. folder manga, chapters as subdirectories --------------------------------
	val folderManga = File(tmp, "Folder Manga")
	mkImages(File(folderManga, "Chapter 2"), listOf("Page 2.png", "Page 10.png", "Page 1.png"))
	mkImages(File(folderManga, "Chapter 1"), listOf("01.png", "02.png"))
	mkImages(folderManga, listOf("cover.png")) // stray root image becomes its own chapter per upstream rules
	val folderParser = LocalMangaParser.get(folderManga)!!
	val folderInfo = folderParser.getManga()
	check(folderInfo.chapters != null) { "folder chapters missing" }
	println("folder manga chapters: ${folderInfo.chapters!!.map { it.title }}")
	// chapters: "" (root, has cover.png) + Chapter 1 + Chapter 2 — natural order puts "" first
	check(folderInfo.chapters!!.size == 3) { "expected 3 chapters, got ${folderInfo.chapters!!.map { it.title }}" }
	val ch2 = folderInfo.chapters!!.single { it.title == "Chapter 2" }
	val ch2Pages = folderParser.getPages(ch2)
	val ch2Names = ch2Pages.map { it.url.substringAfterLast('/') }
	check(ch2Names == listOf("Page 1.png", "Page 2.png", "Page 10.png")) { "natural order broken: $ch2Names" }
	check(folderParser.readPage(ch2Pages.first()).contentEquals(TINY_PNG)) { "folder page bytes mismatch" }
	println("folder subdirectory manga OK: order=$ch2Names")

	// ---- 2. folder manga, chapter is a .cbz file ------------------------------------
	val folderCbzManga = File(tmp, "Folder Cbz Manga")
	folderCbzManga.mkdirs()
	mkImages(folderCbzManga, listOf("cover.png"))
	writeCbz(File(folderCbzManga, "Chapter 1.cbz"), listOf("p2.png", "p10.png", "p1.png"))
	val fcParser = LocalMangaParser.get(folderCbzManga)!!
	val fcInfo = fcParser.getManga()
	val fcChapters = fcInfo.chapters!!.map { it.title }
	check("Chapter 1" in fcChapters) { "cbz chapter missing: $fcChapters" }
	val fcCh = fcInfo.chapters!!.single { it.title == "Chapter 1" }
	val fcPages = fcParser.getPages(fcCh)
	val fcNames = fcPages.map { it.url.substringAfterLast('/').substringAfterLast('!').substringAfterLast('/') }
	check(fcNames.size == 3) { "expected 3 pages inside chapter cbz, got $fcNames" }
	check(fcParser.readPage(fcPages[0]).contentEquals(TINY_PNG)) { "chapter cbz page bytes mismatch" }
	println("folder with .cbz chapter OK: pages=$fcNames")

	// ---- 3. outer .cbz with nested chapter .cbz archives ----------------------------
	val outer = File(tmp, "Outer Manga.cbz")
	val ch1Bytes = zipBytes(listOf("001.png", "002.png"))
	val ch2Bytes = zipBytes(listOf("001.png", "010.png", "002.png"))
	ZipOutputStream(outer.outputStream()).use { zip ->
		zip.putNextEntry(ZipEntry("Chapter 1.cbz")); zip.write(ch1Bytes); zip.closeEntry()
		zip.putNextEntry(ZipEntry("Chapter 2.cbz")); zip.write(ch2Bytes); zip.closeEntry()
	}
	val outerParser = LocalMangaParser.get(outer)!!
	val outerInfo = outerParser.getManga()
	val outerChapters = outerInfo.chapters!!.map { it.title }
	println("nested cbz chapters: $outerChapters")
	check(outerChapters == listOf("Chapter 1", "Chapter 2")) { "nested chapters wrong: $outerChapters" }
	val outerCh2 = outerInfo.chapters!!.single { it.title == "Chapter 2" }
	val outerPages = outerParser.getPages(outerCh2)
	val opNames = outerPages.map { it.url.substringAfterLast('!') }
	check(opNames == listOf("001.png", "002.png", "010.png")) { "nested natural order broken: $opNames" }
	check(outerParser.readPage(outerPages[0]).contentEquals(TINY_PNG)) { "nested page bytes mismatch" }
	println("nested cbz OK: order=$opNames")

	// ---- 4. archive with plain directory prefixes (no nested cbz) -------------------
	val dirArchive = File(tmp, "Dir Archive.cbz")
	ZipOutputStream(dirArchive.outputStream()).use { zip ->
		for (name in listOf("Ch 1/02.png", "Ch 1/01.png", "Ch 1/10.png", "Ch 2/01.png")) {
			zip.putNextEntry(ZipEntry(name)); zip.write(TINY_PNG); zip.closeEntry()
		}
	}
	val daParser = LocalMangaParser.get(dirArchive)!!
	val daInfo = daParser.getManga()
	val daChapters = daInfo.chapters!!.map { it.title }
	check(daChapters == listOf("Ch 1", "Ch 2")) { "directory chapters wrong: $daChapters" }
	val daCh1 = daInfo.chapters!!.single { it.title == "Ch 1" }
	val daNames = daParser.getPages(daCh1).map { it.url.substringAfterLast('/') }
	check(daNames == listOf("01.png", "02.png", "10.png")) { "archive dir order broken: $daNames" }
	check(daParser.readPage(daParser.getPages(daCh1)[0]).contentEquals(TINY_PNG)) { "archive dir page bytes mismatch" }
	println("archive directory prefix OK: order=$daNames")

	println("LOCAL LAYOUT SMOKE TEST PASSED")
}

private fun createTempDir(): File {
	val d = File.createTempFile("kotanix-local", "")
	d.delete()
	d.mkdirs()
	return d
}

private fun mkImages(dir: File, names: List<String>) {
	dir.mkdirs()
	for (n in names) File(dir, n).writeBytes(TINY_PNG)
}

private fun writeCbz(file: File, entries: List<String>) {
	ZipOutputStream(file.outputStream()).use { zip ->
		for (n in entries) {
			zip.putNextEntry(ZipEntry(n)); zip.write(TINY_PNG); zip.closeEntry()
		}
	}
}

private fun zipBytes(entries: List<String>): ByteArray {
	val baos = java.io.ByteArrayOutputStream()
	ZipOutputStream(baos).use { zip ->
		for (n in entries) {
			zip.putNextEntry(ZipEntry(n)); zip.write(TINY_PNG); zip.closeEntry()
		}
	}
	return baos.toByteArray()
}

private val TINY_PNG = byteArrayOf(
	0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
	0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
