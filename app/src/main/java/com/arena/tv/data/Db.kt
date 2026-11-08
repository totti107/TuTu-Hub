package com.arena.tv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels", indices = [Index("visible", "groupTitle"), Index("epgKey"), Index("visible", "position"), Index("playlistId")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val visible: Boolean = true,
    val position: Int,
    val name: String,
    val logo: String?,
    val groupTitle: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    /** Lower-cased XMLTV channel id this channel's guide comes from (resolved at EPG import). */
    val epgKey: String?,
    val userAgent: String?,
    val referrer: String?,
    /** Adult content: locked behind the parental PIN (or hidden). */
    @androidx.room.ColumnInfo(defaultValue = "0") val adult: Boolean = false,
    /** The name as the provider wrote it (name may be tidied by the Title Optimizer). */
    @androidx.room.ColumnInfo(defaultValue = "") val rawName: String = "",
)

@Entity(tableName = "programs", indices = [Index(value = ["epgKey", "start"]), Index("stop")])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/** Full-text index over the guide, so a game's channels are found with one quick search instead of scanning everything. */
@androidx.room.Fts4(contentEntity = ProgramEntity::class)
@Entity(tableName = "programs_fts")
data class ProgramFts(val title: String, val subtitle: String?)

@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(@PrimaryKey val channelId: String, val addedAt: Long)

data class RawName(val id: String, val rawName: String)

@Entity(tableName = "favorite_teams")
data class FavoriteTeamEntity(@PrimaryKey val key: String, val name: String, val logo: String?)

@Entity(tableName = "recent_channels")
data class RecentChannelEntity(@PrimaryKey val channelId: String, val at: Long)

/** verdict: 1 = "this channel shows the game", -1 = "it doesn't". */
@Entity(tableName = "match_feedback", primaryKeys = ["eventId", "channelId"], indices = [Index("leagueKey")])
data class FeedbackEntity(val eventId: String, val channelId: String, val leagueKey: String, val verdict: Int, val at: Long)

data class GroupCount(val playlistId: String, val groupTitle: String, val count: Int, val adult: Boolean = false)
data class ChannelLite(val id: String, val name: String, val groupTitle: String, val epgKey: String?)
data class PlaylistGroup(val playlistId: String, val groupTitle: String, val count: Int)
data class AffinityRow(val channelId: String, val n: Int)

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<ChannelEntity>)
    @Query("DELETE FROM channels WHERE playlistId = :pid") suspend fun clearPlaylist(pid: String)
    @Query("DELETE FROM channels WHERE playlistId NOT IN (:keep)") suspend fun clearOthers(keep: List<String>)
    @Query("SELECT COUNT(*) FROM channels WHERE visible = 1") fun countFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE visible = 1") suspend fun count(): Int
    /** Visible categories, per playlist, in the provider's own order. */
    @Query("SELECT playlistId, groupTitle, COUNT(*) AS count, MAX(adult) AS adult FROM channels WHERE visible = 1 GROUP BY playlistId, groupTitle ORDER BY MIN(position)")
    fun groups(): Flow<List<GroupCount>>
    /** Every category of the given playlists, hidden ones included (for the settings toggles). */
    @Query("SELECT playlistId, groupTitle, COUNT(*) AS count FROM channels WHERE playlistId IN (:pids) GROUP BY playlistId, groupTitle ORDER BY MIN(position)")
    fun allGroups(pids: List<String>): Flow<List<PlaylistGroup>>
    @Query("SELECT * FROM channels WHERE visible = 1 AND playlistId = :pid AND groupTitle = :group ORDER BY position")
    fun inGroup(pid: String, group: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE visible = 1 AND playlistId = :pid AND groupTitle = :group ORDER BY position")
    suspend fun inGroupOnce(pid: String, group: String): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE visible = 1 AND name LIKE '%' || :q || '%' ORDER BY position LIMIT 300") fun search(q: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE visible = 1 AND name LIKE '%' || :q || '%' ORDER BY position LIMIT 300") suspend fun searchOnce(q: String): List<ChannelEntity>
    @Query("SELECT c.* FROM channels c JOIN favorite_channels f ON f.channelId = c.id WHERE c.visible = 1 ORDER BY f.addedAt")
    fun favorites(): Flow<List<ChannelEntity>>
    @Query("SELECT c.* FROM channels c JOIN favorite_channels f ON f.channelId = c.id WHERE c.visible = 1 ORDER BY f.addedAt")
    suspend fun favoritesOnce(): List<ChannelEntity>
    @Query("SELECT c.* FROM channels c JOIN recent_channels r ON r.channelId = c.id WHERE c.visible = 1 ORDER BY r.at DESC LIMIT 30")
    fun recents(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE visible = 1 ORDER BY position LIMIT 30000") fun all(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE visible = 1 AND adult = 0 AND logo IS NOT NULL AND logo != '' ORDER BY position LIMIT :n")
    suspend fun withLogos(n: Int): List<ChannelEntity>
    @Query("SELECT COUNT(*) FROM recent_channels r JOIN channels c ON c.id = r.channelId WHERE c.visible = 1") fun recentCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM favorite_channels f JOIN channels c ON c.id = f.channelId WHERE c.visible = 1") fun favoriteCount(): Flow<Int>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun byId(id: String): ChannelEntity?
    @Query("SELECT COUNT(*) FROM channels WHERE visible = 1 AND adult = 0 AND epgKey IS NOT NULL") fun withGuideCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE visible = 1 AND adult = 0") fun safeCount(): Flow<Int>
    @Query("SELECT * FROM channels WHERE visible = 1 AND adult = 0 AND epgKey IS NULL ORDER BY position LIMIT 300") suspend fun withoutGuide(): List<ChannelEntity>
    @Query("UPDATE channels SET rawName = name WHERE rawName = ''") fun fillRawNamesBlocking()
    @Query("SELECT id, rawName FROM channels") fun rawNamesBlocking(): List<RawName>
    @Query("UPDATE channels SET name = :name WHERE id = :id") fun renameBlocking(id: String, name: String)
    @Query("SELECT * FROM channels WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<ChannelEntity>
    @Query("SELECT id, name, groupTitle, epgKey FROM channels WHERE visible = 1") suspend fun allLite(): List<ChannelLite>
    @Query("SELECT id, name, groupTitle, epgKey FROM channels WHERE visible = 1 AND adult = 0 AND epgKey IN (:keys)") suspend fun liteByEpgKeys(keys: List<String>): List<ChannelLite>
    @Query("SELECT id, name, groupTitle, epgKey FROM channels WHERE visible = 1 AND adult = 0 AND name LIKE '%' || :word || '%' LIMIT :limit")
    suspend fun liteByName(word: String, limit: Int): List<ChannelLite>
    @Query("SELECT id, name, groupTitle, epgKey FROM channels WHERE id IN (:ids)") suspend fun liteByIds(ids: List<String>): List<ChannelLite>
    /** Channels that need a guide: visible ones only (hidden categories are skipped to save time). */
    @Query("SELECT id, name, tvgId, tvgName, groupTitle FROM channels WHERE playlistId = :pid AND visible = 1") suspend fun epgCandidates(pid: String): List<EpgCandidate>
    @Query("UPDATE channels SET epgKey = :key WHERE id = :id") suspend fun setEpgKey(id: String, key: String?)
    @Query("SELECT id, epgKey FROM channels WHERE playlistId = :pid AND epgKey IS NOT NULL") suspend fun epgKeysOf(pid: String): List<ChannelEpgKey>
    @Query("UPDATE channels SET epgKey = NULL WHERE playlistId = :pid") suspend fun clearEpgKeys(pid: String)
    @Query("""UPDATE channels SET visible = CASE WHEN playlistId IN (:active) AND (playlistId || '|' || groupTitle) NOT IN (:hidden)
              AND NOT (adult = 1 AND :hideAdult) THEN 1 ELSE 0 END""")
    suspend fun applyVisibility(active: List<String>, hidden: List<String>, hideAdult: Boolean)
    @Transaction suspend fun replacePlaylist(pid: String, items: List<ChannelEntity>) { clearPlaylist(pid); items.chunked(500).forEach { insertAll(it) } }
    @Transaction suspend fun setEpgKeys(pid: String, map: Map<String, String>) { clearEpgKeys(pid); map.forEach { (id, k) -> setEpgKey(id, k) } }
    /** Adds guide links without clearing the ones set by an earlier step. */
    @Transaction suspend fun addEpgKeys(map: Map<String, String>) { map.forEach { (id, k) -> setEpgKey(id, k) } }
}

data class ChannelEpgKey(val id: String, val epgKey: String)
data class EpgCandidate(val id: String, val name: String, val tvgId: String?, val tvgName: String?, val groupTitle: String)

@Dao
interface ProgramDao {
    @Insert fun insertAllBlocking(items: List<ProgramEntity>)
    @Query("DELETE FROM programs") suspend fun clear()
    @Query("SELECT COUNT(*) FROM programs") fun countFlow(): Flow<Int>
    @Query("SELECT * FROM programs WHERE epgKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun forKeys(keys: List<String>, from: Long, to: Long): List<ProgramEntity>
    @Query("SELECT * FROM programs WHERE stop > :from AND start < :to") suspend fun window(from: Long, to: Long): List<ProgramEntity>
    /** Guide entries in a time window whose title/description contain any of the words in [match] (FTS syntax). */
    @Query("""SELECT p.* FROM programs p JOIN programs_fts f ON p.rowId = f.rowid
              WHERE programs_fts MATCH :match AND p.stop > :from AND p.start < :to LIMIT :limit""")
    suspend fun search(match: String, from: Long, to: Long, limit: Int): List<ProgramEntity>
    @Query("SELECT COALESCE(MAX(rowId), 0) FROM programs") fun maxRowId(): Long
    @Query("DELETE FROM programs WHERE rowId <= :cutoff") fun deleteUpTo(cutoff: Long)
    @Query("DELETE FROM programs WHERE rowId <= :cutoff AND epgKey IN (:keys)") fun deleteUpToFor(cutoff: Long, keys: List<String>)
    @Query("SELECT * FROM programs WHERE epgKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun schedule(key: String, from: Long, to: Long): List<ProgramEntity>
    @Query("SELECT * FROM programs WHERE epgKey = :key AND stop > :from ORDER BY start LIMIT :limit")
    suspend fun upcoming(key: String, from: Long, limit: Int): List<ProgramEntity>
}

@Dao
interface UserDao {
    @Upsert suspend fun addFavorite(f: FavoriteChannelEntity)
    @Query("DELETE FROM favorite_channels WHERE channelId = :id") suspend fun removeFavorite(id: String)
    @Query("SELECT channelId FROM favorite_channels") fun favoriteIds(): Flow<List<String>>

    @Upsert suspend fun addTeam(t: FavoriteTeamEntity)
    @Query("DELETE FROM favorite_teams WHERE `key` = :key") suspend fun removeTeam(key: String)
    @Query("SELECT * FROM favorite_teams ORDER BY name") fun teams(): Flow<List<FavoriteTeamEntity>>

    @Upsert suspend fun touchRecent(r: RecentChannelEntity)

    @Upsert suspend fun setFeedback(f: FeedbackEntity)
    @Query("DELETE FROM match_feedback WHERE eventId = :eventId AND channelId = :channelId") suspend fun clearFeedback(eventId: String, channelId: String)
    @Query("SELECT * FROM match_feedback WHERE eventId = :eventId") fun feedbackFor(eventId: String): Flow<List<FeedbackEntity>>
    @Query("SELECT * FROM match_feedback WHERE eventId = :eventId") suspend fun feedbackForOnce(eventId: String): List<FeedbackEntity>
    @Query("SELECT channelId, COUNT(*) AS n FROM match_feedback WHERE leagueKey = :leagueKey AND verdict = 1 GROUP BY channelId")
    suspend fun affinity(leagueKey: String): List<AffinityRow>
}

@Database(
    entities = [ChannelEntity::class, ProgramEntity::class, FavoriteChannelEntity::class, FavoriteTeamEntity::class,
        RecentChannelEntity::class, FeedbackEntity::class, ProgramFts::class, VodEntity::class, VodCategoryEntity::class, EpisodeEntity::class,
        ProgressEntity::class, VodFavoriteEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class ArenaDb : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun programs(): ProgramDao
    abstract fun user(): UserDao
    abstract fun vod(): VodDao

    companion object {
        /** 3 -> 4: adds the guide's full-text index (keeps channels, favorites and picks). */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `programs_fts` USING FTS4(`title` TEXT NOT NULL, `subtitle` TEXT, `description` TEXT, content=`programs`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_BEFORE_UPDATE BEFORE UPDATE ON `programs` BEGIN DELETE FROM `programs_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_BEFORE_DELETE BEFORE DELETE ON `programs` BEGIN DELETE FROM `programs_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_AFTER_UPDATE AFTER UPDATE ON `programs` BEGIN INSERT INTO `programs_fts`(`docid`, `title`, `subtitle`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`subtitle`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_AFTER_INSERT AFTER INSERT ON `programs` BEGIN INSERT INTO `programs_fts`(`docid`, `title`, `subtitle`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`subtitle`); END")
                // Index the guide that's already stored.
                db.execSQL("INSERT INTO `programs_fts`(`programs_fts`) VALUES('rebuild')")
            }
        }

        private const val FTS_CREATE = "CREATE VIRTUAL TABLE IF NOT EXISTS `programs_fts` USING FTS4(`title` TEXT NOT NULL, `subtitle` TEXT, content=`programs`)"

        private val ftsTriggers = listOf(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_BEFORE_UPDATE BEFORE UPDATE ON `programs` BEGIN DELETE FROM `programs_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_BEFORE_DELETE BEFORE DELETE ON `programs` BEGIN DELETE FROM `programs_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_AFTER_UPDATE AFTER UPDATE ON `programs` BEGIN INSERT INTO `programs_fts`(`docid`, `title`, `subtitle`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`subtitle`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_programs_fts_AFTER_INSERT AFTER INSERT ON `programs` BEGIN INSERT INTO `programs_fts`(`docid`, `title`, `subtitle`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`subtitle`); END",
        )

        /** Before a big guide import: stop per-row index updates (the index is rebuilt in one pass afterwards). */
        fun beginGuideImport(db: ArenaDb) {
            val w = db.openHelper.writableDatabase
            listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").forEach { w.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_programs_fts_$it") }
        }

        /** After the import: rebuild the full-text index in one pass and turn syncing back on. */
        fun endGuideImport(db: ArenaDb) {
            val w = db.openHelper.writableDatabase
            w.execSQL("INSERT INTO `programs_fts`(`programs_fts`) VALUES('rebuild')")
            ftsTriggers.forEach(w::execSQL)
        }

        /** 4 -> 5: the guide index covers titles only (much smaller and faster to build). */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").forEach { db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_programs_fts_$it") }
                db.execSQL("DROP TABLE IF EXISTS `programs_fts`")
                db.execSQL(FTS_CREATE)
                ftsTriggers.forEach(db::execSQL)
                db.execSQL("INSERT INTO `programs_fts`(`programs_fts`) VALUES('rebuild')")
            }
        }

        /** 5 -> 6: adult flag on channels. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `adult` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `rawName` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vod` ADD COLUMN `adult` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `vod_categories` ADD COLUMN `adult` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): ArenaDb =
            Room.databaseBuilder(context, ArenaDb::class.java, "arena.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
    }
}
