package kotanix.ui

import kotanix.backup.Backup
import kotanix.core.LOCAL_SOURCE
import kotanix.core.Manga
import kotanix.data.*
import kotanix.local.LocalMangaParser
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.File
import java.io.FileInputStream
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Kotanix main window — Kotatsu-Redo's dark look translated to Swing: black background,
 * pill search bar, cover grid, Material-3 bottom navigation and an extended FAB.
 * Tab order follows the Android home (History → Favourites → Explore → Local);
 * backup import/export and library actions live in the overflow (⋮) menu and per-tab chips
 * (no desktop menu bar, like Android). Tapping a card opens the details screen, like Android.
 */
class KotanixApp {

	private lateinit var main: MainFrame

	fun start() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		} catch (_: Exception) {
			// fall back to the default look and feel
		}
		// dark scrollbars + popups so the native chrome matches the theme
		UIManager.put("ScrollBar.thumb", T.SurfaceHigh)
		UIManager.put("ScrollBar.thumbDarkShadow", T.SurfaceHigh)
		UIManager.put("ScrollBar.thumbHighlight", T.SurfaceHigh)
		UIManager.put("ScrollBar.thumbShadow", T.SurfaceHigh)
		UIManager.put("ScrollBar.track", T.Bg)
		UIManager.put("ScrollBar.background", T.Bg)
		UIManager.put("PopupMenu.background", T.SurfaceHigh)
		UIManager.put("MenuItem.background", T.SurfaceHigh)
		UIManager.put("MenuItem.foreground", T.OnBg)
		UIManager.put("MenuItem.selectionBackground", T.SurfaceSelected)
		UIManager.put("MenuItem.selectionForeground", T.Accent)
		UIManager.put("OptionPane.background", T.SurfaceHigh)
		UIManager.put("Panel.background", T.SurfaceHigh)
		UIManager.put("TextField.background", T.Surface)
		UIManager.put("TextField.foreground", T.OnBg)

		val db = AppPaths.openDatabase()
		main = MainFrame(db)
		main.isVisible = true
	}
}

class MainFrame(
	private val db: SqliteDatabase,
) : javax.swing.JFrame("Kotanix") {

	private companion object {
		const val TAB_HISTORY = 0
		const val TAB_FAVOURITES = 1
		const val TAB_EXPLORE = 2
		const val TAB_LOCAL = 3
		const val TAB_COUNT = 4
	}

	private val mangaStore = MangaStore(db)
	private val favouritesStore = FavouritesStore(db)
	private val historyStore = HistoryStore(db)

	private val favouritesGrid = CoverGridPanel("Chưa có manga yêu thích.\nMở một bộ truyện rồi bấm “Yêu thích”.") { openDetails(it) }
	private val localGrid = CoverGridPanel("Chưa có manga local.\nBấm “Thêm thư mục…” hoặc “Thêm CBZ…” phía trên.") { openDetails(it) }
	private val historyPanel = HistorySectionsPanel("Chưa có lịch sử đọc.") { openDetails(it) }
	private val explorePanel = ExplorePanel(
		db, mangaStore,
		onOpenDetails = { openDetails(it) },
		onOpenLocalTab = { selectTab(TAB_LOCAL) },
		onShowBookmarks = { showBookmarks() },
		onInfo = { message -> infoDialog(message) },
		onShowCatalog = { showSourcesCatalog() },
	)

	private val screenNames = listOf("history", "favourites", "explore", "local")
	private val queries = arrayOf("", "", "", "")
	private var currentTab = TAB_HISTORY
	private var lastHistoryCount = 0

	private val cardStack = javax.swing.JPanel(CardLayout()).apply { background = T.Bg }
	private val searchPill = SearchPill("Tìm manga…") { q ->
		queries[currentTab] = q
		applyFilterFor(currentTab, q)
	}

	// favourites chips
	private var currentCategoryId: Long = 1L
	private var currentCategoryTitle: String = "Thư viện"
	private val categoryChip = PillChip({ "Danh mục: $currentCategoryTitle  ▾" }) { showCategoryMenu() }

	// local chips
	private val addFolderChip = PillChip({ "Thêm thư mục…" }, Icons.folder(18, T.OnBg)) { importLocalManga(folder = true) }
	private val addCbzChip = PillChip({ "Thêm CBZ…" }, Icons.filePlus(18, T.OnBg)) { importLocalManga(folder = false) }

	// history chips
	private var historyLocalOnly = false
	private var historyCompletedOnly = false
	private val historyLocalOnlyChip: PillChip = PillChip({ "Trên máy" }, Icons.folder(18, T.Accent)) {
		historyLocalOnly = !historyLocalOnly
		historyLocalOnlyChip.toggled = historyLocalOnly
		refreshHistory()
	}
	private val historyCompletedChip: PillChip = PillChip({ "Hoàn thành" }, Icons.check(18, T.Accent)) {
		historyCompletedOnly = !historyCompletedOnly
		historyCompletedChip.toggled = historyCompletedOnly
		refreshHistory()
	}
	private val clearHistoryChip = PillChip({ "Xoá lịch sử" }, Icons.trash(18, T.OnBg)) { clearHistory() }

	private val fab = ExtendedFab("Đọc tiếp", Icons.bookOpen(26, T.OnAccent)) { openMostRecent() }

	// FAB sits in a right-aligned transparent row above the bottom navigation.
	// Keep this a plain BorderLayout child: in this codebase a null-layout
	// container only ever paints its FIRST child (found the hard way — card stack
	// painted, the FAB on top of it silently vanished). No overlapping children.
	private val fabRow = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
		isOpaque = false
		border = javax.swing.border.EmptyBorder(0, 12, 12, 16)
		add(fab)
	}

	private val centerPanel = javax.swing.JPanel(BorderLayout()).apply {
		isOpaque = true
		background = T.Bg
		add(cardStack, BorderLayout.CENTER)
		add(fabRow, BorderLayout.SOUTH)
	}

	private val nav = BottomNav(
		listOf(
			"Lịch sử" to { size: Int, color: java.awt.Color -> Icons.clock(size, color) },
			"Yêu thích" to { size: Int, color: java.awt.Color -> Icons.heart(size, color, filled = false) },
			"Khám phá" to { size: Int, color: java.awt.Color -> Icons.compass(size, color) },
			"Local" to { size: Int, color: java.awt.Color -> Icons.folder(size, color) },
		),
	) { index -> selectTab(index) }

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		minimumSize = Dimension(880, 560)
		setSize(1180, 740)
		setLocationRelativeTo(null)

		iconImage = runCatching {
			javaClass.getResource("/icons/kotanix-64.png")?.let { javax.imageio.ImageIO.read(it) }
		}.getOrNull() ?: iconImageFromPlaceholder()

		contentPane.background = T.Bg
		layout = BorderLayout()

		// ---- top bar: search pill + overflow menu
		val topBar = javax.swing.JPanel(BorderLayout()).apply {
			isOpaque = false
			border = javax.swing.border.EmptyBorder(12, 16, 8, 12)
		}
		topBar.add(searchPill, BorderLayout.CENTER)
		val overflow = IconButton({ size -> Icons.moreVert(size, T.OnBg) }, 44) { showOverflowMenu() }
		val overflowWrap = javax.swing.JPanel(BorderLayout()).apply {
			isOpaque = false
			border = javax.swing.border.EmptyBorder(0, 8, 0, 0)
		}
		overflowWrap.add(overflow, BorderLayout.CENTER)
		topBar.add(overflowWrap, BorderLayout.EAST)
		add(topBar, BorderLayout.NORTH)

		// ---- screens (each with its own chip row; Explore is the Android browse surface)
		cardStack.add(buildScreen(chips(historyLocalOnlyChip, historyCompletedChip, clearHistoryChip), historyPanel), screenNames[TAB_HISTORY])
		cardStack.add(buildScreen(chips(categoryChip), favouritesGrid), screenNames[TAB_FAVOURITES])
		cardStack.add(explorePanel, screenNames[TAB_EXPLORE])
		cardStack.add(buildScreen(chips(addFolderChip, addCbzChip), localGrid), screenNames[TAB_LOCAL])
		add(centerPanel, BorderLayout.CENTER)

		add(nav, BorderLayout.SOUTH)

		refreshAll()
		selectTab(TAB_HISTORY)
	}

	private fun selectTab(index: Int) {
		queries[currentTab] = searchPill.query()
		currentTab = index.coerceIn(0, TAB_COUNT - 1)
		nav.selectedIndex = currentTab
		(cardStack.layout as CardLayout).show(cardStack, screenNames[currentTab])
		searchPill.setQuery(queries[currentTab])
		applyFilterFor(currentTab, queries[currentTab])
		syncFabVisibility()
	}

	private fun applyFilterFor(tab: Int, query: String) {
		when (tab) {
			TAB_EXPLORE -> explorePanel.setQuery(query)
			TAB_HISTORY -> refreshHistory()
			TAB_FAVOURITES -> favouritesGrid.setFilter(query)
			TAB_LOCAL -> localGrid.setFilter(query)
		}
	}

	private fun syncFabVisibility() {
		fabRow.isVisible = currentTab == TAB_HISTORY && lastHistoryCount > 0
		centerPanel.revalidate()
		centerPanel.repaint()
	}

	// ------------------------------------------------------------ screens

	private fun chips(vararg chips: PillChip): javax.swing.JPanel =
		javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
			isOpaque = false
			border = javax.swing.border.EmptyBorder(4, 16, 8, 16)
			chips.forEach { add(it) }
		}

	private fun buildScreen(chipRow: javax.swing.JPanel?, content: javax.swing.JComponent): javax.swing.JPanel =
		javax.swing.JPanel(BorderLayout()).apply {
			isOpaque = false
			chipRow?.let { add(it, BorderLayout.NORTH) }
			add(content, BorderLayout.CENTER)
		}

	// ------------------------------------------------------------ menu

	private fun showOverflowMenu() {
		val menu = darkPopup().apply {
			add(darkMenuItem("Nhập backup (.bak)…", Icons.imports(18, T.OnBg)) { importBackup() })
			add(darkMenuItem("Xuất backup (.bak)…", Icons.exports(18, T.OnBg)) { exportBackup() })
			addSeparator()
			add(darkMenuItem("Giới thiệu", Icons.info(18, T.OnBg)) { showAbout() })
			addSeparator()
			add(darkMenuItem("Thoát") { dispose() })
		}
		menu.show(this, width - menu.preferredSize.width - 16, searchPillTopOffset())
	}

	private fun searchPillTopOffset(): Int = 64

	private fun showCategoryMenu() {
		val menu = darkPopup()
		for (category in favouritesStore.listCategories()) {
			val id = category.categoryId.toLong()
			val item = darkMenuItem(category.title) {
				currentCategoryId = id
				currentCategoryTitle = category.title
				categoryChip.refreshText()
				refreshFavourites()
			}
			if (id == currentCategoryId) {
				item.icon = javax.swing.ImageIcon(Icons.check(18, T.Accent))
			}
			menu.add(item)
		}
		if (menu.subElements.isEmpty()) {
			menu.add(darkMenuItem("Thư viện") {
				currentCategoryId = 1L
				currentCategoryTitle = "Thư viện"
				categoryChip.refreshText()
				refreshFavourites()
			})
		}
		menu.show(this, 16, searchPillTopOffset())
	}

	private fun showAbout() {
		JOptionPane.showMessageDialog(
			this,
			"""Kotanix 0.1.0 — bản port Linux của Kotatsu-Redo (Android), dựa trên mã nguồn
				Kotatsu-Redo 9.8.7 (GPL-3.0). Backup .bak tương thích định dạng Android (chưa có
				xác nhận trên thiết bị thật cho chiều Kotanix → Android).

				Đọc được manga local: thư mục / CBZ / CBZ lồng nhau.
				Giao diện mô phỏng Kotatsu Android (dark, cover grid, bottom navigation).
				Mã nguồn: giữ nguyên attribution theo UPSTREAM.lock.""".trimIndent(),
			"Giới thiệu Kotanix",
			JOptionPane.INFORMATION_MESSAGE,
		)
	}

	private fun infoDialog(message: String) {
		JOptionPane.showMessageDialog(this, message, "Kotanix", JOptionPane.INFORMATION_MESSAGE)
	}

	// ------------------------------------------------------------ refresh

	private fun historyPercent(m: Manga): Float? = historyStore.get(m.id)?.percent

	private fun refreshAll() {
		refreshCategories()
		refreshFavourites()
		refreshLocal()
		refreshHistory()
		explorePanel.refreshData()
	}

	private fun refreshCategories() {
		val first = favouritesStore.listCategories().firstOrNull()
		currentCategoryId = first?.categoryId?.toLong() ?: 1L
		currentCategoryTitle = first?.title ?: "Thư viện"
		categoryChip.refreshText()
	}

	private fun refreshFavourites() {
		val items = favouritesStore.getFavouriteManga(currentCategoryId).map { GridItem(it, historyPercent(it)) }
		favouritesGrid.setItems(items)
		favouritesGrid.setFilter(queries[TAB_FAVOURITES])
	}

	private fun refreshLocal() {
		val pairs = mangaStore.findLocalManga()
		val items = pairs.mapNotNull { (id, _) -> mangaStore.findById(id) }
		localGrid.setItems(items.map { GridItem(it, historyPercent(it)) })
		localGrid.setFilter(queries[TAB_LOCAL])
	}

	private fun refreshHistory() {
		val all = historyStore.getRecent(120)
		lastHistoryCount = all.size

		var entries = all
		val q = queries[TAB_HISTORY].trim().lowercase()
		if (q.isNotEmpty()) {
			entries = entries.filter {
				it.manga.title.lowercase().contains(q) ||
					it.manga.altTitles.any { alt -> alt.lowercase().contains(q) }
			}
		}
		if (historyLocalOnly) entries = entries.filter { it.manga.source == LOCAL_SOURCE }
		if (historyCompletedOnly) entries = entries.filter { it.history.percent >= 0.995f }

		historyPanel.setItems(entries.map { HistoryCard(it.manga, it.history.percent, it.history.updatedAt) })
		syncFabVisibility()
	}

	// ------------------------------------------------------------ details + reader

	private fun openDetails(manga: Manga) {
		val full = mangaStore.findById(manga.id) ?: manga
		val path = mangaStore.getLocalPath(manga.id)
		MangaDetailsDialog(
			this, db, full, path,
			onOpenReader = { m, chapter -> openReaderOrMessage(m, chapter) },
			onFavouriteChanged = { refreshFavourites() },
			onRemoveLocal = { removeLocalManga(it) },
		).isVisible = true
	}

	private fun openReaderOrMessage(manga: Manga, chapterIndex: Int?): Boolean {
		val path = mangaStore.getLocalPath(manga.id)
		if (path != null && File(path).canRead()) {
			ReaderFrame(db, manga, path, chapterIndex ?: -1)
			return true
		}
		JOptionPane.showMessageDialog(
			this,
			"""“${manga.title}” không có bản local.

			${if (manga.source == LOCAL_SOURCE) "Đường dẫn đã mất: $path" else "Nguồn ${manga.source} (online) chưa được port — Milestone M2."}""".trimIndent(),
			"Chưa đọc được",
			JOptionPane.INFORMATION_MESSAGE,
		)
		return false
	}

	private fun removeLocalManga(manga: Manga) {
		db.exec("DELETE FROM local_index WHERE manga_id = ?", manga.id)
		refreshAll()
	}

	private fun showBookmarks() {
		BookmarksDialog(this, db) { manga -> openDetails(manga) }.isVisible = true
	}

	private fun showSourcesCatalog() {
		SourcesCatalogDialog(
			this, db,
			onInfo = { message -> infoDialog(message) },
			onPinsChanged = { explorePanel.refreshData() },
		).isVisible = true
	}

	// ------------------------------------------------------------ actions

	private fun openMostRecent() {
		val latest = historyStore.getRecent(1).firstOrNull()?.manga ?: return
		openReaderOrMessage(latest, null)
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
			onSuccess = { refreshAll() },
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
