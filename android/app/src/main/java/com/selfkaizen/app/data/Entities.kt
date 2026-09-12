package com.selfkaizen.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 生イベント: 「いつ・どのアプリが・前面に来て・いつ離れたか」。
 *
 * 生イベントを保存しておくことで、後からルール（閾値・NGアプリ）を変更した際に
 * 過去データへ遡って再評価できる。集計だけを保存するとこれができない。
 *
 * 一意インデックス (packageName, startTime) は**冪等性のため**に必要。
 * WorkManager は遅延・再実行があり得るので、同じ区間を二重保存しても
 * 増えないことが保証されなければならない。
 */
@Entity(
    tableName = "app_usage_event",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["packageName"]),
        Index(value = ["packageName", "startTime"], unique = true)
    ]
)
data class AppUsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    /** 表示名。後から解決できなくなった場合に備えて保存する。 */
    val appLabel: String,
    val startTime: Long,
    /** null = まだ使用中。次回の収集でこの区間を引き継ぐ。 */
    val endTime: Long?,
    /**
     * 区間をどう閉じたか。実測（PAUSED / NEXT_RESUMED）と
     * 推定・破棄（STALE_SWEEP / DEVICE_SHUTDOWN）を区別するために必要。
     *
     * null = まだ開いている（endTime も null）。
     */
    val closeReason: String?,

    /**
     * **収集時点**の端末ローカル日付（"YYYY-MM-DD"）。
     *
     * なぜ保存するか:
     * epoch millis から日付を導くと、端末のタイムゾーンが変わったときに
     * **過去のイベントが別の日へ再配分される**。
     *
     * 実測例（東京で 2026-09-13 08:00 に使用）:
     * ```
     *   Asia/Tokyo       → 日付キー 2026-09-13
     *   America/New_York → 日付キー 2026-09-12   ← 変わる
     * ```
     * 渡航・夏時間・手動変更で発生し、後から復元する手段がない。
     * 「その端末にとっての1日」は端末しか正しく判定できないため、
     * 収集時点で確定して保存する。
     *
     * 空文字はマイグレーション前の古い行を表す（集計時にフォールバック）。
     *
     * `@ColumnInfo(defaultValue)` はマイグレーションの
     * `ALTER TABLE ... DEFAULT ''` と一致させるために必要
     * （一致しないと Room のスキーマ検証に失敗する）。
     */
    @ColumnInfo(defaultValue = "")
    val localDate: String = "",

    /**
     * **収集時点**のタイムゾーンID（例 "Asia/Tokyo"）。
     *
     * `localDate` だけでは日境界を切れないため必要。
     * 深夜をまたぐ区間を按分するには「そのゾーンでの 0:00」を知る必要があり、
     * 集計時のタイムゾーンで代用すると `localDate` と矛盾する。
     *
     * 空文字は古い行を表す（集計時にシステム既定へフォールバック）。
     */
    @ColumnInfo(defaultValue = "")
    val zoneId: String = ""
)

/**
 * 画面 OFF / ロック中だった期間。
 *
 * この期間は「アプリが前面のまま」でも使用時間ではないため、
 * 集計時に区間から差し引く。
 * これを保存しないと、画面 OFF 中に前面だったアプリの使用時間が
 * 実測で約3000倍に膨らむ（エミュレータ検証で確認）。
 */
@Entity(
    tableName = "screen_off_period",
    indices = [
        Index(value = ["startTime"]),
        // 同じ期間の重複保存を防ぐ。
        // これがないと、収集のたびに同じ画面OFF期間が積み増される
        // （実データ検証で重複行を確認）。
        Index(value = ["startTime", "endTime"], unique = true)
    ]
)
data class ScreenOffPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long
)

/**
 * 収集の状態は [CollectionState]（UsageDao.kt でキー・バリュー表として定義）を使う。
 * ここには定義しない（重複を避けるため）。
 */

/**
 * 日次集計（表示高速化用キャッシュ）。
 * 生イベントから再生成可能であること。
 */
@Entity(
    tableName = "daily_summary",
    primaryKeys = ["date", "packageName"]
)
data class DailySummary(
    /** ローカル日付キー "YYYY-MM-DD"。 */
    val date: String,
    val packageName: String,
    val appLabel: String,
    val totalMillis: Long,
    val launchCount: Int
)

/**
 * 監視ルール。
 *
 * **現在は「1日の合計上限」のみ。**
 * 勤務時間帯ルールは 2026-09-14 に廃止されたため、
 * `workStartMinute` / `workEndMinute` / `weekdaysOnly` / `targetPackage` を削除した。
 *
 * 設定値の保存先として使うかは未定（`RuleSettings` を DataStore に置く案もある）。
 */
@Entity(tableName = "monitor_rule")
data class MonitorRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 現在は `DAILY_TOTAL_LIMIT` のみ。 */
    val type: String,
    /** 閾値（ミリ秒）。 */
    val thresholdMillis: Long?,
    val enabled: Boolean = true
)

/**
 * 違反記録。
 *
 * 記録するのは**1日の合計上限を超えた事実のみ**（1日につき最大1件）。
 * 勤務時間帯ルールの廃止に伴い、`packageName`（対象アプリ）は削除した。
 */
@Entity(
    tableName = "violation",
    indices = [Index(value = ["date"])],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = MonitorRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class Violation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val detectedAt: Long,
    val date: String,
    val actualMillis: Long,
    val thresholdMillis: Long?,
    /** 人間が読める説明。 */
    val detail: String
)
