package kotanix.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Shared source tile — Kotatsu's source grid cell: colored rounded-square glyph with the
 * source's initial, title (max 2 lines, ellipsed) and a one-line subtitle ("3 bộ", locale,
 * "bị hỏng"…). Optional pin affordance: when [onTogglePin] is non-null a small pin glyph is
 * drawn in the top-right and tapping that corner toggles the pin instead of opening the tile.
 */
class SourceTile(
	private val source: String,
	private val title: String,
	private val subtitle: String?,
	private val onClick: () -> Unit,
	private val onTogglePin: (() -> Unit)? = null,
	private val pinned: Boolean = false,
	private val broken: Boolean = false,
) : JPanel() {

	private val pretty = title.ifBlank { source }
	private val tileColor = if (source == kotanix.core.LOCAL_SOURCE) T.Accent else sourceColor(source)

	init {
		isOpaque = false
		layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
		cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
		border = EmptyBorder(2, 4, 2, 4)
		preferredSize = Dimension(96, 122)
		minimumSize = Dimension(88, 122)

		val glyph = SourceGlyph(pretty, tileColor, source == kotanix.core.LOCAL_SOURCE, pinned, onTogglePin != null, broken)
		glyph.alignmentX = CENTER_ALIGNMENT
		add(glyph)
		add(javax.swing.Box.createVerticalStrut(6))
		add(JLabel(pretty.take(13).let { if (it.length < pretty.length) "$it…" else it }).apply {
			font = T.font(11.5f, bold = broken.not())
			foreground = if (broken) T.Dim else T.OnBg
			alignmentX = CENTER_ALIGNMENT
		})
		add(JLabel(subtitle ?: "").apply {
			font = T.font(9.5f)
			foreground = if (broken) T.Dim else T.Dim
			alignmentX = CENTER_ALIGNMENT
		})

		addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				// Pin corner hotzone (top-right 34x34 of the glyph area)
				if (onTogglePin != null && e.y <= 40 && e.x >= width - 40) onTogglePin() else onClick()
			}
		})
	}

	private fun sourceColor(source: String): Color {
		val palette = intArrayOf(0x3B6FE0, 0xE0632F, 0x2FA36B, 0x8A4FE0, 0xD0453E, 0x2E8FA8, 0xB85C0A, 0x4E5BD0)
		return Color(palette[Math.floorMod(source.hashCode(), palette.size)])
	}
}

/** The 64px rounded-square source glyph, optionally dimmed (broken) with a pin in the corner. */
private class SourceGlyph(
	private val pretty: String,
	private val color: Color,
	private val isLocal: Boolean,
	private val pinned: Boolean,
	private val showPin: Boolean,
	private val broken: Boolean,
) : javax.swing.JComponent() {

	private val letter = pretty.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

	init {
		preferredSize = Dimension(64, 64)
		minimumSize = Dimension(64, 64)
		maximumSize = Dimension(64, 64)
	}

	override fun paintComponent(g: java.awt.Graphics) {
		val g2 = T.g2(g)
		g2.composite = if (broken) java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f)
		else java.awt.AlphaComposite.SrcOver
		g2.color = color
		g2.fill(RoundRectangle2D.Float(0f, 0f, 64f, 64f, 18f, 18f))
		if (isLocal) {
			val folder = Icons.folder(32, Color(0x10231C))
			g2.drawImage(folder, (64 - folder.width) / 2, (64 - folder.height) / 2, null)
		} else {
			g2.color = Color(0xFFFFFF)
			g2.font = T.font(30f, bold = true)
			val fm = g2.fontMetrics
			g2.drawString(letter, (64 - fm.stringWidth(letter)) / 2, (64 + fm.ascent - fm.descent) / 2)
		}
		g2.composite = java.awt.AlphaComposite.SrcOver
		if (showPin) {
			val pin = if (pinned) Icons.pinFilled(18, T.Accent) else Icons.pin(18, T.Dim)
			g2.drawImage(pin, 44, -2, null)
		}
		g2.dispose()
	}
}
