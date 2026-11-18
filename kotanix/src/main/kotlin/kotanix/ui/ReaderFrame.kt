package kotanix.ui

import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.core.MangaPage
import kotanix.core.HistoryEntry
import kotanix.data.HistoryStore
import kotanix.data.SqliteDatabase
import kotanix.local.LocalMangaParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Page reader for LOCAL manga (folder / CBZ / nested CBZ). Rendering is plain Swing —
 * each page is decoded on demand and scaled to fit the window. History is written on every
 * page turn (created_at preserved across sessions, matching Android Kotatsu semantics).
 */
class ReaderFrame(
	private val db: SqliteDatabase,
	private val manga: Manga,
	localPath: String,
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
	}

	private val pageLabel = JLabel("– / –").apply { foreground = Color(0xE8E3D8) }
	private val chapterLabel = JLabel("–").apply { foreground = Color(0x9AA5B1); font = font.deriveFont(11f) }
	private val errorLabel = JLabel().apply { foreground = Color(0xFF9E9E); border = EmptyBorder(8, 12, 8, 12) }

	private val prevChapter = JButton("⟨ Ch").apply { toolTipText = "Chapter trước" }
	private val prevPage = JButton("⟨ Trang").apply { toolTipText = "Trang trước (←)" }
	private val nextPage = JButton("Trang ⟩").apply { toolTipText = "Trang sau (→)" }
	private val nextChapter = JButton("Ch ⟩").apply { toolTipText = "Chapter tiếp theo" }

	init {
		defaultCloseOperation = DISPOSE_ON_CLOSE
		minimumSize = Dimension(760, 560)
		layout = BorderLayout()
		background = Color(0x14171C)

		val top = JPanel(BorderLayout()).apply {
			background = Color(0x1B1F26)
			border = EmptyBorder(6, 8, 6, 8)
			add(chapterLabel, BorderLayout.WEST)
			add(pageLabel, BorderLayout.EAST)
		}
		add(top, BorderLayout.NORTH)

		val center = JPanel(BorderLayout()).apply {
			background = Color(0x14171C)
			add(imageLabel, BorderLayout.CENTER)
			add(errorLabel, BorderLayout.SOUTH)
		}
		add(center, BorderLayout.CENTER)

		val bottom = JPanel().apply {
			background = Color(0x1B1F26)
			border = EmptyBorder(6, 8, 6, 8)
			add(prevChapter); add(prevPage); add(nextPage); add(nextChapter)
		}
		add(bottom, BorderLayout.SOUTH)

		prevChapter.addActionListener { gotoChapter(chapterIndex - 1, page = 0) }
		nextChapter.addActionListener { gotoChapter(chapterIndex + 1, page = 0) }
		prevPage.addActionListener { gotoPage(pageIndex - 1) }
		nextPage.addActionListener { gotoPage(pageIndex + 1) }

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

			// Resume from history when possible
			val saved = history.get(manga.id)
			val savedChapterIdx = saved?.let { s -> chapters.indexOfFirst { it.id == s.chapterId }.takeIf { it >= 0 } } ?: 0
			val savedPage = saved?.takeIf { chapters.isNotEmpty() }?.page ?: 0

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
		pageIndex = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
		renderPage()
		persistHistory()
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
				mangaId = chaptersMangaId(),
				createdAt = history.get(chaptersMangaId())?.createdAt ?: System.currentTimeMillis(),
				updatedAt = System.currentTimeMillis(),
				chapterId = chapter.id,
				page = pageIndex,
				scroll = 0f,
				percent = percent,
				chaptersCount = chapters.size,
			),
		)
	}

	private fun chaptersMangaId(): Long = manga.id

	private fun renderPage() {
		pageLabel.text = if (pages.isEmpty()) "– / –" else "${pageIndex + 1} / ${pages.size}"
		prevPage.isEnabled = pageIndex > 0
		nextPage.isEnabled = pageIndex < pages.size - 1
		prevChapter.isEnabled = chapterIndex > 0
		nextChapter.isEnabled = chapterIndex < chapters.size - 1

		val page = pages.getOrNull(pageIndex) ?: run { imageLabel.icon = null; return }
		val img: BufferedImage? = runCatching {
			val bytes = parser?.readPage(page) ?: return
			ImageIO.read(ByteArrayInputStream(bytes))
		}.getOrNull()

		if (img == null) {
			errorLabel.text = "Không đọc được trang ${pageIndex + 1}"
			imageLabel.icon = null
			return
		}
		errorLabel.text = ""
		val size = imageLabel.size.takeIf { it.width > 10 && it.height > 10 } ?: Dimension(800, 600)
		imageLabel.icon = ImageIcon(Placeholder.scaledTo(img, size.width, size.height))
	}
}
