package kotanix.ui

import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.core.MangaPage
import kotanix.core.HistoryEntry
import kotanix.data.HistoryStore
import kotanix.data.SqliteDatabase
import kotanix.local.LocalMangaParser
import kotanix.local.PageFormats
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Page reader for LOCAL manga (folder / CBZ / nested CBZ) — Kotatsu's immersive reader look:
 * black canvas, full-page image, minimal chrome. ‹ › / arrow keys turn pages, ESC exits.
 * Top bar shows back + title/chapter; bottom bar shows page position and chapter navigation;
 * a thin accent progress line tracks overall percent. History is written on every page turn
 * (created_at preserved across sessions, matching Android Kotatsu semantics).
 */
class ReaderFrame(
	private val db: SqliteDatabase,
	private val manga: Manga,
	localPath: String,
	/** When >= 0, open this chapter at page 0 instead of resuming from history. */
	private val startChapter: Int = -1,
) : JFrame(manga.title) {

	private val history = HistoryStore(db)
	private val rootFile = java.io.File(localPath)
	private val parser = LocalMangaParser.get(rootFile)
	private val chapters: List<MangaChapter> = parser?.getManga(withDetails = true)?.chapters.orEmpty()

	private var chapterIndex = 0
	private var pages: List<MangaPage> = emptyList()
	private var pageIndex = 0

	private val imageLabel = JLabel().apply {
		horizontalAlignment = SwingConstants.CENTER
		verticalAlignment = SwingConstants.CENTER
		background = Color(0x000000)
		isOpaque = true
	}

	private val pageLabel = JLabel("– / –").apply {
		foreground = T.OnBg
		font = T.font(15f, bold = true)
	}
	private val chapterLabel = JLabel("–").apply {
		foreground = T.Dim
		font = T.font(11f)
	}
	private val titleLabel = JLabel(manga.title).apply {
		foreground = T.OnBg
		font = T.font(14f, bold = true)
	}
	private val errorLabel = JLabel().apply {
		foreground = T.Error
		font = T.font(12f)
		border = EmptyBorder(4, 16, 4, 16)
	}
	
	/** Shown while the chapter contains pages the runtime cannot decode (e.g. WebP) — never hidden silently. */
	private val formatBanner = JLabel().apply {
		foreground = java.awt.Color(0xE8B54D) // warning amber on black canvas
		font = T.font(11f, bold = true)
		border = EmptyBorder(6, 16, 0, 16)
		isVisible = false
	}
	private val progress = ReadingProgressBar()

	private val prevChapter = IconButton({ size -> Icons.skipPrevious(size, T.OnBg) }, 40) { gotoChapter(chapterIndex - 1, 0) }
	private val prevPage = IconButton({ size -> Icons.chevronLeft(size, T.OnBg) }, 40) { gotoPage(pageIndex - 1) }
	private val nextPage = IconButton({ size -> Icons.chevronRight(size, T.OnBg) }, 40) { gotoPage(pageIndex + 1) }
	private val nextChapter = IconButton({ size -> Icons.skipNext(size, T.OnBg) }, 40) { gotoChapter(chapterIndex + 1, 0) }

	init {
		defaultCloseOperation = DISPOSE_ON_CLOSE
		minimumSize = Dimension(760, 560)
		layout = BorderLayout()
		background = Color(0x000000)
		contentPane.background = Color(0x000000)

		// ---- top bar (Kotatsu style: back arrow + two-line title)
		val back = IconButton({ size -> Icons.backArrow(size, T.OnBg) }, 44) { dispose() }
		val titles = JPanel().apply {
			layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
			isOpaque = false
			add(titleLabel)
			add(chapterLabel)
		}
		val top = JPanel(BorderLayout()).apply {
			background = Color(0x0B1013)
			border = EmptyBorder(8, 8, 8, 16)
			add(back, BorderLayout.WEST)
			add(titles, BorderLayout.CENTER)
			add(pageLabel, BorderLayout.EAST)
		}
		add(top, BorderLayout.NORTH)

		// ---- page canvas (tap left/right third to turn pages)
		val canvas = javax.swing.JPanel(BorderLayout()).apply {
			background = Color(0x000000)
			add(imageLabel, BorderLayout.CENTER)
			add(formatBanner, BorderLayout.NORTH)
			add(errorLabel, BorderLayout.SOUTH)
		}
		imageLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseClicked(e: java.awt.event.MouseEvent) {
				val third = imageLabel.width / 3
				when {
					e.x < third -> gotoPage(pageIndex - 1)
					e.x >= 2 * third -> gotoPage(pageIndex + 1)
					else -> Unit
				}
			}
		})
		add(canvas, BorderLayout.CENTER)

		// ---- bottom bar: chapter navigation + page indicator, plus accent progress line
		val bottom = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 18, 6)).apply {
			background = Color(0x0B1013)
			add(prevChapter)
			add(prevPage)
			add(nextPage)
			add(nextChapter)
		}
		val south = JPanel(BorderLayout()).apply {
			isOpaque = false
			add(bottom, BorderLayout.CENTER)
			add(progress, BorderLayout.SOUTH)
		}
		add(south, BorderLayout.SOUTH)

		rootPane.inputMap.put(KeyStroke.getKeyStroke("LEFT"), "prev")
		rootPane.inputMap.put(KeyStroke.getKeyStroke("RIGHT"), "next")
		rootPane.inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "close")
		rootPane.actionMap.put("prev", object : AbstractAction() {
			override fun actionPerformed(e: java.awt.event.ActionEvent?) = gotoPage(pageIndex - 1)
		})
		rootPane.actionMap.put("next", object : AbstractAction() {
			override fun actionPerformed(e: java.awt.event.ActionEvent?) = gotoPage(pageIndex + 1)
		})
		rootPane.actionMap.put("close", object : AbstractAction() {
			override fun actionPerformed(e: java.awt.event.ActionEvent?) { dispose() }
		})

		addComponentListener(object : java.awt.event.ComponentAdapter() {
			override fun componentResized(e: java.awt.event.ComponentEvent?) = renderPage()
		})

		if (parser == null || chapters.isEmpty()) {
			errorLabel.text = "Không đọc được nội dung local: $localPath"
			setSize(760, 560)
			setLocationRelativeTo(null)
			isVisible = true
		} else {
			// Explicit chapter wins; otherwise resume from history when possible
			val saved = history.get(manga.id)
			val savedChapterIdx = if (startChapter in chapters.indices) startChapter
				else saved?.let { s -> chapters.indexOfFirst { it.id == s.chapterId }.takeIf { it >= 0 } } ?: 0
			val savedPage = if (startChapter in chapters.indices) 0
				else saved?.takeIf { chapters.isNotEmpty() }?.page ?: 0

			setSize(920, 720)
			setLocationRelativeTo(null)
			isVisible = true
			gotoChapter(savedChapterIdx, savedPage)
		}
	}

	private fun gotoChapter(index: Int, page: Int) {
		if (index !in chapters.indices) return
		chapterIndex = index
		val chapter = chapters[index]
		chapterLabel.text = chapter.title ?: "Chapter ${index + 1}"
		pages = parser?.getPages(chapter).orEmpty()
		errorLabel.text = if (pages.isEmpty()) "Chapter này không có ảnh." else ""
		updateFormatBanner()
		pageIndex = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
		renderPage()
		persistHistory()
	}

	/** Persistent per-chapter warning: which/how many pages cannot be decoded by the runtime. Stays visible until the chapter changes. */
	private fun updateFormatBanner() {
		val names = pages.map { it.url.substringAfterLast('!', it.url).substringAfterLast('/').substringAfterLast('\\') }
		val unsupported = PageFormats.unsupportedSummary(names)
		if (unsupported.isEmpty()) {
			formatBanner.isVisible = false
			return
		}
		val totalUnsupported = unsupported.values.sum()
		val detail = unsupported.entries.joinToString(", ") { (fmt, count) -> "$count× $fmt" }
		formatBanner.text = "⚠ $totalUnsupported/${pages.size} trang không giải mã được: $detail — sẽ không hiển thị"
		formatBanner.toolTipText = "<html>Kotanix (JVM) chưa có bộ giải mã cho các định dạng này — ảnh vẫn được liệt kê nhưng không hiển thị.<br>" +
			"Cách xử lý: chuyển ảnh sang PNG hoặc JPG rồi đọc lại, ví dụ<br>" +
			"• ImageMagick: <code>mogrify -format png *.webp</code><br>" +
			"• XnConvert / IrfanView (miễn phí)<br>" +
			"Hỗ trợ WebP/AVIF nằm trong lộ trình (Milestone M2+); xem STATUS.md.</html>"
		formatBanner.isVisible = true
	}

	private fun gotoPage(index: Int) {
		if (pages.isEmpty()) return
		pageIndex = index.coerceIn(0, pages.size - 1)
		renderPage()
		persistHistory()
	}

	private fun persistHistory() {
		val chapter = chapters.getOrNull(chapterIndex) ?: return
		val percent = if (pages.size > 1) pageIndex.toFloat() / (pages.size - 1) else 1f
		history.upsert(
			HistoryEntry(
				mangaId = manga.id,
				createdAt = history.get(manga.id)?.createdAt ?: System.currentTimeMillis(),
				updatedAt = System.currentTimeMillis(),
				chapterId = chapter.id,
				page = pageIndex,
				scroll = 0f,
				percent = percent,
				chaptersCount = chapters.size,
			),
		)
	}

	private fun renderPage() {
		pageLabel.text = if (pages.isEmpty()) "– / –" else "${pageIndex + 1} / ${pages.size}"
		progress.percent = if (pages.isEmpty()) 0f else (if (pages.size > 1) pageIndex.toFloat() / (pages.size - 1) else 1f)
		prevPage.isEnabled = pageIndex > 0
		nextPage.isEnabled = pageIndex < pages.size - 1
		prevChapter.isEnabled = chapterIndex > 0
		nextChapter.isEnabled = chapterIndex < chapters.size - 1

		val page = pages.getOrNull(pageIndex) ?: run { imageLabel.icon = null; return }
		var decodeFailed: String? = null
		val img: BufferedImage? = run {
			val bytes = runCatching { parser?.readPage(page) }
				.getOrElse { e ->
					decodeFailed = "lỗi đọc file (${e.message ?: e.javaClass.simpleName})"
					null
				}
			bytes?.let { pageBytes ->
				runCatching { ImageIO.read(ByteArrayInputStream(pageBytes)) }.getOrElse {
					decodeFailed = "không giải mã được ảnh ${PageFormats.label(page.url, pageBytes)}"
					null
				}
			}
		}

		if (img == null) {
			errorLabel.text = "Trang ${pageIndex + 1}: ${decodeFailed ?: "không đọc được"}"
			imageLabel.icon = null
			return
		}
		errorLabel.text = ""
		val size = imageLabel.size.takeIf { it.width > 10 && it.height > 10 } ?: Dimension(800, 600)
		imageLabel.icon = ImageIcon(Placeholder.scaledTo(img, size.width, size.height))
	}
}

/** Thin accent progress line docked at the bottom of the reader. */
private class ReadingProgressBar : javax.swing.JComponent() {
	var percent: Float = 0f
		set(value) {
			field = value.coerceIn(0f, 1f)
			repaint()
		}

	init {
		isOpaque = false
		preferredSize = Dimension(0, 3)
		minimumSize = Dimension(0, 3)
	}

	override fun paintComponent(g: java.awt.Graphics) {
		g.color = T.Outline
		g.fillRect(0, 0, width, height)
		g.color = T.Accent
		g.fillRect(0, 0, (width * percent).toInt(), height)
	}
}
