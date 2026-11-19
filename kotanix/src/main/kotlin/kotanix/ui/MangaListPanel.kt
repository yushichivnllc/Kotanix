package kotanix.ui

import kotanix.core.Manga
import kotanix.core.RATING_UNKNOWN
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

/**
 * Searchable manga list with cover thumbnails (local covers only; everything else uses the
 * placeholder). Used by the Favourites, Local and History tabs. Icons are cached per manga.
 */
class MangaListPanel(
	private val onOpen: (Manga) -> Unit,
) : JPanel(BorderLayout()) {

	private val model = DefaultListModel<Manga>()
	private var allItems: List<Manga> = emptyList()

	val list = JList(model).apply {
		cellRenderer = MangaCellRenderer()
		fixedCellHeight = 116
		selectionMode = ListSelectionModel.SINGLE_SELECTION
		selectionBackground = Color(0x2F5F74)
		background = Color(0x1B1F26)
	}

	private val search = JTextField().apply {
		toolTipText = "Tìm theo tên…"
	}

	private val status = JLabel("0 mục").apply {
		border = EmptyBorder(4, 8, 4, 8)
		foreground = Color(0x9AA5B1)
	}

	init {
		background = Color(0x1B1F26)

		val top = JPanel(BorderLayout()).apply {
			isOpaque = false
			border = EmptyBorder(8, 8, 4, 8)
			add(search, BorderLayout.CENTER)
		}
		add(top, BorderLayout.NORTH)
		add(JScrollPane(list).apply { border = null }, BorderLayout.CENTER)
		add(status, BorderLayout.SOUTH)

		search.document.addDocumentListener(object : javax.swing.event.DocumentListener {
			override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
			override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
			override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
		})

		list.addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseClicked(e: java.awt.event.MouseEvent) {
				if (e.clickCount == 2) onOpen(list.selectedValue ?: return)
			}
		})
		list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
		list.actionMap.put("open", object : AbstractAction() {
			override fun actionPerformed(e: java.awt.event.ActionEvent?) { list.selectedValue?.let(onOpen) }
		})
	}

	fun setItems(items: List<Manga>) {
		allItems = items
		applyFilter()
	}

	fun selected(): Manga? = list.selectedValue

	private fun applyFilter() {
		val q = search.text.trim().lowercase()
		val filtered = if (q.isEmpty()) allItems else allItems.filter {
			(it.title + " " + it.altTitles.joinToString(" ")).lowercase().contains(q)
		}
		model.clear()
		filtered.forEach { model.addElement(it) }
		status.text = "${model.size()} mục"
	}
}

private class MangaCellRenderer : ListCellRenderer<Manga> {

	private val panel = DefaultPanel()

	override fun getListCellRendererComponent(
		list: JList<out Manga>, value: Manga, index: Int,
		isSelected: Boolean, cellHasFocus: Boolean,
	): Component {
		panel.setCell(value, isSelected)
		return panel
	}
}

/** Custom painted cell — title, source, rating, cached thumbnail. */
private class DefaultPanel : JPanel(BorderLayout()) {

	private val cover = JLabel().apply { border = EmptyBorder(8, 8, 8, 4) }
	private val title = JLabel().apply { foreground = Color(0xE8E3D8); font = font.deriveFont(java.awt.Font.BOLD, 15f) }
	private val meta = JLabel().apply { foreground = Color(0x9AA5B1); font = font.deriveFont(12f) }
	private val desc = JLabel().apply { foreground = Color(0xC9CFD6); font = font.deriveFont(12f) }

	private val iconCache = HashMap<Long, ImageIcon>()

	init {
		background = Color(0x1B1F26)
		isOpaque = true
		border = MatteBorder(0, 0, 1, 0, Color(0x2A2F38))

		val text = JPanel(GridLayout(3, 1)).apply {
			isOpaque = false
			add(title); add(meta); add(desc)
		}
		add(cover, BorderLayout.WEST)
		add(text, BorderLayout.CENTER)
		add(Box.createHorizontalStrut(12), BorderLayout.EAST)
	}

	fun setCell(manga: Manga, selected: Boolean) {
		background = if (selected) Color(0x2F5F74) else Color(0x1B1F26)
		cover.icon = iconFor(manga)

		title.text = manga.title
		meta.text = buildString {
			append(manga.source)
			if (manga.rating != RATING_UNKNOWN && manga.rating > 0f) {
				append("  •  ★ ${"%.1f".format(manga.rating * 10)}/10")
			}
			manga.state?.let { append("  •  $it") }
		}
		desc.text = manga.tags.take(3).joinToString(", ") { it.title }.ifBlank { " " }
	}

	private fun iconFor(manga: Manga): ImageIcon {
		iconCache[manga.id]?.let { return it }
		val img: BufferedImage? = CoverLoader.load(manga)
		val icon = if (img != null) {
			ImageIcon(Placeholder.scaledTo(img, 72, 96))
		} else {
			Placeholder.forTitle(manga.title, 96)
		}
		if (iconCache.size >= 128) iconCache.clear()
		iconCache[manga.id] = icon
		return icon
	}
}
