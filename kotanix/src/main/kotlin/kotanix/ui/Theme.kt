package kotanix.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/** Kotatsu-Redo dark palette, sampled from the Android build (see STATUS.md). */
object T {
	val Bg = Color(0x000000)
	val Surface = Color(0x171E22)          // search pill / bottom bar / icon wells
	val SurfaceHigh = Color(0x1E272C)      // cards, popups
	val SurfaceSelected = Color(0x28323A)  // hover / pressed
	val Outline = Color(0x2C363D)          // hairline borders
	val Accent = Color(0x4CD7B0)           // Kotatsu teal
	val NavIndicator = Color(0x2E453E)     // M3 selected-pill on the bottom bar
	val OnAccent = Color(0x04241A)         // text/icon on accent fill
	val OnBg = Color(0xE9EFEC)             // primary text
	val Dim = Color(0x8FA39C)              // secondary text
	val Error = Color(0xFF8A80)

	fun g2(g: Graphics): Graphics2D {
		val g2 = g.create() as Graphics2D
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		return g2
	}

	fun pill(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, fill: Color) {
		g.color = fill
		g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h.toFloat(), h.toFloat()))
	}

	fun pillOutline(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, outline: Color, stroke: Float = 1f) {
		g.color = outline
		g.stroke = BasicStroke(stroke)
		g.draw(RoundRectangle2D.Float(x + stroke / 2f, y + stroke / 2f, w - stroke, h - stroke, h.toFloat(), h.toFloat()))
	}

	val baseFont = javax.swing.UIManager.getFont("Label.font")
		?: java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13)

	fun font(size: Float, bold: Boolean = false): java.awt.Font =
		baseFont.deriveFont(if (bold) java.awt.Font.BOLD else java.awt.Font.PLAIN, size)
}

/** Rounded-pill search field with a magnifier glyph and ghost hint text. Height 44. */
class SearchPill(hint: String, private val onQuery: (String) -> Unit) : JPanel(java.awt.BorderLayout()) {

	private val field = object : JTextField() {
		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			if (text.isEmpty()) {
				val g2 = T.g2(g)
				g2.color = T.Dim
				g2.font = T.font(14f)
				val fm = g2.fontMetrics
				g2.drawString(hint, 0, (height - fm.height) / 2 + fm.ascent)
				g2.dispose()
			}
		}
	}.apply {
		isOpaque = false
		border = null
		font = T.font(14f)
		foreground = T.OnBg
		caretColor = T.Accent
		addCaretListener { onQuery(text) }
	}

	fun query(): String = field.text
	fun setQuery(q: String) { field.text = q }

	init {
		isOpaque = false
		border = EmptyBorder(0, 18, 0, 8)
		val icon = javax.swing.JLabel(javax.swing.ImageIcon(Icons.search(20, T.OnBg)))
		add(icon, java.awt.BorderLayout.WEST)
		add(field, java.awt.BorderLayout.CENTER)
		preferredSize = Dimension(0, 44)
	}

	override fun paintComponent(g: Graphics) {
		val g2 = T.g2(g)
		T.pill(g2, 0, 0, width, height, T.Surface)
		g2.dispose()
	}
}

/**
 * Outlined pill chip (Kotatsu filter-chip style): optional leading icon, optional toggle
 * state. Used for category picker, local-library actions and toolbars.
 */
class PillChip(
	private val text: () -> String,
	icon: BufferedImage? = null,
	private val onClick: () -> Unit,
) : JButton() {

	var toggled = false
		set(value) { field = value; repaint() }

	private val iconImage = icon
	private var hover = false

	init {
		isOpaque = false
		isContentAreaFilled = false
		isBorderPainted = false
		isFocusPainted = false
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		font = T.font(13f)
		addActionListener { onClick() }
		addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
			override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
		})
	}

	/** Force-recompute label text (for the dynamic category chip). */
	fun refreshText() = repaint()

	private fun label(): String = text()

	override fun getPreferredSize(): Dimension {
		val fm = getFontMetrics(font)
		val iconW = if (iconImage != null) 22 + 8 else 0
		return Dimension(fm.stringWidth(label()) + iconW + 32, 36)
	}

	override fun getMaximumSize(): Dimension = preferredSize

	override fun paintComponent(g: Graphics) {
		val g2 = T.g2(g)
		val w = width
		val h = height
		val base = when {
			toggled -> T.Accent.darker().darker()
			hover -> T.SurfaceSelected
			else -> T.Bg
		}
		if (toggled) T.pill(g2, 0, 0, w, h, Color(0x1D3A31)) else if (hover) T.pill(g2, 0, 0, w, h, base)
		T.pillOutline(g2, 0, 0, w, h, if (toggled) T.Accent else T.Outline)

		val fg = if (toggled) T.Accent else T.OnBg
		var x = 16
		val cy = h / 2
		iconImage?.let {
			g2.drawImage(it, x, cy - it.height / 2, null)
			x += it.width + 8
		}
		g2.color = fg
		g2.font = font
		val fm = g2.fontMetrics
		g2.drawString(label(), x, cy + fm.ascent / 2 - 2)
		g2.dispose()
	}
}

/**
 * Round icon-only button (back arrow, overflow menu, reader chevrons). Transparent with a
 * subtle hover disc — the Kotatsu toolbar look.
 */
class IconButton(
	private val icon: (Int) -> BufferedImage,
	private val size: Int = 40,
	private val onClick: () -> Unit,
) : JButton() {

	private var hover = false

	init {
		isOpaque = false
		isContentAreaFilled = false
		isBorderPainted = false
		isFocusPainted = false
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		preferredSize = Dimension(size, size)
		maximumSize = Dimension(size, size)
		minimumSize = Dimension(size, size)
		addActionListener { onClick() }
		addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
			override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
		})
	}

	override fun paintComponent(g: Graphics) {
		val g2 = T.g2(g)
		if (hover) {
			g2.color = T.SurfaceSelected
			g2.fillOval(0, 0, width, height)
		}
		val iconSize = size - 16
		val img = icon(iconSize)
		g2.drawImage(img, (width - img.width) / 2, (height - img.height) / 2, null)
		g2.dispose()
	}
}

/** Extended FAB — accent pill with icon + label (Kotatsu "Continue" button). */
class ExtendedFab(
	private val text: String,
	private val icon: BufferedImage,
	private val onClick: () -> Unit,
) : JButton() {

	private var hover = false

	init {
		isOpaque = false
		isContentAreaFilled = false
		isBorderPainted = false
		isFocusPainted = false
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		addActionListener { onClick() }
		addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
			override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
		})
	}

	override fun getPreferredSize(): Dimension {
		val fm = getFontMetrics(T.font(15f, bold = true))
		return Dimension(fm.stringWidth(text) + icon.width + 16 + 56, 56)
	}

	override fun getMaximumSize(): Dimension = preferredSize

	override fun paintComponent(g: Graphics) {
		val g2 = T.g2(g)
		val fill = if (hover) T.Accent.brighter() else T.Accent
		T.pill(g2, 0, 0, width, height, fill)
		g2.drawImage(icon, 26, (height - icon.height) / 2, null)
		g2.color = T.OnAccent
		g2.font = T.font(15f, bold = true)
		val fm = g2.fontMetrics
		g2.drawString(text, 26 + icon.width + 12, height / 2 + fm.ascent / 2 - 2)
		g2.dispose()
	}
}

/** Dark popup menu matching the theme (used for overflow + category picker). */
fun darkPopup(enabled: Boolean = true): javax.swing.JPopupMenu {
	require(enabled)
	return javax.swing.JPopupMenu().apply {
		background = T.SurfaceHigh
		border = javax.swing.border.LineBorder(T.Outline, 1, true)
	}
}

fun darkMenuItem(text: String, icon: BufferedImage? = null, action: () -> Unit): javax.swing.JMenuItem =
	javax.swing.JMenuItem(text).apply {
		icon?.let { setIcon(javax.swing.ImageIcon(it)) }
		background = T.SurfaceHigh
		foreground = T.OnBg
		isOpaque = true
		font = T.font(13f)
		border = javax.swing.border.EmptyBorder(8, 12, 8, 12)
		addActionListener { action() }
	}

/** Hairline divider in the theme color. */
fun divider(): javax.swing.JComponent = object : javax.swing.JComponent() {
	override fun paintComponent(g: Graphics) {
		g.color = T.Outline
		g.fillRect(0, height / 2, width, 1)
	}
	override fun getPreferredSize(): Dimension = Dimension(Int.MAX_VALUE, 8)
}
