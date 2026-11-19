package kotanix.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * Programmatic Material-ish icons, drawn with anti-aliased strokes/fills. Kotatsu ships its
 * own vector drawables; we deliberately draw ours instead of copying GPL assets wholesale —
 * these shapes are generic (circles, arcs, chevrons) and Apache/MIT-safe to produce here.
 * Each function returns an ARGB image of [size] px with [color] strokes/fills.
 */
object Icons {

	private fun canvas(size: Int, block: (Graphics2D) -> Unit): BufferedImage {
		val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
		try {
			block(g)
		} finally {
			g.dispose()
		}
		return img
	}

	private fun Graphics2D.stroke(color: Color, width: Float) {
		this.color = color
		this.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
	}

	fun search(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawOval((4 * s).toInt(), (4 * s).toInt(), (11 * s).toInt(), (11 * s).toInt())
		g.drawLine((13.6f * s).toInt(), (13.6f * s).toInt(), (20 * s).toInt(), (20 * s).toInt())
	}

	fun moreVert(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		g.color = color
		val s = size / 24f
		for (cy in floatArrayOf(5.5f, 12f, 18.5f)) {
			val r = 1.65f * s
			g.fillOval((12 * s - r).toInt(), (cy * s - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
		}
	}

	fun backArrow(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 2f * s)
		g.drawLine((20 * s).toInt(), (12 * s).toInt(), (4 * s).toInt(), (12 * s).toInt())
		g.drawPolyline(
			intArrayOf((11 * s).toInt(), (4 * s).toInt(), (11 * s).toInt()),
			intArrayOf((5 * s).toInt(), (12 * s).toInt(), (19 * s).toInt()),
			3,
		)
	}

	fun chevronLeft(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 2.2f * s)
		g.drawPolyline(
			intArrayOf((15 * s).toInt(), (9 * s).toInt(), (15 * s).toInt()),
			intArrayOf((5 * s).toInt(), (12 * s).toInt(), (19 * s).toInt()),
			3,
		)
	}

	fun chevronRight(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 2.2f * s)
		g.drawPolyline(
			intArrayOf((9 * s).toInt(), (15 * s).toInt(), (9 * s).toInt()),
			intArrayOf((5 * s).toInt(), (12 * s).toInt(), (19 * s).toInt()),
			3,
		)
	}

	fun skipPrevious(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 2f * s)
		g.drawLine((6 * s).toInt(), (6 * s).toInt(), (6 * s).toInt(), (18 * s).toInt())
		g.color = color
		g.fillPolygon(
			intArrayOf((18 * s).toInt(), (10 * s).toInt(), (18 * s).toInt()),
			intArrayOf((6.5f * s).toInt(), (12 * s).toInt(), (17.5f * s).toInt()),
			3,
		)
	}

	fun skipNext(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 2f * s)
		g.drawLine((18 * s).toInt(), (6 * s).toInt(), (18 * s).toInt(), (18 * s).toInt())
		g.color = color
		g.fillPolygon(
			intArrayOf((6 * s).toInt(), (14 * s).toInt(), (6 * s).toInt()),
			intArrayOf((6.5f * s).toInt(), (12 * s).toInt(), (17.5f * s).toInt()),
			3,
		)
	}

	/** Heart outline (favourites); pass [filled] = true for the toggled state. */
	fun heart(size: Int, color: Color, filled: Boolean): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		val p = Path2D.Float().apply {
			moveTo(12f * s, 20f * s)
			curveTo(6.5f * s, 15.4f * s, 3.5f * s, 12.2f * s, 3.5f * s, 8.4f * s)
			curveTo(3.5f * s, 5.5f * s, 5.8f * s, 3.5f * s, 8.3f * s, 3.5f * s)
			curveTo(9.9f * s, 3.5f * s, 11.2f * s, 4.4f * s, 12f * s, 5.8f * s)
			curveTo(12.8f * s, 4.4f * s, 14.1f * s, 3.5f * s, 15.7f * s, 3.5f * s)
			curveTo(18.2f * s, 3.5f * s, 20.5f * s, 5.5f * s, 20.5f * s, 8.4f * s)
			curveTo(20.5f * s, 12.2f * s, 17.5f * s, 15.4f * s, 12f * s, 20f * s)
			closePath()
		}
		if (filled) {
			g.color = color
			g.fill(p)
		} else {
			g.stroke(color, 1.9f * s)
			g.draw(p)
		}
	}

	fun folder(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		val body = RoundRectangle2D.Float(3.5f * s, 7f * s, 17f * s, 13f * s, 2.5f * s, 2.5f * s)
		g.draw(body)
		// tab
		g.drawLine((3.5f * s).toInt(), (10 * s).toInt(), (9.5f * s).toInt(), (10 * s).toInt())
		g.drawArc((4 * s).toInt(), (4.6f * s).toInt(), (6 * s).toInt(), (5f * s).toInt(), 0, 90)
	}

	fun clock(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawOval((4 * s).toInt(), (4 * s).toInt(), (16 * s).toInt(), (16 * s).toInt())
		g.drawLine((12 * s).toInt(), (12 * s).toInt(), (12 * s).toInt(), (7.5f * s).toInt())
		g.drawLine((12 * s).toInt(), (12 * s).toInt(), (15.2f * s).toInt(), (13.8f * s).toInt())
	}

	fun check(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		g.stroke(color, size / 9f)
		g.drawPolyline(
			intArrayOf((size * 0.18f).toInt(), (size * 0.42f).toInt(), (size * 0.84f).toInt()),
			intArrayOf((size * 0.52f).toInt(), (size * 0.74f).toInt(), (size * 0.28f).toInt()),
			3,
		)
	}

	/** Open book — the "Đọc tiếp" FAB icon. */
	fun bookOpen(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		// left page
		g.drawArc((-2 * s).toInt(), (6 * s).toInt(), (13 * s).toInt(), (12 * s).toInt(), -90, 180)
		// right page
		g.drawArc((13 * s).toInt(), (6 * s).toInt(), (13 * s).toInt(), (12 * s).toInt(), -90, 180)
		g.drawLine((12 * s).toInt(), (6.2f * s).toInt(), (12 * s).toInt(), (18.2f * s).toInt())
	}

	fun filePlus(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.8f * s)
		g.drawLine((12 * s).toInt(), (5 * s).toInt(), (12 * s).toInt(), (15 * s).toInt())
		g.drawLine((7.5f * s).toInt(), (10 * s).toInt(), (16.5f * s).toInt(), (10 * s).toInt())
		g.drawLine((4 * s).toInt(), (20 * s).toInt(), (20 * s).toInt(), (20 * s).toInt())
	}

	fun download(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawLine((12 * s).toInt(), (4 * s).toInt(), (12 * s).toInt(), (14 * s).toInt())
		g.drawPolyline(
			intArrayOf((7 * s).toInt(), (12 * s).toInt(), (17 * s).toInt()),
			intArrayOf((10 * s).toInt(), (15 * s).toInt(), (10 * s).toInt()),
			3,
		)
		g.drawLine((5 * s).toInt(), (19.5f * s).toInt(), (19 * s).toInt(), (19.5f * s).toInt())
	}

	fun trash(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawLine((4 * s).toInt(), (7 * s).toInt(), (20 * s).toInt(), (7 * s).toInt())
		g.drawLine((9 * s).toInt(), (7 * s).toInt(), (9 * s).toInt(), (4.5f * s).toInt())
		g.drawLine((15 * s).toInt(), (7 * s).toInt(), (15 * s).toInt(), (4.5f * s).toInt())
		g.drawLine((9 * s).toInt(), (4.5f * s).toInt(), (15 * s).toInt(), (4.5f * s).toInt())
		g.drawRoundRect((6 * s).toInt(), (7 * s).toInt(), (12 * s).toInt(), (13 * s).toInt(), (2 * s).toInt(), (2 * s).toInt())
	}

	fun info(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawOval((4 * s).toInt(), (4 * s).toInt(), (16 * s).toInt(), (16 * s).toInt())
		g.color = color
		g.fillOval((11 * s).toInt(), (7.6f * s).toInt(), (2 * s).toInt(), (2 * s).toInt())
		g.stroke(color, 2.1f * s)
		g.drawLine((12 * s).toInt(), (11 * s).toInt(), (12 * s).toInt(), (16.5f * s).toInt())
	}

	fun imports(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		// tray + down arrow
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawLine((4 * s).toInt(), (15 * s).toInt(), (4 * s).toInt(), (19 * s).toInt())
		g.drawLine((20 * s).toInt(), (15 * s).toInt(), (20 * s).toInt(), (19 * s).toInt())
		g.drawLine((4 * s).toInt(), (19 * s).toInt(), (20 * s).toInt(), (19 * s).toInt())
		g.drawLine((12 * s).toInt(), (4 * s).toInt(), (12 * s).toInt(), (13.5f * s).toInt())
		g.drawPolyline(
			intArrayOf((7.5f * s).toInt(), (12 * s).toInt(), (16.5f * s).toInt()),
			intArrayOf((9 * s).toInt(), (13.5f * s).toInt(), (9 * s).toInt()),
			3,
		)
	}

	fun exports(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawLine((4 * s).toInt(), (15 * s).toInt(), (4 * s).toInt(), (19 * s).toInt())
		g.drawLine((20 * s).toInt(), (15 * s).toInt(), (20 * s).toInt(), (19 * s).toInt())
		g.drawLine((4 * s).toInt(), (19 * s).toInt(), (20 * s).toInt(), (19 * s).toInt())
		g.drawLine((12 * s).toInt(), (14 * s).toInt(), (12 * s).toInt(), (4.5f * s).toInt())
		g.drawPolyline(
			intArrayOf((7.5f * s).toInt(), (12 * s).toInt(), (16.5f * s).toInt()),
			intArrayOf((8.5f * s).toInt(), (4f * s).toInt(), (8.5f * s).toInt()),
			3,
		)
	}

	/** Compass — the Explore tab icon. */
	fun compass(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.drawOval((4 * s).toInt(), (4 * s).toInt(), (16 * s).toInt(), (16 * s).toInt())
		g.color = color
		val needle = Path2D.Float().apply {
			moveTo((14.8f * s), (9.2f * s))
			lineTo((12.9f * s), (12.9f * s))
			lineTo((9.2f * s), (14.8f * s))
			lineTo((11.1f * s), (11.1f * s))
			closePath()
		}
		g.fill(needle)
	}

	/** Dice — the "Random" action. */
	fun dice(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		g.draw(RoundRectangle2D.Float(4 * s, 4 * s, 16 * s, 16 * s, 3.5f * s, 3.5f * s))
		g.color = color
		val dot = 1.35f * s
		for ((dx, dy) in listOf(6.8f to 6.8f, 17.2f to 6.8f, 12f to 12f, 6.8f to 17.2f, 17.2f to 17.2f)) {
			g.fillOval((dx * s - dot).toInt(), (dy * s - dot).toInt(), (dot * 2).toInt(), (dot * 2).toInt())
		}
	}

	/** Bookmark — the bookmarks action. */
	fun bookmark(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		val p = Path2D.Float().apply {
			moveTo(7f * s, 4f * s)
			lineTo(17f * s, 4f * s)
			lineTo(17f * s, 20f * s)
			lineTo(12f * s, 16.2f * s)
			lineTo(7f * s, 20f * s)
			closePath()
		}
		g.draw(p)
	}

	/** Push-pin — marks a pinned source, like Kotatsu's source grid. */
	fun pin(size: Int, color: Color): BufferedImage = canvas(size) { g ->
		val s = size / 24f
		g.stroke(color, 1.9f * s)
		val p = Path2D.Float().apply {
			moveTo(9f * s, 4f * s)
			lineTo(15f * s, 4f * s)
			lineTo(14.2f * s, 10f * s)
			lineTo(17.5f * s, 13f * s)
			lineTo(17.5f * s, 15.2f * s)
			lineTo(6.5f * s, 15.2f * s)
			lineTo(6.5f * s, 13f * s)
			lineTo(9.8f * s, 10f * s)
			closePath()
		}
		g.draw(p)
		g.drawLine((12 * s).toInt(), (15.2f * s).toInt(), (12 * s).toInt(), (20.5f * s).toInt())
	}
}
