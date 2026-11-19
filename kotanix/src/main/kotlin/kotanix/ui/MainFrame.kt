package kotanix.ui

import kotanix.backup.Backup
import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.core.MangaChapter
import kotanix.data.*
import kotanix.local.LocalMangaParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.io.FileInputStream
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Three tabs — Favourites (from the restored backup or later toggles), Local (folder/CBZ
 * library on disk) and History (recently read). Import/export of `.bak` lives in the menu.
 */
class KotanixApp {

	private lateinit var main: MainFrame

	fun start() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		} catch (_: Exception) {
			// fall back to the default look and feel
		}
		val db = AppPaths.openDatabase()
		main = MainFrame(db)
		main.isVisible = true
	}
}

class MainFrame(
	private val db: SqliteDatabase,
) : javax.swing.JFrame("Kotanix") {

	private val mangaStore = MangaStore(db)
	private val favouritesStore = FavouritesStore(db)
	private val historyStore = HistoryStore(db)

	private val favouritesList = MangaListPanel(::openManga)
	private val localList = MangaListPanel(::openManga)
	private val historyList = MangaListPanel(::openManga)

	private val categoryBox = javax.swing.JComboBox<CategoryItem>()

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		minimumSize = Dimension(880, 560)
		setSize(1120, 720)
		setLocationRelativeTo(null)

		iconImage = runCatching {
			javaClass.getResource("/icons/kotanix-64.png")?.let { javax.imageio.ImageIO.read(it) }
		}.getOrNull() ?: iconImageFromPlaceholder()

		jMenuBar = buildMenuBar()

		val tabs = javax.swing.JTabbedPane()
		tabs.addTab("Yêu thích", buildFavouritesTab())
		tabs.addTab("Local", buildLocalTab())
		tabs.addTab("Lịch sử", buildHistoryTab())
		tabs.border = javax.swing.border.EmptyBorder(0, 0, 0, 0)
		tabs.background = Color(0x1B1F26)
		contentPane = tabs
		tabs.addChangeListener { refreshAll() }

		refreshAll()
	}

	// ------------------------------------------------------------- menu

	private fun buildMenuBar(): javax.swing.JMenuBar {
		val bar = javax.swing.JMenuBar()

		val file = javax.swing.JMenu("Tệp")
		file.add(javax.swing.JMenuItem("Nhập backup (.bak)…").apply {
			addActionListener { importBackup() }
		})
		file.add(javax.swing.JMenuItem("Xuất backup (.bak)…").apply {
			addActionListener { exportBackup() }
		})
		file.addSeparator()
		file.add(javax.swing.JMenuItem("Thoát").apply {
			addActionListener { dispose() }
		})
		bar.add(file)

		val library = javax.swing.JMenu("Thư viện")
		library.add(javax.swing.JMenuItem("Thêm thư mục…").apply {
			addActionListener { importLocalManga(folder = true) }
		})
		library.add(javax.swing.JMenuItem("Thêm CBZ/CBR… (cbz/zip)").apply {
			addActionListener { importLocalManga(folder = false) }
		})
		bar.add(library)

		val help = javax.swing.JMenu("Trợ giúp")
		help.add(javax.swing.JMenuItem("Giới thiệu").apply {
			addActionListener { showAbout() }
		})
		bar.add(help)
		return bar
	}

	private fun showAbout() {
		JOptionPane.showMessageDialog(
			this,
			"""Kotanix 0.1.0 — bản port Linux của Kotatsu-Redo (Android), dựa trên mã nguồn
				Kotatsu-Redo 9.8.7 (GPL-3.0). Backup .bak tương thích định dạng Android (chưa có
				xác nhận trên thiết bị thật cho chiều Kotanix → Android).

				Đọc được manga local: thư mục / CBZ / CBZ lồng nhau.
				Mã nguồn: giữ nguyên attribution theo UPSTREAM.lock.""".trimIndent(),
			"Giới thiệu Kotanix",
			JOptionPane.INFORMATION_MESSAGE,
		)
	}

	// ------------------------------------------------------------- tabs

	private fun buildFavouritesTab(): javax.swing.JPanel {
		val panel = javax.swing.JPanel(BorderLayout())
		val toolbar = javax.swing.JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
			background = Color(0x14171C)
		}
		toolbar.add(javax.swing.JLabel("Danh mục:").apply { foreground = Color(0xC9CFD6) })
		categoryBox.apply {
			addActionListener { refreshFavourites() }
			background = Color(0x23272E); foreground = Color(0xE8E3D8)
			maximumSize = Dimension(320, 28)
		}
		toolbar.add(categoryBox)
		toolbar.add(actionButton("Toggle yêu thích") { selectedFavourite()?.let {
			favouritesStore.toggleFavourite(it.id, (categoryBox.selectedItem as? CategoryItem)?.id)
			refreshFavourites()
		} })
		panel.add(toolbar, BorderLayout.NORTH)
		panel.add(favouritesList, BorderLayout.CENTER)
		return panel
	}

	private fun buildLocalTab(): javax.swing.JPanel {
		val panel = javax.swing.JPanel(BorderLayout())
		val toolbar = javax.swing.JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
			background = Color(0x14171C)
		}
		toolbar.add(actionButton("Thêm thư mục…") { importLocalManga(folder = true) })
		toolbar.add(actionButton("Thêm CBZ…") { importLocalManga(folder = false) })
		toolbar.add(actionButton("Gỡ khỏi local") { removeSelectedLocal() })
		panel.add(toolbar, BorderLayout.NORTH)
		panel.add(localList, BorderLayout.CENTER)
		return panel
	}

	private fun buildHistoryTab(): javax.swing.JPanel {
		val panel = javax.swing.JPanel(BorderLayout())
		val toolbar = javax.swing.JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
			background = Color(0x14171C)
		}
		toolbar.add(javax.swing.JLabel("Gần đây").apply { foreground = Color(0xC9CFD6) })
		toolbar.add(actionButton("Xoá hết lịch sử…") { clearHistory() })
		panel.add(toolbar, BorderLayout.NORTH)
		panel.add(historyList, BorderLayout.CENTER)
		return panel
	}

	private fun actionButton(text: String, onClick: () -> Unit) =
		javax.swing.JButton(text).apply {
			foreground = Color(0xE8E3D8)
			background = Color(0x2A2F38)
			isFocusPainted = false
			addActionListener { onClick() }
		}

	private inner class CategoryItem(val id: Long, label: String) {
		private val label = label
		override fun toString(): String = label
	}

	private fun selectedFavourite(): Manga? = favouritesList.selected()
	private fun selectedLocal(): Manga? = localList.selected()

	// ------------------------------------------------------------- refresh

	private fun refreshAll() {
		refreshCategories()
		refreshFavourites()
		refreshLocal()
		refreshHistory()
	}

	private fun refreshCategories() {
		val previous = (categoryBox.selectedItem as? CategoryItem)?.id
		categoryBox.removeAllItems()
		for (c in favouritesStore.listCategories()) {
			categoryBox.addItem(CategoryItem(c.categoryId.toLong(), c.title))
		}
		if (categoryBox.itemCount == 0) categoryBox.addItem(CategoryItem(1, "Thư viện"))
		if (previous != null) {
			for (i in 0 until categoryBox.itemCount) {
				if (categoryBox.getItemAt(i).id == previous) categoryBox.selectedIndex = i
			}
		}
	}

	private fun refreshFavourites() {
		val cat = (categoryBox.selectedItem as? CategoryItem)?.id ?: 1L
		favouritesList.setItems(favouritesStore.getFavouriteManga(cat))
	}

	private fun refreshLocal() {
		val pairs = mangaStore.findLocalManga()
		val items = pairs.mapNotNull { (id, _) -> mangaStore.findById(id) }
		localList.setItems(items)
	}

	private fun refreshHistory() {
		val items = historyStore.getRecent(60).map { it.manga }
		historyList.setItems(items)
	}

	// ------------------------------------------------------------- actions

	private fun openManga(manga: Manga) {
		val path = mangaStore.getLocalPath(manga.id)
		if (path != null && File(path).canRead()) {
			ReaderFrame(db, manga, path)
		} else {
			JOptionPane.showMessageDialog(
				this,
				"""“${manga.title}” không có bản local.

				${if (manga.source == LOCAL_SOURCE) "Đường dẫn đã mất: $path" else "Nguồn ${manga.source} (online) chưa được port — Milestone M2."}""".trimIndent(),
				"Chưa đọc được",
				JOptionPane.INFORMATION_MESSAGE,
			)
		}
	}

	private fun removeSelectedLocal() {
		val manga = selectedLocal() ?: return
		db.exec("DELETE FROM local_index WHERE manga_id = ?", manga.id)
		refreshLocal()
	}

	private fun clearHistory() {
		val ok = JOptionPane.showConfirmDialog(
			this, "Xoá toàn bộ lịch sử đọc?", "Xác nhận", JOptionPane.YES_NO_OPTION,
		)
		if (ok == JOptionPane.YES_OPTION) {
			db.exec("DELETE FROM history")
			refreshHistory()
		}
	}

	private fun importBackup() {
		val chooser = javax.swing.JFileChooser().apply {
			fileFilter = FileNameExtensionFilter("Backup Kotatsu (.bak)", "bak")
			dialogTitle = "Nhập backup"
		}
		if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
		val file = chooser.selectedFile

		val result = runCatching {
			FileInputStream(file).use { Backup.restoreBackup(it, db) { /* settings are noted only */ } }
		}
		result.fold(
			onSuccess = { report ->
				val identity = if (report.identityKeyPresent)
					"\n\n⚠ Backup chứa identity key (dùng cho đồng bộ community) — Kotanix không gửi nó đi đâu cả."
				else ""
				val unsupported = if (report.unsupportedSections.isEmpty()) ""
				else "\nChưa hỗ trợ: ${report.unsupportedSections.joinToString(", ")}"
				val failed = if (report.failed.isEmpty()) ""
				else "\nThất bại: ${report.failed.joinToString("\n")}"
				JOptionPane.showMessageDialog(
					this,
					"Nhập xong:\n${report.summary()}$identity$unsupported$failed",
					"Nhập backup",
					JOptionPane.INFORMATION_MESSAGE,
				)
				refreshAll()
			},
			onFailure = { e ->
				JOptionPane.showMessageDialog(
					this, "Không nhập được: ${e.message}", "Lỗi", JOptionPane.ERROR_MESSAGE,
				)
			},
		)
	}

	private fun exportBackup() {
		val chooser = javax.swing.JFileChooser().apply {
			fileFilter = FileNameExtensionFilter("Backup Kotatsu (.bak)", "bak")
			selectedFile = java.io.File("kotanix-backup.bak")
			dialogTitle = "Xuất backup"
		}
		if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
		val file = chooser.selectedFile.let { if (it.extension.equals("bak", true)) it else File(it.path + ".bak") }

		val result = runCatching {
			file.outputStream().use {
				Backup.createBackup(it, db, settingsToDump = mapOf(
					"app" to "kotanix.linux",
					"version" to "0.1.0",
				))
			}
		}
		result.fold(
			onSuccess = {
				JOptionPane.showMessageDialog(
					this, "Đã xuất: ${file.absolutePath}\n\nLưu ý: chiều Kotanix → Android chưa được kiểm chứng trên thiết bị thật.",
					"Xuất backup", JOptionPane.INFORMATION_MESSAGE,
				)
			},
			onFailure = { e ->
				JOptionPane.showMessageDialog(
					this, "Không xuất được: ${e.message}", "Lỗi", JOptionPane.ERROR_MESSAGE,
				)
			},
		)
	}

	private fun importLocalManga(folder: Boolean) {
		val chooser = javax.swing.JFileChooser().apply {
			dialogTitle = if (folder) "Chọn thư mục manga" else "Chọn file CBZ/ZIP"
			if (folder) {
				fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
			} else {
				fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
				fileFilter = FileNameExtensionFilter("CBZ/ZIP", "cbz", "zip")
			}
		}
		if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
		val root = chooser.selectedFile

		val result = runCatching {
			val parser = LocalMangaParser.get(root)
				?: throw IllegalArgumentException("Không phải thư mục/CBZ hợp lệ: ${root.absolutePath}")
			val manga = parser.getManga(withDetails = true)
			mangaStore.upsertManga(manga)
			manga.chapters?.let { mangaStore.replaceChapters(manga.id, it) }
			mangaStore.upsertLocalPath(manga.id, root.absolutePath)
			manga
		}
		result.fold(
			onSuccess = { refreshLocal() },
			onFailure = { e ->
				JOptionPane.showMessageDialog(
					this, "Không thêm được: ${e.message}", "Lỗi", JOptionPane.ERROR_MESSAGE,
				)
			},
		)
	}

	private fun iconImageFromPlaceholder(): java.awt.Image {
		val icon = Placeholder.forTitle("Kotanix", 64)
		return java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
			val g = createGraphics()
			icon.paintIcon(null, g, 0, 0)
			g.dispose()
		}
	}
}
