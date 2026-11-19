package kotanix.ui

import kotanix.data.SourcesStore
import kotanix.data.SqliteDatabase
import kotanix.online.SourceCatalog
import kotanix.online.CatalogSource
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.Normalizer
import java.util.Locale
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.DefaultListCellRenderer
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

/** Content types considered adult in the catalog; hidden unless the user opts in. */
private val ADULT_TYPES = setOf("HENTAI", "ARTIST_CG", "GAME_CG")

/** How many tiles are materialised at once — the catalog has over a thousand entries. */
private const val MAX_SHOWN = 240

/**
 * Sources catalog screen — read-only browse of the online source catalog bundled with
 * Kotanix (generated from the pinned kotatsu-parsers-redo revision, see UPSTREAM.lock §2).
 *
 * Kotanix cannot read online sources yet (no parser runtime, Milestone M2), so this screen
 * is honestly labelled as a catalogue: every source tile opens a notice; tapping the pin
 * glyph pins/unpins the source for the “Nguồn truyện” section of the Explore tab. The list
 * of sources is real data from the pinned catalog, including upstream's broken flags.
 */
class SourcesCatalogDialog(
	owner: JFrame,
	db: SqliteDatabase,
	onInfo: (String) -> Unit,
	onPinsChanged: () -> Unit,
) : JDialog(owner, "Nguồn truyện", true) {

	init {
		defaultCloseOperation = DISPOSE_ON_CLOSE
		setSize(880, 640)
		minimumSize = Dimension(640, 480)
		setLocationRelativeTo(owner)
		contentPane.background = T.Bg
		layout = BorderLayout()
		add(SourcesCatalogContent(db, onInfo, { dispose() }, onPinsChanged), BorderLayout.CENTER)
	}
}

/** Body of the sources catalog, separated from the dialog shell so it can be painted offscreen. */
class SourcesCatalogContent(
	private val db: SqliteDatabase,
	private val onInfo: (String) -> Unit,
	private val onClose: () -> Unit,
	private val onPinsChanged: () -> Unit,
) : JPanel(BorderLayout()) {

	private val store = SourcesStore(db)
	private val byName = SourceCatalog.SOURCES.associateBy { it.name }

	private var query = ""
	private var localeFilter = ""
	private var showAdult = false
	private var pinnedCache: Set<String> = emptySet()
	private val localeCodes = ArrayList<String>()

	private val grid = WrapPanel().apply {
		background = T.Bg
		border = EmptyBorder(6, 14, 14, 14)
	}
	private val countLabel = JLabel().apply {
		font = T.font(12f)
		foreground = T.Dim
	}

	init {
		isOpaque = true
		background = T.Bg
		componentOrientation = java.awt.ComponentOrientation.LEFT_TO_RIGHT
		add(buildTopBar(), BorderLayout.NORTH)
		add(buildGrid(), BorderLayout.CENTER)
		add(buildFooter(), BorderLayout.SOUTH)
		refill()
	}

	// ------------------------------------------------------------ top bar + filters

	private fun buildTopBar(): JPanel {
		val bar = JPanel().apply {
			isOpaque = false
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			border = EmptyBorder(10, 10, 0, 16)
		}

		val titleRow = JPanel(BorderLayout()).apply {
			isOpaque = false
			maximumSize = Dimension(Int.MAX_VALUE, 48)
			val back = IconButton({ size -> Icons.backArrow(size, T.OnBg) }, 44) { onClose() }
			add(back, BorderLayout.WEST)
			add(JLabel("Nguồn truyện").apply {
				font = T.font(16f, bold = true)
				foreground = T.OnBg
				border = EmptyBorder(0, 4, 0, 0)
			}, BorderLayout.CENTER)
		}
		bar.add(titleRow)
		bar.add(javax.swing.Box.createVerticalStrut(10))

		val search = SearchPill("Tìm nguồn theo tên… (vd: truyen, blog, mangadex)") { q ->
			query = q
			refill()
		}
		bar.add(search)
		bar.add(javax.swing.Box.createVerticalStrut(10))

		val filterRow = JPanel(BorderLayout()).apply {
			isOpaque = false
			maximumSize = Dimension(Int.MAX_VALUE, 44)
		}
		val localeBox = JComboBox(localeChoices()).apply {
			font = T.font(13f)
			foreground = T.OnBg
			background = T.SurfaceHigh
			renderer = darkRenderer()
			maximumSize = Dimension(280, 36)
			preferredSize = Dimension(230, 36)
			addActionListener {
				val idx = selectedIndex
				localeFilter = localeCodes.getOrElse(idx) { "" }
				refill()
			}
			cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		}
		filterRow.add(localeBox, BorderLayout.WEST)
		val adult = JCheckBox("Hiện nội dung người lớn").apply {
			isOpaque = false
			foreground = T.Dim
			font = T.font(12.5f)
			isFocusPainted = false
			addActionListener { showAdult = isSelected; refill() }
		}
		val filterRight = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 4)).apply {
			isOpaque = false
			add(adult)
		}
		filterRow.add(filterRight, BorderLayout.EAST)
		bar.add(filterRow)
		bar.add(javax.swing.Box.createVerticalStrut(6))

		return bar
	}

	private fun localeChoices(): Array<String> {
		val counts = SourceCatalog.SOURCES.groupingBy { it.locale }.eachCount()
		localeCodes.clear()
		localeCodes += ""
		val rest = counts.entries
			.filter { it.key != "" }
			.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
		localeCodes += rest.map { it.key }
		return localeCodes.map { code ->
			if (code.isEmpty()) "Tất cả ngôn ngữ (${SourceCatalog.SIZE})"
			else "${localeDisplay(code)} — $code (${counts[code]})"
		}.toTypedArray()
	}

	private fun darkRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
		init {
			background = T.SurfaceHigh
			foreground = T.OnBg
			border = EmptyBorder(6, 10, 6, 10)
		}
	}

	// ------------------------------------------------------------ grid

	private fun buildGrid(): JScrollPane {
		return JScrollPane(grid).apply {
			border = null
			isOpaque = false
			viewport.isOpaque = false
			viewport.background = T.Bg
			verticalScrollBar.unitIncrement = 48
			verticalScrollBar.background = T.Bg
			horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
		}
	}

	private fun buildFooter(): JPanel =
		JPanel(BorderLayout()).apply {
			border = EmptyBorder(6, 16, 10, 16)
			isOpaque = false
			add(JLabel("Nguồn từ kotatsu-parsers (Kotatsu-Redo, GPL-3.0) · hỏng = làm mờ · online CHƯA port (M2)").apply {
				font = T.font(10.5f)
				foreground = T.Dim
			}, BorderLayout.WEST)
			add(countLabel, BorderLayout.EAST)
		}

	private fun refill() {
		grid.removeAll()
		pinnedCache = store.pinnedNames().toSet()
		val folded = fold(query)
		val matches = SourceCatalog.SOURCES.filter { src ->
			(localeFilter.isEmpty() || src.locale == localeFilter) &&
				(showAdult || src.contentType !in ADULT_TYPES) &&
				(folded.isEmpty() || fold(src.name).contains(folded) || fold(src.title).contains(folded))
		}
		val pinnedFirst = matches.sortedWith(compareByDescending<CatalogSource> { it.name in pinnedCache }.thenBy { it.locale }.thenBy { it.title.lowercase() })
		val shown = pinnedFirst.take(MAX_SHOWN)

		for (src in shown) {
			val pinned = src.name in pinnedCache
			val broken = src.isBroken || src.isDeprecated
			val sub = when {
				src.isBroken -> "bị hỏng"
				src.isDeprecated -> "cũ"
				src.locale.isEmpty() -> "đa ngôn ngữ"
				else -> src.locale
			}
			grid.add(SourceTile(
				source = src.name,
				title = src.title,
				subtitle = sub,
				onClick = {
					onInfo("“${src.title}” (${src.locale.ifEmpty { "đa ngôn ngữ" }}) chưa được port — nguồn online chưa hỗ trợ, Milestone M2.\n" +
						"Đây là danh mục chỉ để tham khảo và ghim lên tab Khám phá.")
				},
				onTogglePin = {
					store.setPinned(src.name, !pinned)
					onPinsChanged()
					refill()
				},
				pinned = pinned,
				broken = broken,
			))
		}

		countLabel.text = if (matches.size > MAX_SHOWN) {
			"Hiển thị $MAX_SHOWN / ${matches.size} — thu hẹp tìm kiếm"
		} else {
			"${matches.size} nguồn · ${pinnedCache.size} đã ghim"
		}
		grid.revalidate()
		grid.repaint()
	}

	private fun fold(s: String): String =
		Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

	private fun localeDisplay(code: String): String {
		val parts = code.split('-', limit = 2)
		val locale = if (parts.size == 2) Locale.of(parts[0], parts[1].uppercase()) else Locale.of(parts[0])
		val name = locale.getDisplayLanguage(Locale.of("vi"))
		return name.ifBlank { code }
	}
}
