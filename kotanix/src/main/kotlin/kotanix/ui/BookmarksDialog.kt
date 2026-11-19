package kotanix.ui

import kotanix.core.Manga
import kotanix.data.BookmarkStore
import kotanix.data.MangaStore
import kotanix.data.SqliteDatabase
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.geom.RoundRectangle2D
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Bookmarks screen — Kotatsu's saved pages, as restored from backup or saved in the reader.
 * Lists one row per bookmark: manga cover, title, chapter/page and the captured percent.
 * Tapping a row opens the manga (reader resumes from history — restoring the exact bookmarked
 * page position is not wired into the reader yet, so we don't pretend it is).
 */
class BookmarksDialog(
	owner: JFrame,
	db: SqliteDatabase,
	onOpenManga: (Manga) -> Unit,
) : JDialog(owner, "Dấu trang", false) {

	init {
		defaultCloseOperation = DISPOSE_ON_CLOSE
		setSize(620, 560)
		minimumSize = Dimension(480, 400)
		setLocationRelativeTo(owner)
		contentPane.background = T.Bg
		layout = BorderLayout()

		add(BookmarksContent(db, { m -> onOpenManga(m); dispose() }) { dispose() }, BorderLayout.CENTER)
	}
}

/** Bookmark list body, separated from the dialog shell so it can be painted offscreen. */
class BookmarksContent(
	private val db: SqliteDatabase,
	private val onOpenManga: (Manga) -> Unit,
	private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

	init {
		isOpaque = true
		background = T.Bg
		componentOrientation = java.awt.ComponentOrientation.LEFT_TO_RIGHT
		add(buildTopBar(), BorderLayout.NORTH)
		add(buildList(), BorderLayout.CENTER)
	}

	private fun buildTopBar(): JPanel {
		val back = IconButton({ size -> Icons.backArrow(size, T.OnBg) }, 44) { onClose() }
		val title = JLabel("Dấu trang").apply {
			font = T.font(16f, bold = true)
			foreground = T.OnBg
		}
		return JPanel(BorderLayout()).apply {
			isOpaque = false
			border = EmptyBorder(10, 10, 4, 16)
			add(back, BorderLayout.WEST)
			add(title, BorderLayout.CENTER)
		}
	}

	private fun buildList(): JScrollPane {
		val bookmarkStore = BookmarkStore(db)
		val mangaStore = MangaStore(db)
		val entries = bookmarkStore.getAll()

		val column = FillWidthPanel().apply {
			border = EmptyBorder(4, 16, 16, 16)
		}

		if (entries.isEmpty()) {
			column.addRow(JLabel("Chưa có dấu trang nào.\nDấu trang từ backup sẽ xuất hiện ở đây.", SwingConstants.CENTER).apply {
				font = T.font(13f)
				foreground = T.Dim
				border = EmptyBorder(80, 40, 40, 40)
			})
		} else {
			for (entry in entries) {
				val manga = runCatching { mangaStore.findById(entry.mangaId) }.getOrNull()
				column.addRow(BookmarkRow(manga, entry.percent, entry.createdAt) {
					if (manga != null) onOpenManga(manga)
				})
			}
		}
		column.addGlue()

		return JScrollPane(column).apply {
			border = null
			isOpaque = false
			viewport.isOpaque = false
			verticalScrollBar.unitIncrement = 48
			verticalScrollBar.background = T.Bg
		horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
		}
	}
}

private class BookmarkRow(
	manga: Manga?,
	percent: Float,
	createdAt: Long,
	private val onOpen: () -> Unit,
) : JPanel(BorderLayout()) {

	private var hover = false

	init {
		isOpaque = true
		background = T.Bg
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		maximumSize = Dimension(Int.MAX_VALUE, 104)
		border = EmptyBorder(4, 4, 4, 4)

		val cover = RoundedCover(manga?.let { CoverLoader.load(it) }, manga?.title ?: "?", 60, 88)
		add(cover, BorderLayout.WEST)

		val text = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			border = EmptyBorder(4, 12, 4, 0)
			val titleText = manga?.title?.let { escapeHtml(it) } ?: "(manga không còn trong thư viện)"
			add(JLabel("<html><body style='width:380px'>$titleText</body></html>").apply {
				font = T.font(13.5f, bold = true)
				foreground = T.OnBg
				alignmentX = 0f
			})
			add(javax.swing.Box.createVerticalStrut(4))
			add(JLabel("Trang đã lưu · ${(percent * 100).toInt()}%").apply {
				font = T.font(12f)
				foreground = T.OnBg
				alignmentX = 0f
			})
			add(javax.swing.Box.createVerticalStrut(2))
			add(JLabel(formatDate(createdAt)).apply {
				font = T.font(10.5f)
				foreground = T.Dim
				alignmentX = 0f
			})
		}
		add(text, BorderLayout.CENTER)

		if (manga != null) {
			withClickThrough(
				onClick = { onOpen() },
				onHover = { hover = it; repaint() },
			)
		}
	}

	override fun paintComponent(g: java.awt.Graphics) {
		val g2 = T.g2(g)
		if (hover) {
			g2.color = T.SurfaceHigh
			g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
		}
		g2.dispose()
		super.paintComponent(g)
	}

	private fun escapeHtml(text: String): String =
		text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	private fun formatDate(at: Long): String =
		if (at <= 0L) "—" else runCatching {
			java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(at))
		}.getOrDefault("—")
}
