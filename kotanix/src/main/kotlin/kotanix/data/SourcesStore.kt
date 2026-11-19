package kotanix.data

/**
 * Pins for online sources. Uses the `sources` table from the Kotatsu schema (kept for
 * backup compatibility: `kotatsu_test.bak` carries it). Kotanix does not read or write
 * `enabled` / `sort_key` / `cf_state` — it only toggles `pinned`, which drives the
 * "Nguồn truyện" section on the Explore tab.
 */
class SourcesStore(private val db: SqliteDatabase) {

	fun pinnedNames(): List<String> =
		db.query("SELECT source FROM sources WHERE pinned = 1 ORDER BY sort_key, source") {
			it.getString(0).orEmpty()
		}

	fun isPinned(source: String): Boolean =
		db.queryOne("SELECT pinned FROM sources WHERE source = ?", source) { it.getBoolean(0) } ?: false

	fun setPinned(source: String, pinned: Boolean) {
		db.transaction { tx ->
			tx.exec(
				"""INSERT INTO sources(source, enabled, sort_key, added_in, used_at, pinned, cf_state)
				   VALUES (?, 1, 0, 0, 0, ?, 0)
				   ON CONFLICT(source) DO UPDATE SET pinned = excluded.pinned""",
				source, if (pinned) 1 else 0,
			)
		}
	}
}
