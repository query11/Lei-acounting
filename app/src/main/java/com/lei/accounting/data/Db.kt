package com.lei.accounting.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lei.accounting.core.dedup.BillMerger
import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.PayApp
import kotlinx.coroutines.flow.Flow

object AiStatus {
    const val PENDING = 0
    const val DONE = 1
    const val SKIPPED = 2
    const val USER_EDITED = 3
    /** DeepSeek 认为这条不是真实交易，界面上提示用户确认 */
    const val SUSPECT = 4
}

@Entity(tableName = "bills", indices = [Index("time"), Index("aiStatus")])
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    /** BillType.name */
    val type: String,
    val category: String,
    val merchant: String = "",
    val payMethod: String = "",
    val note: String = "",
    /** PayApp.name */
    val app: String,
    /** 逗号分隔的 CaptureSource.name，比如 PAGE,NOTIFICATION */
    val sources: String,
    val time: Long,
    val rawText: String = "",
    val aiStatus: Int = AiStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toRecord() = BillRecord(
        id = id,
        amountCents = amountCents,
        type = billType,
        app = payApp,
        sources = BillMerger.decodeSources(sources),
        timeMillis = time,
        category = category,
        merchant = merchant,
        payMethod = payMethod,
        note = note,
    )
}

// 写成扩展属性而不是成员，避免 Room 把它们当成数据库列
val BillEntity.billType: BillType get() = runCatching { BillType.valueOf(type) }.getOrDefault(BillType.EXPENSE)
val BillEntity.payApp: PayApp get() = runCatching { PayApp.valueOf(app) }.getOrDefault(PayApp.MANUAL)

@Entity(tableName = "summaries")
data class SummaryEntity(
    /** DateRange.key，例如 WEEK:2026-10-12 */
    @PrimaryKey val key: String,
    val period: String,
    val title: String,
    val localText: String,
    val aiText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 识别日志：记录收到了哪些微信/支付宝通知、打开了哪些页面、识别结果如何。
 * 只存在本机，最多保留 500 条，用来排查「为什么这一笔没记上」。
 */
@Entity(tableName = "capture_logs", indices = [Index("time")])
data class CaptureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** 通知 / 页面 / 截图 / 手动 */
    val channel: String,
    val pkg: String,
    val result: String,
    val detail: String,
)

@Dao
interface CaptureLogDao {
    @Insert
    suspend fun insert(log: CaptureLogEntity)

    @Query("SELECT * FROM capture_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureLogEntity>>

    @Query("SELECT * FROM capture_logs ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CaptureLogEntity>

    /** 某个渠道最近一条真实事件（排除「服务已连接」这类没有包名的状态记录） */
    @Query("SELECT * FROM capture_logs WHERE channel = :channel AND pkg != '' ORDER BY id DESC LIMIT 1")
    fun observeLatest(channel: String): Flow<CaptureLogEntity?>

    @Query("DELETE FROM capture_logs WHERE id NOT IN (SELECT id FROM capture_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM capture_logs")
    suspend fun clear()
}

@Dao
interface BillDao {
    @Insert
    suspend fun insert(bill: BillEntity): Long

    @Update
    suspend fun update(bill: BillEntity)

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun get(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE time >= :start AND time < :end ORDER BY time DESC")
    fun observeRange(start: Long, end: Long): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE time >= :start AND time < :end ORDER BY time DESC")
    suspend fun range(start: Long, end: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE time >= :since ORDER BY time DESC")
    suspend fun since(since: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE aiStatus = 0 ORDER BY time DESC LIMIT :limit")
    suspend fun pendingAi(limit: Int): List<BillEntity>

    @Query("SELECT * FROM bills ORDER BY time DESC")
    suspend fun all(): List<BillEntity>
}

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Query("SELECT * FROM summaries WHERE `key` = :key")
    fun observe(key: String): Flow<SummaryEntity?>

    @Query("SELECT * FROM summaries WHERE `key` = :key")
    suspend fun get(key: String): SummaryEntity?
}

@Database(entities = [BillEntity::class, SummaryEntity::class, CaptureLogEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bills(): BillDao
    abstract fun summaries(): SummaryDao
    abstract fun logs(): CaptureLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "lei_accounting.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}

/** v0.1 → v0.2：新增识别日志表，原有账单保留 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `capture_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `channel` TEXT NOT NULL, " +
                "`pkg` TEXT NOT NULL, `result` TEXT NOT NULL, `detail` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_logs_time` ON `capture_logs` (`time`)")
    }
}
