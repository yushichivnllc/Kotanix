package kotanix.ui

import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.data.HistoryStore
import kotanix.data.MangaStore
import kotanix.data.SourcesStore
import kotanix.data.SqliteDatabase
import kotanix.online.SourceCatalog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JLabel
import javax.swing.border.EmptyBorder

/**
 * Explore screen — Kotatsu Android's "Explore" tab (store listing shot 3) rebuilt for a
 * local-first desktop port:
 *
 *  - 2×2 shortcut tiles: Bộ nhớ máy (Local storage), Dấu trang (Bookmarks), Ngẫu nhiên
 *    (Random), Tải xuống (Downloads — not ported yet, shows an honest notice instead of
 *    a dead button);
 *  - "Gợi ý cho bạn" — suggestions drawn from the *local* library (random titles, preferring
 *    ones without history). Not the upstream remote-suggestions feed; rewording keeps it honest;
 *  - "Nguồn truyện" — pinned online sources from the bundled read-only catalog (see
 *    SourceCatalog / UPSTREAM.lock §2), plus sources present in the library. The header link
 *    opens the full catalog browser. Empty state is explicit.
 *
 * Typing in the global search pill switches this tab to a results grid (searchByTitle).
 */
class ExplorePanel(
	private val db: SqliteDatabase,
	private val mangaStore: MangaStore,
	private val onOpenDetails: (Manga) -> Unit,
	private val onOpenLocalTab: () -> Unit,
	private val onShowBookmarks: () -> Unit,
	private val onInfo: (String) -> Unit,
	private val onShowCatalog: () -> Unit,
) : JPanel(java.awt.CardLayout()) {

	private val historyStore = HistoryStore(db)
	private val sourcesStore = SourcesStore(db)
	private val catalogByName = SourceCatalog.SOURCES.associateBy { it.name }

	private val browse = buildBrowse()
	private val resultsHeader = JLabel("Kết quả tìm kiếm").apply {
		font = T.font(13f, bold = true)
		foreground = T.Dim
		border = EmptyBorder(4, 16, 4, 16)
	}
	private val results = CoverGridPanel("Không tìm thấy manga nào.") { onOpenDetails(it) }
	private val resultsCard = JPanel(BorderLayout()).apply {
		isOpaque = false
		add(resultsHeader, BorderLayout.NORTH)
		add(results, BorderLayout.CENTER)
	}

	private lateinit var suggestionHost: JPanel
	private lateinit var sourcesHost: JPanel

	init {
		isOpaque = false
		add(browse, "browse")
		add(resultsCard, "results")
		refreshData()
	}

	/** Global search routing — empty query returns to the browse view. */
	fun setQuery(query: String) {
		val q = query.trim()
		if (q.isEmpty()) {
			(layout as java.awt.CardLayout).show(this, "browse")
			return
		}
		val history = historyStore.getRecent(120).associate { it.manga.id to it.history.percent }
		val hits = mangaStore.searchByTitle(q, 60).map { GridItem(it, history[it.id]) }
		resultsHeader.text = "Kết quả tìm kiếm cho “$q” (${hits.size})"
		results.setItems(hits)
		(layout as java.awt.CardLayout).show(this, "results")
	}

	/** Re-run suggestions and the source roll-up (after import/backup restore). */
	fun refreshData() {
		if (::suggestionHost.isInitialized) refillSuggestions()
		if (::sourcesHost.isInitialized) refillSources()
	}

	// ------------------------------------------------------------ browse view

	private fun buildBrowse(): JScrollPane {
		val column = FillWidthPanel().apply {
			border = EmptyBorder(6, 16, 18, 16)
		}

		column.addRow(tiles())
		column.addRow(javax.swing.Box.createVerticalStrut(12), fill = java.awt.GridBagConstraints.NONE)
		column.addRow(sectionHeader("Gợi ý cho bạn", "Đổi") { refillSuggestions() })
		suggestionHost = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
		}
		column.addRow(suggestionHost)
		column.addRow(javax.swing.Box.createVerticalStrut(12), fill = java.awt.GridBagConstraints.NONE)
		column.addRow(sectionHeader("Nguồn truyện", "Tất cả ${SourceCatalog.SIZE} nguồn") { onShowCatalog() })
		sourcesHost = JPanel(GridLayout(0, 4, 10, 14)).apply {
			isOpaque = false
			maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
		}
		column.addRow(sourcesHost)
		column.addGlue()

		val scroll = JScrollPane(column).apply {
			border = null
			isOpaque = false
			viewport.isOpaque = false
			verticalScrollBar.unitIncrement = 48
			verticalScrollBar.background = T.Bg
		}
		scroll.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
		return scroll
	}

	private fun sectionHeader(title: String, action: String?, onAction: (() -> Unit)?): JPanel =
		JPanel(BorderLayout()).apply {
			isOpaque = false
			alignmentX = 0f
			maximumSize = Dimension(Int.MAX_VALUE, 36)
			border = EmptyBorder(0, 0, 8, 0)
			add(JLabel(title).apply {
				font = T.font(14.5f, bold = true)
				foreground = T.OnBg
			}, BorderLayout.WEST)
			if (action != null) {
				add(JLabel(action).apply {
					font = T.font(12.5f, bold = true)
					foreground = T.Accent
					cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
					addMouseListener(object : MouseAdapter() {
						override fun mouseClicked(e: MouseEvent) { onAction?.invoke() }
					})
				}, BorderLayout.EAST)
			}
		}

	// ---- 2x2 shortcut tiles

	private fun tiles(): JPanel =
		JPanel(GridLayout(2, 2, 14, 14)).apply {
			isOpaque = false
			preferredSize = Dimension(0, 144)
			add(TileButton("Bộ nhớ máy", { size -> Icons.folder(size, T.Accent) }) { onOpenLocalTab() })
			add(TileButton("Dấu trang", { size -> Icons.bookmark(size, T.Accent) }) { onShowBookmarks() })
			add(TileButton("Ngẫu nhiên", { size -> Icons.dice(size, T.Accent) }) { openRandom() })
			add(TileButton("Tải xuống", { size -> Icons.download(size, T.Accent) }) {
				onInfo("Tải xuống chưa được port — Kotanix hiện chỉ đọc manga local trên máy.")
			})
		}

	private fun openRandom() {
		val pick = mangaStore.findAll(200).randomOrNull()
		if (pick == null) onInfo("Thư viện trống — hãy thêm manga local trước.") else onOpenDetails(pick)
	}

	// ---- suggestions

	private fun refillSuggestions() {
		suggestionHost.removeAll()
		val withHistory = historyStore.getRecent(1000).map { it.manga.id }.toHashSet()
		val all = mangaStore.findAll(200)
		val unseen = all.filter { it.id !in withHistory }
		val picks = (if (unseen.isNotEmpty()) unseen else all).shuffled().take(3)
		if (picks.isEmpty()) {
			suggestionHost.add(JLabel("Thư viện trống — hãy thêm manga local hoặc nhập backup.").apply {
				font = T.font(12.5f)
				foreground = T.Dim
				border = EmptyBorder(10, 0, 0, 0)
			})
		} else {
			for (manga in picks) {
				val tags = mangaStore.findTags(manga.id).map { it.title }.take(6)
				suggestionHost.add(SuggestionRow(manga, tags) { onOpenDetails(it) })
				suggestionHost.add(javax.swing.Box.createVerticalStrut(2))
			}
		}
		suggestionHost.revalidate()
		suggestionHost.repaint()
	}

	// ---- sources roll-up

	private fun refillSources() {
		sourcesHost.removeAll()
		val rollup = mangaStore.countBySource()
		val rollupBySource = rollup.toMap()
		val pinned = sourcesStore.pinnedNames().filter { it in catalogByName }

		if (rollup.isEmpty() && pinned.isEmpty()) {
			sourcesHost.layout = FlowLayout(FlowLayout.LEFT, 0, 0)
			sourcesHost.add(JLabel("Chưa có nguồn nào — bấm “Tất cả ${SourceCatalog.SIZE} nguồn” để ghim nguồn yêu thích.").apply {
				font = T.font(12.5f)
				foreground = T.Dim
			})
			sourcesHost.revalidate()
			sourcesHost.repaint()
			return
		}

		sourcesHost.layout = GridLayout(0, 4, 10, 14)
		// Pinned catalog sources first (they fit on one row in practice), then what the
		// library actually uses.
		for (name in pinned) {
			if (name in rollupBySource) continue
			val entry = catalogByName.getValue(name)
			val broken = entry.isBroken || entry.isDeprecated
			sourcesHost.add(SourceTile(
				source = name,
				title = entry.title,
				subtitle = when {
					entry.isBroken -> "bị hỏng"
					entry.isDeprecated -> "cũ"
					entry.locale.isEmpty() -> "đa ngôn ngữ"
					else -> entry.locale
				},
				onClick = {
					onInfo("“${entry.title}” ($name) chưa được port — online chưa hỗ trợ, Milestone M2.\n" +
						"Ghim chỉ để đánh dấu nguồn yêu thích.")
				},
				onTogglePin = { sourcesStore.setPinned(name, false); refillSources() },
				pinned = true,
				broken = broken,
			))
		}
		for ((source, count) in rollup) {
			val catalogEntry = catalogByName[source]
			val title = catalogEntry?.title ?: source
			sourcesHost.add(SourceTile(
				source = source,
				title = if (source == LOCAL_SOURCE) "Local" else title,
				subtitle = "$count bộ",
				onClick = {
					if (source == LOCAL_SOURCE) onOpenLocalTab() else
						onInfo("Nguồn “$title” ($source) chưa được port — online chưa hỗ trợ, Milestone M2.")
				},
			))
		}
		sourcesHost.revalidate()
		sourcesHost.repaint()
	}
}

/** Rounded shortcut tile — Kotatsu's Explore quick actions (dark tile, accent icon+label). */
private class TileButton(
	private val label: String,
	icon: (Int) -> java.awt.image.BufferedImage,
	private val onClick: () -> Unit,
) : javax.swing.JComponent() {

	private val iconImage = icon(30)
	private var hover = false

	init {
		isOpaque = false
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		preferredSize = Dimension(0, 64)
		minimumSize = Dimension(120, 64)
		withClickThrough(
			onClick = { onClick() },
			onHover = { hover = it; repaint() },
		)
	}

	override fun paintComponent(g: java.awt.Graphics) {
		val g2 = T.g2(g)
		g2.color = if (hover) T.SurfaceSelected else T.SurfaceHigh
		g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 26f, 26f))
		g2.drawImage(iconImage, 22, (height - iconImage.height) / 2, null)
		g2.color = T.Accent
		g2.font = T.font(14.5f, bold = true)
		val fm = g2.fontMetrics
		g2.drawString(label, 66, (height + fm.ascent - fm.descent) / 2)
		g2.dispose()
	}
}

/** One suggestion row: cover left, title + tag line right (no card chrome, like Android). */
private class SuggestionRow(
	private val manga: Manga,
	tags: List<String>,
	private val onOpen: (Manga) -> Unit,
) : JPanel(BorderLayout()) {

	private var hover = false

	init {
		isOpaque = true
		background = T.Bg
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		border = EmptyBorder(4, 2, 4, 2)
		maximumSize = Dimension(Int.MAX_VALUE, 88)

		add(RoundedCover(CoverLoader.load(manga), manga.title, 54, 78), BorderLayout.WEST)

		val text = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			border = EmptyBorder(2, 12, 2, 0)
			add(JLabel("<html><body style='width:340px'>${escapeHtml(manga.title)}</body></html>").apply {
				font = T.font(14.5f, bold = true)
				foreground = T.OnBg
				alignmentX = 0f
			})
			add(javax.swing.Box.createVerticalStrut(4))
			val tagLine = tags.joinToString(", ").ifBlank { if (manga.source == LOCAL_SOURCE) "Local" else manga.source }
			add(JLabel("<html><body style='width:340px'>${escapeHtml(tagLine)}</body></html>").apply {
				font = T.font(11.5f)
				foreground = T.Dim
				alignmentX = 0f
			})
		}
		add(text, BorderLayout.CENTER)

		withClickThrough(
			onClick = { onOpen(manga) },
			onHover = { hover = it; repaint() },
		)
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
}

