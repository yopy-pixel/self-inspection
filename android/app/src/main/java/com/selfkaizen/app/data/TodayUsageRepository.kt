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
}
