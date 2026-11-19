package kotanix.data

import java.io.Closeable
import kotanix.core.Json

class SqliteException(message: String) : RuntimeException(message)

/**
 * Low-level binding. Every entry point throws [SqliteException] on failure.
 * Text crosses as UTF-8 bytes — never as modified UTF-8 jstrings.
 */
object Sqlite {

	const val COLUMN_INTEGER = 1
	const val COLUMN_FLOAT = 2
	const val COLUMN_TEXT = 3
	const val COLUMN_BLOB = 4
	const val COLUMN_NULL = 5

	const val ROW = 100
	const val DONE = 101

	@JvmStatic external fun nativeOpen(path: ByteArray): Long
	@JvmStatic external fun nativeClose(db: Long)
	@JvmStatic external fun nativePrepare(db: Long, sql: ByteArray): Long
	@JvmStatic external fun nativeStep(db: Long, stmt: Long): Int
	@JvmStatic external fun nativeFinalize(db: Long, stmt: Long)
	@JvmStatic external fun nativeReset(db: Long, stmt: Long)
	@JvmStatic external fun nativeBindAll(db: Long, stmt: Long, args: Array<out Any?>)
	@JvmStatic external fun nativeColumnCount(stmt: Long): Int
	@JvmStatic external fun nativeColumnType(stmt: Long, i: Int): Int
	@JvmStatic external fun nativeColumnLong(stmt: Long, i: Int): Long
	@JvmStatic external fun nativeColumnDouble(stmt: Long, i: Int): Double
	@JvmStatic external fun nativeColumnBytes(stmt: Long, i: Int): ByteArray
	@JvmStatic external fun nativeColumnName(stmt: Long, i: Int): ByteArray?
	@JvmStatic external fun nativeLastInsertRowId(db: Long): Long
	@JvmStatic external fun nativeChanges(db: Long): Int

	fun load() {
		// java.library.path includes the lib/ directory next to the jar in the .deb layout
		System.loadLibrary("kotanixsqlite")
	}
}

class SqliteStatement internal constructor(
	private val db: SqliteDatabase,
	private val stmt: Long,
) : Closeable {

	var closed = false
		private set

	internal fun bind(args: Array<out Any?>) {
		Sqlite.nativeBindAll(db.ptr, stmt, args)
	}

	fun next(): Boolean = when (val rc = Sqlite.nativeStep(db.ptr, stmt)) {
		Sqlite.ROW -> true
		Sqlite.DONE -> false
		else -> throw SqliteException("Unexpected step result $rc")
	}

	fun columnCount(): Int = Sqlite.nativeColumnCount(stmt)
	fun columnType(i: Int): Int = Sqlite.nativeColumnType(stmt, i)
	fun isNull(i: Int): Boolean = columnType(i) == Sqlite.COLUMN_NULL

	fun getLong(i: Int): Long = Sqlite.nativeColumnLong(stmt, i)
	fun getDouble(i: Int): Double = Sqlite.nativeColumnDouble(stmt, i)
	fun getBoolean(i: Int): Boolean = Sqlite.nativeColumnLong(stmt, i) != 0L
	fun getFloat(i: Int): Float = Sqlite.nativeColumnDouble(stmt, i).toFloat()
	fun getInt(i: Int): Int = Sqlite.nativeColumnLong(stmt, i).toInt()

	fun getString(i: Int): String? = when (columnType(i)) {
		Sqlite.COLUMN_NULL -> null
		Sqlite.COLUMN_TEXT -> String(Sqlite.nativeColumnBytes(stmt, i), Charsets.UTF_8)
		else -> java.lang.Long.toString(Sqlite.nativeColumnLong(stmt, i))
	}

	fun getBytes(i: Int): ByteArray? = when (columnType(i)) {
		Sqlite.COLUMN_NULL -> null
		else -> Sqlite.nativeColumnBytes(stmt, i)
	}

	fun columnName(i: Int): String = Sqlite.nativeColumnName(stmt, i)?.let { String(it, Charsets.UTF_8) } ?: ""

	override fun close() {
		if (!closed) {
			closed = true
			Sqlite.nativeFinalize(db.ptr, stmt)
		}
	}
}

/**
 * One SQLite connection. Not thread-safe; the repositories serialise calls through a single lock.
 * Use [transaction] for multi-statement writes — nested transactions use SAVEPOINTs.
 */
class SqliteDatabase internal constructor(internal val ptr: Long) : Closeable {

	private val lock = Any()
	private var transactionDepth = 0
	private var closed = false

	fun open(): SqliteDatabase = this

	fun exec(sql: String, vararg args: Any?): Int = synchronized(lock) {
		ensureOpen()
		prepareStmt(sql, args).use { stmt ->
			while (stmt.next()) {
				// drain rows if any
			}
		}
		Sqlite.nativeChanges(ptr)
	}

	fun <T> query(sql: String, vararg args: Any?, row: (SqliteStatement) -> T): List<T> = synchronized(lock) {
		ensureOpen()
		val out = ArrayList<T>()
		prepareStmt(sql, args).use { stmt ->
			while (stmt.next()) {
				out.add(row(stmt))
			}
		}
		out
	}

	fun <T> queryOne(sql: String, vararg args: Any?, row: (SqliteStatement) -> T): T? =
		query(sql, *args, row = row).firstOrNull()

	/** Number of rows a mutation affected (last UPDATE/INSERT/DELETE). */
	fun changes(): Int = Sqlite.nativeChanges(ptr)

	fun lastInsertRowId(): Long = Sqlite.nativeLastInsertRowId(ptr)

	fun <T> transaction(block: (SqliteDatabase) -> T): T = synchronized(lock) {
		ensureOpen()
		if (transactionDepth == 0) {
			rawExec("BEGIN IMMEDIATE")
		} else {
			rawExec("SAVEPOINT kotanix_sp_$transactionDepth")
		}
		transactionDepth++
		try {
			val result = block(this)
			transactionDepth--
			if (transactionDepth == 0) {
				rawExec("COMMIT")
			} else {
				rawExec("RELEASE kotanix_sp_$transactionDepth")
			}
			result
		} catch (e: Throwable) {
			transactionDepth--
			if (transactionDepth == 0) {
				runCatching { rawExec("ROLLBACK") }
			} else {
				runCatching { rawExec("ROLLBACK TO kotanix_sp_$transactionDepth") }
				runCatching { rawExec("RELEASE kotanix_sp_$transactionDepth") }
			}
			throw e
		}
	}

	private fun rawExec(sql: String) {
		prepareStmt(sql, emptyArray()).use { stmt ->
			while (stmt.next()) { /* no rows expected */ }
		}
	}

	private fun prepareStmt(sql: String, args: Array<out Any?>): SqliteStatement {
		val stmt = Sqlite.nativePrepare(ptr, sql.toByteArray(Charsets.UTF_8))
		if (stmt == 0L) throw SqliteException("Failed to prepare statement: $sql")
		val wrapped = SqliteStatement(this, stmt)
		if (args.isNotEmpty()) wrapped.bind(args)
		return wrapped
	}

	private fun ensureOpen() {
		if (closed) throw SqliteException("Database is closed")
	}

	override fun close() = synchronized(lock) {
		if (!closed) {
			closed = true
			Sqlite.nativeClose(ptr)
		}
	}

	companion object {
		fun open(path: String): SqliteDatabase {
			Sqlite.load()
			val ptr = Sqlite.nativeOpen(path.toByteArray(Charsets.UTF_8))
			if (ptr == 0L) throw SqliteException("Could not open database at $path")
			val db = SqliteDatabase(ptr)
			db.exec("PRAGMA foreign_keys = ON")
			return db
		}

		/** In-memory database — used by the unit tests. */
		fun openInMemory(): SqliteDatabase = open(":memory:")

		fun pragma(db: SqliteDatabase, name: String): Long =
			db.queryOne("PRAGMA $name") { it.getLong(0) } ?: -1L

		fun userVersion(db: SqliteDatabase): Int = db.queryOne("PRAGMA user_version") { it.getInt(0) } ?: -1
	}
}
