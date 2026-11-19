package kotanix.ui

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.image.BufferedImage
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Material-3 style bottom navigation bar — the Android home affordance rebuilt for desktop.
 * Selected item gets the teal pill indicator; unselected items use [T.Dim].
 */
class BottomNav(
	items: List<Pair<String, (Int, Color) -> BufferedImage>>,
	private val onSelect: (Int) -> Unit,
) : JComponent() {

	private val labels = items.map { it.first }
	private val icons = items.map { it.second }

	var selectedIndex: Int = 0
		set(value) {
			field = value.coerceIn(0, labels.size - 1)
			repaint()
		}

	init {
		isOpaque = true
		background = T.Surface
		cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
		preferredSize = java.awt.Dimension(0, 70)
		minimumSize = java.awt.Dimension(0, 70)
		addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				val idx = indexAt(e.point)
				if (idx >= 0 && idx != selectedIndex) {
					selectedIndex = idx
					onSelect(idx)
				}
			}
		})
	}

	private fun indexAt(p: Point): Int {
		if (width <= 0) return -1
		val cell = width.toFloat() / labels.size
		return (p.x / cell).toInt().coerceIn(0, labels.size - 1)
	}

	override fun paintComponent(g: Graphics) {
		val g2 = g as Graphics2D
		g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
		// top hairline
		g2.color = T.Outline
		g2.fillRect(0, 0, width, 1)
		g2.color = T.Surface
		g2.fillRect(0, 1, width, height - 1)

		val cell = width.toFloat() / labels.size
		g2.font = T.font(11.5f)
		val fm = g2.fontMetrics

		for (i in labels.indices) {
			val cx = cell * i + cell / 2f
			val selected = i == selectedIndex
			val contentColor = if (selected) T.Accent else T.Dim

			// M3 indicator pill (66x32) behind the icon
			if (selected) {
				T.pill(g2, (cx - 33).toInt(), 9, 66, 32, T.NavIndicator)
			}
			val icon = icons[i](24, contentColor)
			g2.drawImage(icon, (cx - icon.width / 2f).toInt(), 13, null)

			val label = labels[i]
			val lw = fm.stringWidth(label)
			g2.color = contentColor
			g2.drawString(label, (cx - lw / 2f).toInt(), 62)
		}
	}
}
