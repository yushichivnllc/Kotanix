package kotanix.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.ImageIcon

/**
 * Placeholder cover: rounded dark tile with the manga's initial. Used whenever a cover
 * image is unavailable (offline, missing bytes) — never fetched from the network in the UI.
 */
object Placeholder {

	private val PALETTE = listOf(
		Color(0x2F3B4C), Color(0x3C2F4C), Color(0x2F4C3B), Color(0x4C2F2F),
		Color(0x274C56), Color(0x45322E),
	)

	fun forTitle(title: String, size: Int = 108): ImageIcon {
		val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
		val base = PALETTE[Math.floorMod(title.hashCode(), PALETTE.size)]
		val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
		val g = img.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.paint = GradientPaint(0f, 0f, base.brighter(), 0f, size.toFloat(), base.darker())
		g.fillRoundRect(0, 0, size, size, 18, 18)
		g.color = Color(0xE8E3D8)
		g.font = g.font.deriveFont(java.awt.Font.BOLD, size * 0.42f)
		val fm = g.fontMetrics
		g.drawString(letter, (size - fm.stringWidth(letter)) / 2, (size - fm.height) / 2 + fm.ascent)
		g.stroke = BasicStroke(2f)
		g.color = Color(0, 0, 0, 60)
		g.drawRoundRect(1, 1, size - 3, size - 3, 18, 18)
		g.dispose()
		return ImageIcon(img)
	}

	/** Scales [img] to fit (w, h) preserving aspect, centered on a dark backdrop. */
	fun scaledTo(img: BufferedImage, w: Int, h: Int): BufferedImage {
		val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height).coerceAtMost(1.0)
		val nw = (img.width * scale).toInt().coerceAtLeast(1)
		val nh = (img.height * scale).toInt().coerceAtLeast(1)
		val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
		val g = out.createGraphics()
		g.color = Color(0x14171C)
		g.fillRect(0, 0, w, h)
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		val x = (w - nw) / 2
		val y = (h - nh) / 2
		g.drawImage(img, x, y, nw, nh, null)
		g.dispose()
		return out
	}
}
