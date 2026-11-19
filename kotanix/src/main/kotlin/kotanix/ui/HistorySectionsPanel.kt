package kotanix.ui

import kotanix.core.Manga
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/** One history card: the manga plus where the user stopped. */
data class HistoryCard(val manga: Manga, val percent: Float?, val updatedAt: Long)

/**
 * History screen — Kotatsu Android's grouped home (store listing shot 1): section headers
 * ("Vừa xong" / "Hôm nay" / "Hôm qua" / ngày) above cover-grid rows, one scroll surface.
 * Filtering happens before grouping (chips: local-only / completed-only).
 *
 * Layout note: a single GridBagLayout column (anchor WEST, fill HORIZONTAL) — BoxLayout
 * alignment quirks centered the headers in this codebase's headless renders.
 */
class HistorySectionsPanel(
	private val emptyHint: String,
	private val onOpen: (Manga) -> Unit,
) : JPanel(BorderLayout()) {

	private val column = FillWidthPanel().apply {
		border = EmptyBorder(4, 16, 16, 16)
	}

	private val scroll = JScrollPane(column).apply {
		border = null
		isOpaque = false
		viewport.isOpaque = false
		viewport.background = T.Bg
		background = T.Bg
		verticalScrollBar.unitIncrement = 48
		verticalScrollBar.background = T.Bg
		horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
	}

	private val empty = JLabel(emptyHint, SwingConstants.CENTER).apply {
		font = T.font(14f)
		foreground = T.Dim
		border = EmptyBorder(80, 40, 80, 40)
	}

	init {
		isOpaque = false
	}

	fun setItems(items: List<HistoryCard>) {
		removeAll()
		if (items.isEmpty()) {
			add(empty, BorderLayout.CENTER)
		} else {
			column.removeAll()
			for ((title, groupItems) in group(items)) {
				column.addRow(JLabel(title).apply {
					font = T.font(14.5f, bold = true)
					foreground = T.OnBg
					border = EmptyBorder(10, 0, 8, 0)
					horizontalAlignment = SwingConstants.LEFT
				})
				column.addRow(gridOf(groupItems))
			}
			column.addGlue()
			add(scroll, BorderLayout.CENTER)
		}
		revalidate()
		repaint()
	}

	private fun gridOf(items: List<HistoryCard>): JPanel {
		val wrap = WrapPanel().apply {
			background = T.Bg
			border = null
		}
		for (item in items) {
			wrap.add(CoverCard(item.manga, item.percent).apply {
				onOpen = { onOpen(it.manga) }
			})
		}
		return wrap
	}

	private fun group(items: List<HistoryCard>): List<Pair<String, List<HistoryCard>>> {
		val now = System.currentTimeMillis()
		val todayStart = dayStart(now)
		val yesterdayStart = todayStart - DAY
		val groups = linkedMapOf<String, MutableList<HistoryCard>>()
		for (item in items) {
			val at = item.updatedAt
			val title = when {
				now - at < HOUR -> "Vừa xong"
				at >= todayStart -> "Hôm nay"
				at >= yesterdayStart -> "Hôm qua"
				else -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ENGLISH).format(java.util.Date(at))
			}
			groups.getOrPut(title) { mutableListOf() }.add(item)
		}
		return groups.entries.map { it.key to it.value }
	}

	private fun dayStart(at: Long): Long {
		val cal = java.util.Calendar.getInstance()
		cal.timeInMillis = at
		cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
		cal.set(java.util.Calendar.MINUTE, 0)
		cal.set(java.util.Calendar.SECOND, 0)
		cal.set(java.util.Calendar.MILLISECOND, 0)
		return cal.timeInMillis
	}

	companion object {
		private const val HOUR = 60L * 60L * 1000L
		private const val DAY = 24L * 60L * 60L * 1000L
	}
}
