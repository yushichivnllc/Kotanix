package kotanix.ui

import kotanix.data.Sqlite
import kotanix.data.SqliteDatabase
import java.io.File

/** Data-dir + database location, XDG-compliant. */
object AppPaths {
	private val dataHome: File = run {
		val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
		File(xdg ?: (System.getProperty("user.home") + "/.local/share"), "kotanix").apply { mkdirs() }
	}

	val dbFile: File = File(dataHome, "kotanix.db")

	/** Opens (and creates if needed) the app database, after loading the JNI binding. */
	fun openDatabase(): SqliteDatabase {
		Sqlite.load()
		return SqliteDatabase.open(dbFile.absolutePath).also { db -> kotanix.data.Schema.createAll(db) }
	}
}

fun main(args: Array<String>) {
	val selftest = args.firstOrNull { it == "--selftest" || it.startsWith("--selftest=") }
	if (selftest != null) {
		val fixture = selftest.removePrefix("--selftest=").takeIf { it.isNotBlank() && it != "--selftest" }
		val code = SelfTest.run(fixture)
		kotlin.system.exitProcess(code)
	}
	KotanixApp().start()
}
