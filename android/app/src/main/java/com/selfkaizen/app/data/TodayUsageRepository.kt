package com.selfkaizen.app.data

import com.selfkaizen.app.rules.RuleEngine
import com.selfkaizen.app.rules.RuleSettings
import com.selfkaizen.app.rules.UsageStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日の使用状況（ウィジェットとダッシュボードで共有する）。
 *
 * **同じ計算を2箇所に書かない**ための小さなリポジトリ。
 * ウィジェットとダッシュボードで数値がずれると、
 * どちらが正しいか分からなくなる。
 */
data class TodayUsage(
    val todayMillis: Long,
    val limitMillis: Long,
    val status: UsageStatus,
    /** 前回の収集完了時刻。null = 未収集。 */
    val lastCollectedAt: Long?,
    /** 上限までの残り（超過時は負）。 */
    val remainingMillis: Long,
    /** バー用の塗り率 0.0〜1.0。上限が0なら1.0。 */
    val fraction: Float
)

class TodayUsageRepository(
    private val dao: UsageDao,
    private val settingsRepository: SettingsRepository
) {

    suspend fun load(zone: ZoneId = ZoneId.systemDefault()): TodayUsage {
        val today = LocalDate.now(zone).toString()
        val settings: RuleSettings = settingsRepository.load()

        val total = dao.summariesForDate(today).sumOf { it.totalMillis }
        val limit = settings.dailyLimitMillis

        return TodayUsage(
            todayMillis = total,
            limitMillis = limit,
            status = RuleEngine.status(total, settings),
            lastCollectedAt = dao.getState(CollectionKeys.LAST_COLLECTED_AT)?.toLongOrNull(),
            remainingMillis = limit - total,
            fraction = if (limit <= 0L) 1f else (total.toDouble() / limit.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        )
    }

    /**
     * 指定日の合計。
     *
     * **null と 0 を区別する。**
     * 集計行が無い（= その日のデータが無い）場合は null を返す。
     * 0 に潰すと「0m 使った日」と「データが無い日」が同じになり、
     * 通知が昨日の振り返りを出すかどうかを誤判定する。
     *
     * @return 合計ミリ秒。その日の集計行が1つも無ければ null
     */
    suspend fun totalFor(date: LocalDate): Long? {
        val rows = dao.summariesForDate(date.toString())
        return if (rows.isEmpty()) null else rows.sumOf { it.totalMillis }
    }
}

/**
 * データが読めないときのプレースホルダ。
 *
 * 状態は権限の有無で `NO_PERMISSION` / `STALE` に落ちるため、
 * **数字は表示されない**（[com.selfkaizen.app.data.condition] 参照）。
 */
fun unknownTodayUsage() = TodayUsage(
    todayMillis = 0L,
    limitMillis = 0L,
    status = UsageStatus.WITHIN_LIMIT,
    lastCollectedAt = null,
    remainingMillis = 0L,
    fraction = 0f
)
