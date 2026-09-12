package com.selfkaizen.app.rules

import com.selfkaizen.app.collector.FocusInterval
import java.time.LocalDate
import java.time.ZoneId

/**
 * ルール設定。**すべてアプリの設定画面から編集できること**が要件。
 *
 * 設計上の決定:
 *  - 「接近」は固定分数（パーセントではない）
 *  - **勤務時間帯のルールは設けない**（2026-09-14 確定）
 *
 * 勤務時間帯ルールを廃止した理由:
 * アプリが「いつ使ったか」を判定して違反を出すのではなく、
 * **使用量を示して判断は本人が行う**方針となったため。
 * 「NGアプリのリストが不要」ではなく、**勤務時間の判定そのものが不要**。
 */
data class RuleSettings(
    /** 1日の使用上限（分）。UIから変更可能。 */
    val dailyLimitMinutes: Int = 240,
    /** 「接近」とみなす残り時間（分）。UIから変更可能。 */
    val approachingThresholdMinutes: Int = 10,
    val dailyLimitEnabled: Boolean = true
) {
    val dailyLimitMillis: Long get() = dailyLimitMinutes * 60_000L
    val approachingThresholdMillis: Long get() = approachingThresholdMinutes * 60_000L

    init {
        require(dailyLimitMinutes >= 0) { "dailyLimitMinutes が負: $dailyLimitMinutes" }
        require(approachingThresholdMinutes >= 0) {
            "approachingThresholdMinutes が負: $approachingThresholdMinutes"
        }
    }
}

/** 違反の種類。 */
enum class ViolationType {
    /** その日の合計が上限を超えた。1日につき最大1件。 */
    DAILY_TOTAL_LIMIT
}

/**
 * 上限に対する状態。**UIの表示用であり、違反ではない。**
 * この状態は保存しない（表示のたびに計算する）。
 *
 * リングの3状態（OK / CLOSE / OVER）に対応する。
 */
enum class UsageStatus {
    /** 上限に対する判定が無効。 */
    DISABLED,

    /** 残りが接近閾値より大きい。（= OK） */
    WITHIN_LIMIT,

    /** 残りが接近閾値以下（超過はしていない）。（= CLOSE） */
    APPROACHING,

    /** 上限を超えている。（= OVER） */
    EXCEEDED
}

/**
 * ルール違反の検出結果。
 *
 * Room の `Violation` エンティティとは分離している。
 * ルールエンジンを Android / Room に依存させないため（JVM テストで検証できる）。
 *
 * **現在は「1日の合計上限」のみ**であり、アプリ単位の情報は持たない。
 */
data class RuleViolation(
    val type: ViolationType,
    /** 実測値（ミリ秒）。 */
    val actualMillis: Long,
    val thresholdMillis: Long?,
    /** 人間が読める説明。 */
    val detail: String
)

/**
 * ルールエンジン。
 *
 * **純粋なロジックのみ**（Android API に依存しない）ので、JVM ユニットテストで
 * 境界ケースを検証できる。
 *
 * 判定は過去日に対しても再実行できる（生イベントを保存している理由がこれ）。
 * 設定を変えたら過去データにも新しい設定を適用できる。
 */
object RuleEngine {

    /**
     * 指定日の区間から、その日の合計使用時間（重複を除いた実時間）を求める。
     *
     * 区間は同時に1つしか前面に無い設計だが、念のため重複を結合してから合計する
     * （重複があると二重計上になるため）。
     */
    fun totalUsageMillis(intervals: List<FocusInterval>): Long =
        unionMillis(intervals)

    /** 上限に対する状態を求める（UI表示用。違反ではない）。 */
    fun status(totalMillis: Long, settings: RuleSettings): UsageStatus {
        if (!settings.dailyLimitEnabled) return UsageStatus.DISABLED
        val remaining = settings.dailyLimitMillis - totalMillis
        return when {
            remaining < 0 -> UsageStatus.EXCEEDED
            remaining <= settings.approachingThresholdMillis -> UsageStatus.APPROACHING
            else -> UsageStatus.WITHIN_LIMIT
        }
    }

    /**
     * 1日分の区間を評価して違反を返す。
     *
     * @param date 対象のローカル日付
     * @param intervals その日を含む区間（他日にまたがっていてもよい。内部で切り出す）
     * @param settings 設定
     */
    fun evaluate(
        date: LocalDate,
        intervals: List<FocusInterval>,
        settings: RuleSettings,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<RuleViolation> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 対象日に切り出す（日をまたぐ区間に対応）
        val clipped = intervals.mapNotNull { clipToWindow(it, dayStart, dayEnd) }

        val violations = mutableListOf<RuleViolation>()

        // ---- ルール1: 1日の合計上限 ----
        if (settings.dailyLimitEnabled) {
            val total = totalUsageMillis(clipped)
            if (total > settings.dailyLimitMillis) {
                violations += RuleViolation(
                    type = ViolationType.DAILY_TOTAL_LIMIT,
                    actualMillis = total,
                    thresholdMillis = settings.dailyLimitMillis,
                    detail = "1日の合計 ${formatDuration(total)} が" +
                        "上限 ${formatDuration(settings.dailyLimitMillis)} を超えています"
                )
            }
        }

        return violations
    }

    // ---------- 内部ヘルパ ----------

    /** 区間を [from, to) に切り出す。重なりが無ければ null。 */
    private fun clipToWindow(
        interval: FocusInterval,
        from: Long,
        to: Long
    ): FocusInterval? {
        val end = interval.endTime ?: return null
        val start = maxOf(interval.startTime, from)
        val clippedEnd = minOf(end, to)
        if (clippedEnd <= start) return null
        return interval.copy(startTime = start, endTime = clippedEnd)
    }

    /** 重複を結合した実時間の合計。 */
    private fun unionMillis(intervals: List<FocusInterval>): Long {
        val spans = intervals
            .mapNotNull { iv -> iv.endTime?.let { iv.startTime to it } }
            .filter { (s, e) -> e > s }
            .sortedBy { it.first }

        var total = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((s, e) in spans) {
            if (curEnd < 0) {
                curStart = s; curEnd = e
            } else if (s <= curEnd) {
                curEnd = maxOf(curEnd, e)
            } else {
                total += curEnd - curStart
                curStart = s; curEnd = e
            }
        }
        if (curEnd >= 0) total += curEnd - curStart
        return total
    }

    /** ミリ秒を「4時間12分」形式にする。 */
    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}時間${m}分"
            h > 0 -> "${h}時間"
            else -> "${m}分"
        }
    }
}
