package com.arena.tv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object VodKind { const val MOVIE = 0; const val SERIES = 1 }

/** A movie or a series (episodes live in [EpisodeEntity]). key = "m:<id>" / "s:<id>". */
@Entity(tableName = "vod", indices = [Index("kind", "categoryId"), Index("kind", "position"), Index("kind", "added"), Index("tmdb"), Index("playlistId")])
data class VodEntity(
    @PrimaryKey val key: String,
    val playlistId: String,
    val visible: Boolean = true,
    val kind: Int,
    val providerId: String,
    val position: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: Double?,
    val year: Int?,
    val added: Long,
    val ext: String?,
    /** Direct URL for M3U movies; null for Xtream (built from the id). */
    val url: String?,
    val tmdb: String?,
    val plot: String?,
    val genre: String?,
    val backdrop: String?,
    /** In an adult category: hidden or PIN-locked like adult channels. */
    @androidx.room.ColumnInfo(defaultValue = "0") val adult: Boolean = false,
)

@Entity(tableName = "vod_categories", indices = [Index("kind", "position")])
data class VodCategoryEntity(@PrimaryKey val key: String, val kind: Int, val id: String, val name: String, val position: Int,
    val playlistId: String, val visible: Boolean = true, @androidx.room.ColumnInfo(defaultValue = "0") val adult: Boolean = false)

@Entity(tableName = "episodes", indices = [Index("seriesKey", "season", "number")])
data class EpisodeEntity(
    @PrimaryKey val key: String,
    val seriesKey: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
)

/** Resume point for a movie or an episode. */
@Entity(tableName = "progress", indices = [Index("updatedAt")])
data class ProgressEntity(
    @PrimaryKey val key: String,
    val vodKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val label: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = fraction > 0.92f
}

@Entity(tableName = "vod_favorites")
data class VodFavoriteEntity(@PrimaryKey val key: String, val addedAt: Long)

data class CategoryCount(val key: String, val id: String, val name: String, val count: Int, val adult: Boolean = false)
data class CategoryName(val id: String, val name: String)

@Dao
interface VodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertBlocking(items: List<VodEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertCategoriesBlocking(items: List<VodCategoryEntity>)
    @Query("DELETE FROM vod WHERE kind = :kind AND playlistId = :pid") fun clearKindBlocking(pid: String, kind: Int)
    @Query("DELETE FROM vod_categories WHERE kind = :kind AND playlistId = :pid") fun clearCategoriesBlocking(pid: String, kind: Int)
    @Query("DELETE FROM vod WHERE playlistId NOT IN (:keep)") suspend fun clearOthers(keep: List<String>)
    @Query("DELETE FROM vod_categories WHERE playlistId NOT IN (:keep)") suspend fun clearOtherCategories(keep: List<String>)
    @Query("UPDATE vod SET visible = (playlistId IN (:active)) AND NOT (:hideAdult AND adult = 1)") suspend fun applyVisibility(active: List<String>, hideAdult: Boolean)
    @Query("UPDATE vod_categories SET visible = (playlistId IN (:active)) AND NOT (:hideAdult AND adult = 1)") suspend fun applyCategoryVisibility(active: List<String>, hideAdult: Boolean)
    @Query("SELECT id, name FROM vod_categories") suspend fun categoryNames(): List<CategoryName>
    @Query("UPDATE vod_categories SET adult = (id IN (:ids))") suspend fun markAdultCategories(ids: List<String>)
    @Query("UPDATE vod SET adult = (categoryId IN (:ids))") suspend fun markAdultTitles(ids: List<String>)
    @Query("SELECT COUNT(*) FROM vod WHERE playlistId = :pid AND kind = :kind") fun countFor(pid: String, kind: Int): Int
    @Query("DELETE FROM episodes") suspend fun clearEpisodes()
    @Query("SELECT `key` FROM vod WHERE playlistId = :pid AND kind = :kind") fun keysForBlocking(pid: String, kind: Int): List<String>
    @Query("DELETE FROM vod WHERE `key` IN (:keys)") fun deleteKeysBlocking(keys: List<String>)
    @Query("SELECT `key` FROM vod_categories WHERE playlistId = :pid AND kind = :kind") fun categoryKeysBlocking(pid: String, kind: Int): List<String>
    @Query("DELETE FROM vod_categories WHERE `key` IN (:keys)") fun deleteCategoryKeysBlocking(keys: List<String>)

    @Query("SELECT COUNT(*) FROM vod WHERE kind = :kind AND visible = 1 AND adult = 0") fun count(kind: Int): Flow<Int>
    @Query("""SELECT c.`key`, c.id, c.name, COUNT(v.`key`) AS count, c.adult FROM vod_categories c
              LEFT JOIN vod v ON v.kind = c.kind AND v.categoryId = c.id
              WHERE c.kind = :kind AND c.visible = 1 GROUP BY c.`key` HAVING count > 0 ORDER BY c.position""")
    fun categories(kind: Int): Flow<List<CategoryCount>>
    @Query("""SELECT c.`key`, c.id, c.name, COUNT(v.`key`) AS count, c.adult FROM vod_categories c
              LEFT JOIN vod v ON v.kind = c.kind AND v.categoryId = c.id
              WHERE c.kind = :kind AND c.visible = 1 GROUP BY c.`key` HAVING count > 0 ORDER BY c.position""")
    suspend fun categoriesOnce(kind: Int): List<CategoryCount>

    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND ((:cat IS NULL AND adult = 0) OR categoryId = :cat) AND (:minYear IS NULL OR year >= :minYear) AND (:maxYear IS NULL OR year <= :maxYear) ORDER BY position LIMIT :limit OFFSET :offset")
    suspend fun page(kind: Int, cat: String?, minYear: Int?, maxYear: Int?, limit: Int, offset: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND ((:cat IS NULL AND adult = 0) OR categoryId = :cat) AND (:minYear IS NULL OR year >= :minYear) AND (:maxYear IS NULL OR year <= :maxYear) ORDER BY added DESC LIMIT :limit OFFSET :offset")
    suspend fun pageNewest(kind: Int, cat: String?, minYear: Int?, maxYear: Int?, limit: Int, offset: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND ((:cat IS NULL AND adult = 0) OR categoryId = :cat) AND (:minYear IS NULL OR year >= :minYear) AND (:maxYear IS NULL OR year <= :maxYear) ORDER BY rating IS NULL, rating DESC LIMIT :limit OFFSET :offset")
    suspend fun pageTopRated(kind: Int, cat: String?, minYear: Int?, maxYear: Int?, limit: Int, offset: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND ((:cat IS NULL AND adult = 0) OR categoryId = :cat) AND (:minYear IS NULL OR year >= :minYear) AND (:maxYear IS NULL OR year <= :maxYear) ORDER BY name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun pageAz(kind: Int, cat: String?, minYear: Int?, maxYear: Int?, limit: Int, offset: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND adult = 0 AND name LIKE '%' || :q || '%' ORDER BY position LIMIT 200")
    suspend fun search(kind: Int, q: String): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND adult = 0 ORDER BY added DESC LIMIT :n") suspend fun newest(kind: Int, n: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND categoryId = :cat ORDER BY added DESC LIMIT :n") suspend fun newestIn(kind: Int, cat: String, n: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND adult = 0 AND poster IS NOT NULL AND rating >= 7 AND added > :since ORDER BY added DESC LIMIT :n")
    suspend fun spotlightCandidates(kind: Int, since: Long, n: Int): List<VodEntity>
    @Query("SELECT * FROM vod WHERE `key` = :key") suspend fun byKey(key: String): VodEntity?
    @Query("SELECT * FROM vod WHERE `key` IN (:keys)") suspend fun byKeys(keys: List<String>): List<VodEntity>
    @Query("SELECT * FROM vod WHERE kind = :kind AND visible = 1 AND tmdb IN (:ids)") suspend fun byTmdb(kind: Int, ids: List<String>): List<VodEntity>
    @Query("SELECT `key`, name, year FROM vod WHERE kind = :kind AND visible = 1") suspend fun titles(kind: Int): List<VodTitle>
    @Query("UPDATE vod SET plot = :plot, genre = :genre, backdrop = :backdrop, tmdb = COALESCE(:tmdb, tmdb), year = COALESCE(:year, year), rating = COALESCE(:rating, rating) WHERE `key` = :key")
    suspend fun enrich(key: String, plot: String?, genre: String?, backdrop: String?, tmdb: String?, year: Int?, rating: Double?)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpisodes(items: List<EpisodeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertEpisodesBlocking(items: List<EpisodeEntity>)
    @Query("SELECT * FROM episodes WHERE seriesKey = :key ORDER BY season, number") suspend fun episodes(key: String): List<EpisodeEntity>
    @Query("DELETE FROM episodes WHERE seriesKey = :key") suspend fun clearEpisodes(key: String)
    @Transaction suspend fun replaceEpisodes(key: String, items: List<EpisodeEntity>) { clearEpisodes(key); insertEpisodes(items) }

    @Upsert suspend fun saveProgress(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE `key` = :key") suspend fun progress(key: String): ProgressEntity?
    @Query("SELECT * FROM progress WHERE vodKey = :vodKey ORDER BY updatedAt DESC") fun progressFor(vodKey: String): Flow<List<ProgressEntity>>
    @Query("""SELECT p.* FROM progress p WHERE p.updatedAt = (SELECT MAX(q.updatedAt) FROM progress q WHERE q.vodKey = p.vodKey)
              AND p.positionMs > 60000 ORDER BY p.updatedAt DESC LIMIT 30""")
    fun continueWatching(): Flow<List<ProgressEntity>>
    @Query("DELETE FROM progress WHERE vodKey = :vodKey") suspend fun clearProgress(vodKey: String)

    @Upsert suspend fun addFavorite(f: VodFavoriteEntity)
    @Query("DELETE FROM vod_favorites WHERE `key` = :key") suspend fun removeFavorite(key: String)
    @Query("SELECT `key` FROM vod_favorites") fun favoriteKeys(): Flow<List<String>>
    @Query("SELECT v.* FROM vod v JOIN vod_favorites f ON f.`key` = v.`key` WHERE v.kind = :kind AND v.visible = 1 ORDER BY f.addedAt DESC")
    fun favorites(kind: Int): Flow<List<VodEntity>>
}

data class VodTitle(val key: String, val name: String, val year: Int?)
