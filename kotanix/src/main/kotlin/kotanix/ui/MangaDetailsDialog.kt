package kotanix.ui

import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.core.MangaState
import kotanix.data.FavouritesStore
import kotanix.data.HistoryStore
import kotanix.data.SqliteDatabase
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Manga details screen — Kotatsu Android 2.x layout (shot 2 in the store listing) rebuilt for
 * Swing: back arrow + overflow (⋮), cover + title + alt title, "Favourite this" pill, info
 * card (source / author / state / chapters / progress), description, genre chips, reversed
 * chapter list and a big teal "Đọc tiếp" CTA. Data comes from the local DB (tags/chapters
 * restored from backup or parsed from local files); online-only fields degrade honestly.
 *
 * Simplifications vs Android (documented, not faked): the favourite pill toggles instead of
 * opening a category dropdown; the overflow only offers local actions (open containing folder,
 * remove from library, copy title) — no share/download because there is no network source.
 */
class MangaDetailsDialog(
	owner: JFrame,
	db: SqliteDatabase,
	manga: Manga,
	localPath: String?,
	onOpenReader: (Manga, chapterIndex: Int?) -> Boolean,
	onFavouriteChanged: () -> Unit,
	onRemoveLocal: (Manga) -> Unit,
) : JDialog(owner, manga.title, false) {

	init {
		defaultCloseOperation = DISPOSE_ON_CLOSE
		setSize(840, 720)
		minimumSize = Dimension(640, 520)
		setLocationRelativeTo(owner)
		contentPane.background = T.Bg
		layout = BorderLayout()
		add(MangaDetailsContent(db, manga, localPath,
			onClose = { dispose() },
			onOpenReader = onOpenReader,
			onFavouriteChanged = onFavouriteChanged,
			onRemoveLocal = { m -> onRemoveLocal(m) },
		), BorderLayout.CENTER)
	}
}

/**
 * The details screen body (back bar + scrollable content + bottom CTA) as a standalone panel —
 * separated from the dialog shell so it can be painted offscreen in previews/tests.
 */
class MangaDetailsContent(
	db: SqliteDatabase,
	private val manga: Manga,
	private val localPath: String?,
	private val onClose: () -> Unit,
	private val onOpenReader: (Manga, chapterIndex: Int?) -> Boolean,
	private val onFavouriteChanged: () -> Unit,
	private val onRemoveLocal: (Manga) -> Unit,
) : JPanel(BorderLayout()) {

	private val favouritesStore = FavouritesStore(db)
	private val historyStore = HistoryStore(db)

	private var isFavourite = favouritesStore.isFavourite(manga.id)

	private val favouriteChip: PillChip = PillChip(
		{ if (isFavourite) "Yêu thích ✓" else "Yêu thích" },
		Icons.heart(18, T.Accent, filled = true),
	) {
		isFavourite = favouritesStore.toggleFavourite(manga.id)
		favouriteChip.toggled = isFavourite
		favouriteChip.refreshText()
		onFavouriteChanged()
	}

	init {
		isOpaque = true
		background = T.Bg
		// unparented components get UNKNOWN orientation (BoxLayout then anchors RTL) — be explicit
		componentOrientation = java.awt.ComponentOrientation.LEFT_TO_RIGHT
		add(buildTopBar(), BorderLayout.NORTH)
		add(buildBody(), BorderLayout.CENTER)
		add(buildBottomBar(), BorderLayout.SOUTH)

		favouriteChip.toggled = isFavourite
	}

	// ------------------------------------------------------------ pieces

	private fun buildTopBar(): JPanel {
		val back = IconButton({ size -> Icons.backArrow(size, T.OnBg) }, 44) { onClose() }
		val overflow = IconButton({ size -> Icons.moreVert(size, T.OnBg) }, 44) { showOverflow() }
		val overflowWrap = JPanel(BorderLayout()).apply {
			isOpaque = false
			add(overflow, BorderLayout.EAST)
		}
		return JPanel(BorderLayout()).apply {
			isOpaque = false
			border = EmptyBorder(10, 10, 4, 12)
			add(back, BorderLayout.WEST)
			add(overflowWrap, BorderLayout.EAST)
		}
	}

	private fun showOverflow() {
		val menu = darkPopup()
		if (localPath != null) {
			menu.add(darkMenuItem("Mở thư mục chứa", Icons.folder(18, T.OnBg)) { openContainingFolder() })
			menu.add(darkMenuItem("Gỡ khỏi thư viện local", Icons.trash(18, T.OnBg)) {
				onRemoveLocal(manga)
				onClose()
			})
			menu.addSeparator()
		}
		menu.add(darkMenuItem("Sao chép tiêu đề") { copyTitle() })
		menu.show(this, width - menu.preferredSize.width - 12, 56)
	}

	private fun openContainingFolder() {
		val path = localPath ?: return
		val target = java.io.File(path).let { if (it.isFile) it.parentFile else it }
		if (target != null && target.isDirectory) {
			runCatching { java.awt.Desktop.getDesktop().open(target) }
		}
	}

	private fun copyTitle() {
		runCatching {
			java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
				java.awt.datatransfer.StringSelection(manga.title), null,
			)
		}
	}

	private fun buildBody(): JScrollPane {
		val column = FillWidthPanel().apply {
			border = EmptyBorder(4, 20, 16, 20)
		}
		column.addRow(buildHeader())
		column.addRow(InfoCard())
		column.addRow(sectionTitle("Mô tả"))
		column.addRow(descriptionBlock())
		val tags = manga.tags.map { it.title }.sorted()
		if (tags.isNotEmpty()) {
			column.addRow(sectionTitle("Thể loại"))
			column.addRow(tagRow(tags))
		}
		column.addRow(sectionTitle("Chapters"))
		column.addRow(chapterList())
		column.addGlue()

		return JScrollPane(column).apply {
			border = null
			isOpaque = false
			viewport.isOpaque = false
			verticalScrollBar.unitIncrement = 48
			verticalScrollBar.background = T.Bg
		}
	}

	private fun buildHeader(): JPanel {
		val cover = RoundedCover(CoverLoader.load(manga), manga.title, 150, 225)

		val title = JLabel("<html><body style='width:380px'>${escape(manga.title)}</body></html>").apply {
			font = T.font(21f, bold = true)
			foreground = T.OnBg
			alignmentX = 0f
		}
		val alt = manga.altTitles.firstOrNull()?.let {
			JLabel(it).apply {
				font = T.font(13f)
				foreground = T.Dim
				alignmentX = 0f
			}
		}

		favouriteChip.alignmentX = 0f

		val right = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			border = EmptyBorder(2, 18, 0, 0)
			add(title)
			alt?.let { add(javax.swing.Box.createVerticalStrut(4)); add(it) }
			add(javax.swing.Box.createVerticalStrut(16))
			add(favouriteChip)
		}
		return JPanel(BorderLayout()).apply {
			isOpaque = false
			add(cover, BorderLayout.WEST)
			add(right, BorderLayout.CENTER)
			border = EmptyBorder(4, 0, 16, 0)
			alignmentX = 0f
			maximumSize = Dimension(Int.MAX_VALUE, 245)
			// GridBag rows must not shrink these below their painting height
			minimumSize = Dimension(0, 245)
			preferredSize = Dimension(0, 245)
		}
	}

	private fun sectionTitle(text: String): JLabel =
		JLabel(text).apply {
			font = T.font(14.5f, bold = true)
			foreground = T.OnBg
			border = EmptyBorder(2, 0, 8, 0)
			alignmentX = 0f
		}

	private fun descriptionBlock(): JLabel {
		val text = manga.description?.takeIf { it.isNotBlank() } ?: "Không có mô tả."
		val html = escape(text).replace("\n", "<br>")
		return JLabel("<html><body style='width:640px'>$html</body></html>").apply {
			font = T.font(13f)
			foreground = T.Dim
			border = EmptyBorder(0, 0, 12, 0)
			alignmentX = 0f
			maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
			verticalAlignment = javax.swing.SwingConstants.TOP
		}
	}

	private fun tagRow(tags: List<String>): JPanel =
		JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
			isOpaque = false
			border = EmptyBorder(0, 0, 12, 0)
			alignmentX = 0f
			tags.take(10).forEach { tag ->
				add(JLabel(tag).apply {
					font = T.font(12f)
					foreground = T.OnBg
					border = javax.swing.border.CompoundBorder(
						javax.swing.border.LineBorder(T.Outline, 1, true),
						EmptyBorder(6, 14, 6, 14),
					)
				})
			}
		}

	private fun chapterList(): JPanel {
		val list = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			alignmentX = 0f
		}
		val chapters = manga.chapters.orEmpty()
		if (chapters.isEmpty()) {
			list.add(JLabel("Không có chapter local.").apply {
				font = T.font(13f)
				foreground = T.Dim
			})
		} else {
			chapters.withIndex().reversed().forEach { (index, chapter) ->
				list.add(chapterRow(index, chapter))
			}
		}
		list.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
		return list
	}

	private fun chapterRow(index: Int, chapter: MangaChapter): JPanel {
		val number = JLabel("${index + 1}").apply {
			font = T.font(12.5f, bold = true)
			foreground = T.Dim
			preferredSize = Dimension(44, 48)
			horizontalAlignment = SwingConstants.CENTER
		}
		val scanlator = chapter.scanlator?.takeIf { it.isNotBlank() }
		val titleText = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
		val title = JLabel(titleText).apply {
			font = T.font(13.5f)
			foreground = T.OnBg
		}
		val subtitle = listOf(formatDate(chapter.uploadDate), scanlator).filterNotNull().joinToString(" · ")
		val date = JLabel(subtitle.ifBlank { "—" }).apply {
			font = T.font(11f)
			foreground = T.Dim
		}
		val text = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			add(title)
			add(date)
			border = EmptyBorder(4, 0, 4, 0)
		}
		return object : JPanel(BorderLayout()) {
			private var hover = false

			init {
				isOpaque = true
				background = T.Bg
				cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
				add(number, BorderLayout.WEST)
				add(text, BorderLayout.CENTER)
				border = EmptyBorder(2, 4, 2, 4)
				maximumSize = Dimension(Int.MAX_VALUE, 56)
				addMouseListener(object : MouseAdapter() {
					override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
					override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
					override fun mouseClicked(e: MouseEvent) {
						if (onOpenReader(manga, index)) onClose()
					}
				})
			}

			override fun paintComponent(g: java.awt.Graphics) {
				background = if (hover) T.SurfaceHigh else T.Bg
				super.paintComponent(g)
			}
		}
	}

	private fun formatDate(uploadDate: Long): String? =
		if (uploadDate <= 0L) null else runCatching {
			java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ENGLISH).format(java.util.Date(uploadDate))
		}.getOrNull()

	private fun buildBottomBar(): JPanel {
		val bar = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
			background = Color(0x0B1013)
			border = EmptyBorder(12, 16, 12, 16)
		}
		bar.add(ExtendedFab("Đọc tiếp", Icons.bookOpen(24, T.OnAccent)) {
			if (onOpenReader(manga, null)) onClose()
		})
		return bar
	}

	private fun escape(text: String): String =
		text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	/** Rounded surface card: source / author / state / chapters / progress (Kotatsu info card). */
	private inner class InfoCard : JPanel(BorderLayout()) {
		private val progressPercent: Float = historyStore.get(manga.id)?.percent ?: 0f
		private val rows: List<Pair<String, String>> = listOf(
			"Nguồn" to (if (manga.source == LOCAL_SOURCE) "Local" else manga.source),
			"Tác giả" to (manga.authors.joinToString(", ").ifBlank { "—" }),
			"Tình trạng" to stateLabel(manga.state),
			"Chapters" to (manga.chapters?.size?.toString() ?: "—"),
		)

		init {
			isOpaque = false
			val grid = JPanel(GridLayout(rows.size + 1, 2, 18, 8)).apply {
				isOpaque = false
				for ((label, value) in rows) {
					add(infoLabel(label, dim = true, bold = true))
					add(infoLabel(value, dim = false, bold = false))
				}
				add(infoLabel("Tiến độ", dim = true, bold = true))
				add(progressBlock())
			}
			add(grid, BorderLayout.CENTER)
			border = EmptyBorder(14, 16, 14, 16)
			maximumSize = Dimension(Int.MAX_VALUE, 240)
			alignmentX = 0f
		}

		override fun getPreferredSize(): Dimension = super.getPreferredSize().also {
			it.height = 176
		}

		override fun getMinimumSize(): Dimension = Dimension(0, 176)

		override fun paintComponent(g: java.awt.Graphics) {
			val g2 = T.g2(g)
			g2.color = T.SurfaceHigh
			g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f))
			g2.dispose()
		}

		private fun infoLabel(text: String, dim: Boolean, bold: Boolean): JLabel =
			JLabel(text).apply {
				font = T.font(13f, bold = bold)
				foreground = if (dim) T.Dim else T.OnBg
			}

		private fun progressBlock(): JPanel =
			JPanel(BorderLayout()).apply {
				isOpaque = false
				add(ProgressLine(progressPercent), BorderLayout.CENTER)
				add(JLabel(" ${(progressPercent * 100).toInt()}%").apply {
					font = T.font(12f, bold = true)
					foreground = T.OnBg
				}, BorderLayout.EAST)
			}
	}

	private fun stateLabel(state: MangaState?): String = when (state) {
		MangaState.ONGOING -> "Đang ra"
		MangaState.FINISHED -> "Hoàn thành"
		MangaState.PAUSED -> "Tạm dừng"
		MangaState.ABANDONED -> "Bỏ dở"
		MangaState.UPCOMING -> "Sắp ra"
		MangaState.REMOVED -> "Đã gỡ"
		MangaState.RESTRICTED -> "Giới hạn"
		else -> "—"
	}
}

/** Thin accent progress line used in the info card. */
private class ProgressLine(
	private val percent: Float,
) : javax.swing.JComponent() {
	init {
		isOpaque = false
		preferredSize = Dimension(160, 5)
		minimumSize = Dimension(80, 5)
	}

	override fun paintComponent(g: java.awt.Graphics) {
		g.color = T.Outline
		g.fillRect(0, 0, width, height)
		g.color = T.Accent
		g.fillRect(0, 0, (width * percent.coerceIn(0f, 1f)).toInt(), height)
	}
}
