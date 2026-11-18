package kotanix.core

const val RATING_UNKNOWN = -1f
const val LOCAL_SOURCE = "LOCAL"
const val UNKNOWN_SOURCE = "UNKNOWN"

enum class ContentRating { SAFE, SUGGESTIVE, EROTICA, ADULT }

enum class MangaState { ONGOING, FINISHED, ABANDONED, PAUSED, UPCOMING, RESTRICTED, REMOVED, UNKNOWN }


fun String?.toMangaStateOrNull(): MangaState? = this?.let { name ->
	MangaState.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

fun String?.toContentRatingOrNull(): ContentRating? = this?.let { name ->
	ContentRating.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

data class MangaTag(
	val id: Long,
	val title: String,
	val key: String,
	val source: String,
	val isPinned: Boolean = false,
)

data class Manga(
	val id: Long,
	val title: String,
	val altTitles: Set<String> = emptySet(),
	val url: String,
	val publicUrl: String = "",
	/** Normalised [0..1], or -1 (RATING_UNKNOWN) when the source does not expose a rating. */
	val rating: Float = RATING_UNKNOWN,
	val isNsfw: Boolean = false,
	val contentRating: ContentRating? = null,
	val coverUrl: String = "",
	val largeCoverUrl: String? = null,
	val state: MangaState? = null,
	val authors: Set<String> = emptySet(),
	val source: String,
	val description: String? = null,
	val tags: Set<MangaTag> = emptySet(),
	/** null = details not loaded yet; emptyList = loaded and has none */
	val chapters: List<MangaChapter>? = null,
)

data class MangaChapter(
	val id: Long,
	val title: String?,
	val url: String,
	val number: Float = 0f,
	val volume: Int = 0,
	val uploadDate: Long = 0L,
	val scanlator: String? = null,
	val branch: String? = null,
	val source: String,
	/** Position inside the parent manga, as downloaded/declared. -1 when synthetic. */
	val index: Int = -1,
)

data class MangaPage(
	val id: Long,
	val url: String,
	val preview: String? = null,
	val source: String,
)

/** A manga plus where in it the user stopped. Chapter ids and pages are never derived from titles. */
data class HistoryEntry(
	val mangaId: Long,
	val createdAt: Long,
	val updatedAt: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Float,
	val percent: Float,
	val chaptersCount: Int,
)

data class HistoryWithManga(
	val manga: Manga,
	val history: HistoryEntry,
	val tags: Set<MangaTag>,
)

data class FavouriteCategory(
	val categoryId: Int,
	val createdAt: Long,
	val sortKey: Int,
	val title: String,
	val order: String,
	val track: Boolean,
	val isVisibleInLibrary: Boolean,
	val deletedAt: Long = 0L,
)

data class FavouriteEntry(
	val mangaId: Long,
	val categoryId: Long,
	val sortKey: Int,
	val isPinned: Boolean,
	val createdAt: Long,
	val deletedAt: Long = 0L,
)

data class BookmarkEntry(
	val mangaId: Long,
	val pageId: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
	val imageUrl: String,
	val createdAt: Long,
	val percent: Float,
)

data class StatsEntry(
	val mangaId: Long,
	val startedAt: Long,
	val duration: Long,
	val pages: Int,
)
