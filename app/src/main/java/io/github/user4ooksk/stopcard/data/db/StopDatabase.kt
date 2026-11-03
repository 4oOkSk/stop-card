package io.github.user4ooksk.stopcard.data.db

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
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val packageName: String,
    val mode: String,
    val decided: Boolean,
    val graceSeconds: Int?,
    val updatedAt: Long,
    val userId: Int? = null,
    val uid: Int? = null,
    val installedAt: Long? = null,
    val signer: String? = null,
)

@Entity(tableName = "events", indices = [Index("time"), Index("packageName")])
data class EventEntity(
    @PrimaryKey val id: Long,
    val time: Long,
    val packageName: String,
    val reason: String,
    val sinceLeftMs: Long,
    val detail: String = "",
)

data class PackageCount(
    val packageName: String,
    val count: Int,
    val lastTime: Long,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE packageName = :packageName")
    suspend fun get(packageName: String): RuleEntity?

    @Upsert
    suspend fun upsert(rules: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE packageName IN (:packageNames)")
    suspend fun delete(packageNames: List<String>)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(rules: List<RuleEntity>) {
        deleteAll()
        upsert(rules)
    }
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE time >= :since ORDER BY time DESC")
    fun observeSince(since: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query(
        "SELECT packageName, COUNT(*) AS count, MAX(time) AS lastTime FROM events " +
            "WHERE time >= :since AND reason IN ('card_removed','last_visible','manual','flow_finished','unlicensed_running','woke','left','running') " +
            "GROUP BY packageName"
    )
    fun observeCounts(since: Long): Flow<List<PackageCount>>

    @Query("SELECT * FROM events WHERE packageName = :packageName ORDER BY time DESC LIMIT :limit")
    fun observeForPackage(packageName: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT MAX(id) FROM events")
    suspend fun maxId(): Long?

    @Query("DELETE FROM events WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY time DESC LIMIT :max)")
    suspend fun trimTo(max: Int)
}

@Database(entities = [RuleEntity::class, EventEntity::class], version = 2)
abstract class StopDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao

    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): StopDatabase =
            Room.databaseBuilder(context, StopDatabase::class.java, "stopcard.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE rules ADD COLUMN userId INTEGER")
                        db.execSQL("ALTER TABLE rules ADD COLUMN uid INTEGER")
                        db.execSQL("ALTER TABLE rules ADD COLUMN installedAt INTEGER")
                        db.execSQL("ALTER TABLE rules ADD COLUMN signer TEXT")
                    }
                }).build()
    }
}
