package com.selfkaizen.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 収集した生イベントの読み書き。
 *
 * 設計の要点:
 *  - **冪等な upsert**: 同じ区間を何度保存しても重複しない。
 *    WorkManager は遅延・再実行があり得るため、冪等性が必須。
 *  - **差分収集**: 前回の収集時刻を記録し、そこから先だけを読む。
 *    `queryEvents` は数日分しか保持しないため、毎日回す必要がある。
 */
@Dao
interface UsageDao {

    // ------------------------------------------------------------------
    // 生イベント
    // ------------------------------------------------------------------

    /**
     * 区間をまとめて保存する。
     *
     * 一意インデックス（packageName, startTime）により、
     * 同じ区間の再保存は無視される（IGNORE 戦略）。
     *
     * なぜ REPLACE ではなく IGNORE か:
     * 既存の区間は確定済みの実測値であり、後から来た推定値で
     * 上書きされるべきではないため。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<AppUsageEvent>): List<Long>

    /** 指定期間の区間を取得する（日次集計の再生成に使う）。 */
    @Query(
        """
        SELECT * FROM app_usage_event
        WHERE endTime IS NOT NULL
          AND startTime >= :from AND startTime < :to
        ORDER BY startTime
        """
    )
    suspend fun eventsBetween(from: Long, to: Long): List<AppUsageEvent>

    /**
     * 未クローズの区間（endTime IS NULL）を取得する。
     *
     * 定期収集でこれを使い、**前回の区間を引き継いで**から今回のイベントを
     * 処理する。引き継がないと、アプリの RESUMED が今回の読み取り範囲より
     * 前にあった場合に PAUSED が来ても区間を閉じられず、
     * 12時間後の掃除で**まるごと破棄されてしまう**。
     */
    @Query("SELECT * FROM app_usage_event WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun openEvents(): List<AppUsageEvent>

    /**
     * 未クローズの区間を、パッケージ名と開始時刻で特定して閉じる。
     *
     * 冪等な upsert の一部。`insertEvents` は IGNORE 戦略のため、
     * 既存の未クローズ行に後から終了点が判明しても自動では更新されない。
     * ここで明示的に閉じる。
     *
     * `endTime IS NULL` 条件により、既に確定済みの区間は上書きされない。
     */
    @Query(
        """
        UPDATE app_usage_event
        SET endTime = :endTime, closeReason = :closeReason
        WHERE packageName = :packageName
          AND startTime = :startTime
          AND endTime IS NULL
        """
    )
    suspend fun closeOpenEvent(
        packageName: String,
        startTime: Long,
        endTime: Long,
        closeReason: String
    )

    /** 区間を削除する（古すぎる未クローズ区間の掃除に使う）。 */
    @Query("DELETE FROM app_usage_event WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    /**
     * 指定した区間**以外**の未クローズ区間を削除する。
     *
     * 前面にいられるアプリは常に1つなので、未クローズ区間も本来1件以下である。
     * 再起動をまたいで放棄された区間が残ると複数になり、
     * 「どれを引き継ぐか」が曖昧になる（実データ検証で2件残るのを確認）。
     *
     * `DEVICE_SHUTDOWN` をまたぐ区間は閉じ時刻が不明なため、公式に
     * 「無視すべき」とされている。よって破棄するのが正しい。
     *
     * @param keepPackageName 残す区間のパッケージ名（null なら全て削除）。
     * @param keepStartTime 残す区間の開始時刻。
     */
    @Query(
        """
        DELETE FROM app_usage_event
        WHERE endTime IS NULL
          AND (packageName IS NOT :keepPackageName OR startTime IS NOT :keepStartTime)
        """
    )
    suspend fun deleteOpenEventsExcept(keepPackageName: String?, keepStartTime: Long?)

    /** 保存済みの最新区間の開始時刻。差分収集の起点に使う。 */
    @Query("SELECT MAX(startTime) FROM app_usage_event")
    suspend fun latestEventStart(): Long?

    @Query("SELECT COUNT(*) FROM app_usage_event")
    suspend fun eventCount(): Int

    // ------------------------------------------------------------------
    // 収集カーソル（どこまで読んだか）
    // ------------------------------------------------------------------

    /**
     * 前回の収集完了時刻を取得する。
     *
     * これが差分収集の起点。`queryEvents` は数日分しか保持しないため、
     * 「前回の続きから」読むことがデータ欠損を防ぐ唯一の方法。
     */
    @Query("SELECT value FROM collection_state WHERE key = :key")
    suspend fun getState(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: CollectionState)

    // ------------------------------------------------------------------
    // 画面 OFF / ロック期間
    // ------------------------------------------------------------------

    /**
     * 画面 OFF / ロック中だった期間を保存する。
     *
     * エミュレータでの実データ検証（2026-09-14）で、この除外がないと
     * 「設定」アプリの使用時間が 562秒 と集計され、公式 `queryUsageStats` の
     * 0.06秒 に対して**約3000倍**に膨らむことが判明した。
     * 画面 OFF 中もアプリは「前面」のままなので、区間をそのまま計上すると
     * 実際には使っていない時間まで含まれてしまう。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScreenOffPeriods(periods: List<ScreenOffPeriodEntity>)

    /** 保存済みの画面 OFF 期間を取得する（集計時の差し引きに使う）。 */
    @Query(
        """
        SELECT * FROM screen_off_period
        WHERE endTime > :from AND startTime < :to
        ORDER BY startTime
        """
    )
    suspend fun screenOffPeriodsBetween(from: Long, to: Long): List<ScreenOffPeriodEntity>

    @Query("SELECT COUNT(*) FROM screen_off_period")
    suspend fun screenOffPeriodCount(): Int

    // ------------------------------------------------------------------
    // 日次集計
    // ------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySummary(summaries: List<DailySummary>)

    @Query("SELECT * FROM daily_summary WHERE date = :date ORDER BY totalMillis DESC")
    suspend fun summariesForDate(date: String): List<DailySummary>

    @Query("SELECT DISTINCT date FROM daily_summary ORDER BY date DESC LIMIT :limit")
    suspend fun recentDates(limit: Int): List<String>

    @Query("DELETE FROM daily_summary WHERE date = :date")
    suspend fun deleteSummariesForDate(date: String)

    /**
     * 指定日の集計を、生イベントから作り直す。
     *
     * 集計は生イベントから**再生成可能**でなければならない。
     * ルール（閾値）を後から変えたときに過去を再評価するため。
     */
    @Transaction
    suspend fun replaceDailySummaries(date: String, summaries: List<DailySummary>) {
        deleteSummariesForDate(date)
        upsertDailySummary(summaries)
    }

    // ------------------------------------------------------------------
    // ルール / 違反
    // ------------------------------------------------------------------

    @Query("SELECT * FROM monitor_rule WHERE enabled = 1")
    suspend fun enabledRules(): List<MonitorRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: MonitorRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViolations(violations: List<Violation>)

    @Query("SELECT * FROM violation ORDER BY detectedAt DESC LIMIT :limit")
    suspend fun recentViolations(limit: Int): List<Violation>

    @Query("DELETE FROM violation WHERE date = :date AND ruleId = :ruleId")
    suspend fun deleteViolations(date: String, ruleId: Long)
}

/** 収集の状態を保持する単純なキー・バリュー表。 */
@androidx.room.Entity(tableName = "collection_state")
data class CollectionState(
    @androidx.room.PrimaryKey val key: String,
    val value: String
)

/** 収集カーソルのキー。 */
object CollectionKeys {
    /** 前回の収集が完了した時刻（epoch millis）。 */
    const val LAST_COLLECTED_AT = "last_collected_at"

    /** 前回の収集が成功したか（デバッグ用）。 */
    const val LAST_RESULT = "last_result"
}

@Database(
    entities = [
        AppUsageEvent::class,
        DailySummary::class,
        MonitorRule::class,
        Violation::class,
        CollectionState::class,
        ScreenOffPeriodEntity::class
    ],
    // version 4: 勤務時間帯ルール廃止に伴い、
    //   monitor_rule から workStartMinute / workEndMinute / weekdaysOnly / targetPackage を削除
    //   violation から packageName を削除
    // fallbackToDestructiveMigration のため既存データは破棄される。
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
}
