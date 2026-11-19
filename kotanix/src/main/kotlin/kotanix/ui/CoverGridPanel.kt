package kotanix.ui

import kotanix.core.Manga
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** One card in the grid. [percent] in [0..1] shows the circular progress badge (null = hide). */
data class GridItem(val manga: Manga, val percent: Float?)

/**
 * FlowLayout that recomputes the wrapped height from the viewport width (Swing's stock
 * FlowLayout reports a single-row preferred size, which breaks inside a JScrollPane).
 */
class WrapLayout(align: Int = FlowLayout.LEFT, hgap: Int = 14, vgap: Int = 14) : FlowLayout(align, hgap, vgap) {

	override fun preferredLayoutSize(target: Container): Dimension = wrappedSize(target)

	override fun minimumLayoutSize(target: Container): Dimension = wrappedSize(target)

	private fun wrappedSize(target: Container): Dimension {
		val insets = target.insets
		val hgap = hgap + 0 // FlowLayout's own hgap; mirrored here
		val vgap = this.vgap
		var available = (if (target.parent is javax.swing.JViewport) target.parent.width else target.width) - insets.left - insets.right
		if (available <= 0) available = 720
		var rowWidth = 0
		var rowHeight = 0
		var totalHeight = insets.top
		var maxRowWidth = 0
		for (comp in target.components) {
			if (!comp.isVisible) continue
			val d = comp.preferredSize
			val advance = if (rowWidth == 0) d.width else rowWidth + hgap + d.width
			if (advance > available && rowWidth > 0) {
				totalHeight += rowHeight + vgap
				maxRowWidth = maxOf(maxRowWidth, rowWidth)
				rowWidth = d.width
				rowHeight = d.height
			} else {
				rowWidth = advance
				rowHeight = maxOf(rowHeight, d.height)
			}
		}
		totalHeight += rowHeight + insets.bottom
		maxRowWidth = maxOf(maxRowWidth, rowWidth)
		return Dimension(insets.left + maxRowWidth + insets.right, totalHeight)
	}
}

/** Fixed-width content panel whose height follows the wrapped layout. */
private class WrapPanel : JPanel(WrapLayout(FlowLayout.LEFT, 14, 14)), Scrollable {
	override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
	override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 40
	override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = visibleRect.height.coerceAtLeast(200)
	override fun getScrollableTracksViewportWidth(): Boolean = true
	override fun getScrollableTracksViewportHeight(): Boolean = false
}

private const val CARD_W = 132
private const val COVER_H = 198
private const val RADIUS = 16f

/** Custom-painted cover card: center-cropped rounded cover, progress badge, 2-line title. */
class CoverCard(
	val manga: Manga,
	percent: Float?,
) : JComponent() {

	var percent: Float? = percent
		set(value) { field = value; repaint() }

	var selected: Boolean = false
		set(value) { field = value; repaint() }

	var onSelect: ((CoverCard) -> Unit)? = null
	var onOpen: ((CoverCard) -> Unit)? = null

	private var hover = false
	private val coverCache = HashMap<Long, BufferedImage?>(8)

	init {
		isOpaque = false
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		preferredSize = Dimension(CARD_W, COVER_H + 8 + 36)
		addMouseListener(object : MouseAdapter() {
			override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
			override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
			override fun mouseClicked(e: MouseEvent) {
				onSelect?.invoke(this@CoverCard)
				if (e.clickCount == 2) onOpen?.invoke(this@CoverCard)
			}
		})
	}

	override fun paintComponent(g: Graphics) {
		val g2 = T.g2(g)

		// cover
		val coverBounds = RoundRectangle2D.Float(0f, 0f, CARD_W.toFloat(), COVER_H.toFloat(), RADIUS, RADIUS)
		g2.clip(coverBounds)
		val cover = cover()
		if (cover != null) {
			drawCenterCrop(g2, cover, 0, 0, CARD_W, COVER_H)
		} else {
			paintPlaceholderCover(g2)
		}
		g2.clip = null

		// subtle outline (or accent ring when selected)
		if (selected) {
			g2.color = T.Accent
			g2.stroke = BasicStroke(3f)
			g2.draw(RoundRectangle2D.Float(1.5f, 1.5f, CARD_W - 3f, COVER_H - 3f, RADIUS, RADIUS))
		} else {
			g2.color = T.Outline
			g2.stroke = BasicStroke(1f)
			g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, CARD_W - 1f, COVER_H - 1f, RADIUS, RADIUS))
		}
		if (hover && !selected) {
			g2.color = Color(255, 255, 255, 26)
			g2.fill(coverBounds)
		}

		// circular progress badge
		parentPercent()?.let { p -> paintBadge(g2, p) }

		// title, max two lines, dimmed second line
		g2.font = T.font(12f)
		g2.color = if (selected) T.Accent else T.OnBg
		val lines = wrapTitle(g2, manga.title, CARD_W)
		var ty = COVER_H + 6 + g2.fontMetrics.ascent - 2
		for (line in lines) {
			g2.drawString(line, 0, ty)
			ty += g2.fontMetrics.height - 1
		}
		g2.dispose()
	}

	private fun parentPercent(): Float? = percent

	private fun cover(): BufferedImage? {
		if (coverCache.containsKey(manga.id)) return coverCache[manga.id]
		val img = CoverLoader.load(manga)
		coverCache.clear()
		coverCache[manga.id] = img
		return img
	}

	private fun paintPlaceholderCover(g2: Graphics2D) {
		val palette = intArrayOf(0x2F3B4C, 0x3C2F4C, 0x2F4C3B, 0x4C2F2F, 0x274C56, 0x45322E)
		val base = Color(palette[Math.floorMod(manga.title.hashCode(), palette.size)])
		g2.paint = java.awt.GradientPaint(0f, 0f, base.brighter(), 0f, COVER_H.toFloat(), base.darker().darker())
		g2.fillRect(0, 0, CARD_W, COVER_H)
		val letter = manga.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
		g2.color = Color(0xE8E3D8)
		g2.font = T.font(64f, bold = true)
		val fm = g2.fontMetrics
		g2.drawString(letter, (CARD_W - fm.stringWidth(letter)) / 2, (COVER_H + fm.ascent) / 2 - 6)
	}

	private fun drawCenterCrop(g2: Graphics2D, img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
		val scale = maxOf(w.toDouble() / img.width, h.toDouble() / img.height)
		val srcW = (w / scale).toInt().coerceIn(1, img.width)
		val srcH = (h / scale).toInt().coerceIn(1, img.height)
		val srcX = (img.width - srcW) / 2
		val srcY = (img.height - srcH) / 2
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		g2.drawImage(img, x, y, x + w, y + h, srcX, srcY, srcX + srcW, srcY + srcH, null)
	}

	private fun paintBadge(g2: Graphics2D, p: Float) {
		val d = 32
		val bx = CARD_W - d - 6
		val by = COVER_H - d - 6
		// dark ring + accent disc, like Kotatsu's progress chip
		g2.color = Color(0, 0, 0, 165)
		g2.fillOval(bx - 2, by - 2, d + 4, d + 4)
		g2.color = T.Accent
		g2.fillOval(bx, by, d, d)
		if (p >= 0.995f) {
			val img = Icons.check(18, T.OnAccent)
			g2.drawImage(img, bx + (d - img.width) / 2, by + (d - img.height) / 2, null)
		} else {
			g2.color = T.OnAccent
			g2.font = T.font(10.5f, bold = true)
			val label = "${(p * 100).toInt()}%"
			val fm = g2.fontMetrics
			g2.drawString(label, bx + (d - fm.stringWidth(label)) / 2, by + (d + fm.ascent - fm.descent) / 2)
		}
	}

	private fun wrapTitle(g2: Graphics2D, text: String, maxWidth: Int): List<String> {
		val fm = g2.fontMetrics
		val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
		if (words.isEmpty()) return listOf("?")
		val lines = mutableListOf<String>()
		var current = StringBuilder()
		for (word in words) {
			val candidate = if (current.isEmpty()) word else "$current $word"
			if (fm.stringWidth(candidate) <= maxWidth) {
				current = StringBuilder(candidate)
			} else {
				if (current.isNotEmpty()) lines += current.toString()
				current = StringBuilder(word)
				if (lines.size == 2) break
			}
		}
		if (lines.size < 2 && current.isNotEmpty()) lines += current.toString()
		if (lines.size > 2) lines.subList(2, lines.size).clear()
		// if anything was dropped, append an ellipsis to line 2
		val consumed = lines.joinToString(" ").split(Regex("\\s+")).size
		if (consumed < words.size && lines.isNotEmpty()) {
			var last = lines.last()
			while (fm.stringWidth("$last…") > maxWidth && last.length > 1) last = last.dropLast(1)
			lines[lines.size - 1] = "$last…"
		}
		// single-word overflow: clip it
		for (i in lines.indices) {
			if (fm.stringWidth(lines[i]) > maxWidth) {
				var s = lines[i]
				while (fm.stringWidth("$s…") > maxWidth && s.length > 1) s = s.dropLast(1)
				lines[i] = "$s…"
			}
		}
		return lines.take(2)
	}
}

/**
 * Scrollable cover grid used by all three tabs — Kotatsu's home grid, not a JList of rows.
 * Handles filtering, single selection and empty-state hint text.
 */
class CoverGridPanel(
	private val emptyHint: String,
	private val onOpen: (Manga) -> Unit,
) : JPanel(BorderLayout()) {

	private var allItems: List<GridItem> = emptyList()
	private var query: String = ""

	private val wrap = WrapPanel().apply {
		background = T.Bg
		border = javax.swing.border.EmptyBorder(10, 10, 10, 10)
	}

	private val scroll = JScrollPane(wrap).apply {
		border = null
		background = T.Bg
		viewport.background = T.Bg
		verticalScrollBar.unitIncrement = 48
		verticalScrollBar.background = T.Bg
	}

	private val empty = JLabel(emptyHint, SwingConstants.CENTER).apply {
		font = T.font(14f)
		foreground = T.Dim
		border = javax.swing.border.EmptyBorder(60, 40, 60, 40)
	}

	private val cards = mutableListOf<CoverCard>()
	private var selectedCard: CoverCard? = null

	fun setItems(items: List<GridItem>) {
		allItems = items
		applyFilter()
	}

	fun setFilter(q: String) {
		query = q.trim().lowercase()
		applyFilter()
	}

	fun selected(): Manga? = selectedCard?.manga

	fun selectedPercent(): Float? = selectedCard?.percent

	private fun applyFilter() {
		val filtered = if (query.isEmpty()) allItems else allItems.filter {
			(it.manga.title + " " + it.manga.altTitles.joinToString(" ")).lowercase().contains(query)
		}
		wrap.removeAll()
		cards.clear()
		selectedCard = null
		for (item in filtered) {
			val card = CoverCard(item.manga, item.percent).apply {
				onSelect = { select(it) }
				onOpen = { onOpen(it.manga) }
			}
			cards += card
			wrap.add(card)
		}
		val showEmpty = filtered.isEmpty()
		removeAll()
		if (showEmpty) add(empty, BorderLayout.CENTER) else add(scroll, BorderLayout.CENTER)
		revalidate()
		repaint()
	}

	private fun select(card: CoverCard) {
		if (selectedCard === card) return
		selectedCard?.selected = false
		selectedCard = card
		card.selected = true
	}
}
