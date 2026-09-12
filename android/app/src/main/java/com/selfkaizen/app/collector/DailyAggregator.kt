package com.selfkaizen.app.collector

import java.time.Instant
import java.time.ZoneId

/**
 * 確定した区間を日付ごとに集計する。
 *
 * タイムゾーンの扱い:
 *  - 保存は epoch millis、日付キーは端末のローカル日付で計算する。
 *  - 夏時間切替日は 23/25 時間日になるため、日次合計の比較は
 *    epoch 差分ではなくローカル日付キーで行う。
 */
object DailyAggregator {

    /**
     * 区間をローカル日付ごとに分割して合計する。
     *
     * 1つの区間が日付境界をまたぐ場合、各日にはみ出した分だけを計上する。
     * （UsageEvents は END_OF_DAY/CONTINUE_PREVIOUS_DAY で分割してくるが、
     *   STALE_SWEEP や NEXT_RESUMED で確定した区間はまたぐ可能性があるため、
     *   ここでも分割しておく）
     *
     * @return 日付キー（"2026-02-14"）→ パッケージ名 → 合計ミリ秒
     */
    fun aggregate(
        intervals: List<FocusInterval>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<String, Map<String, Long>> {
        val result = mutableMapOf<String, MutableMap<String, Long>>()

        for (interval in intervals) {
            val end = interval.endTime ?: continue
            if (end <= interval.startTime) continue

            var cursor = interval.startTime
            while (cursor < end) {
                val date = toLocalDate(cursor, zone)
                val nextMidnight = startOfNextDay(cursor, zone)
                val segmentEnd = minOf(end, nextMidnight)

                result.getOrPut(date) { mutableMapOf() }
                    .merge(interval.packageName, segmentEnd - cursor) { a, b -> a + b }

                cursor = segmentEnd
            }
        }
        return result
    }

    /** epoch millis をローカル日付キー（"YYYY-MM-DD"）に変換する。 */
    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** 指定時刻が属するローカル日の、翌日 0:00 の epoch millis を返す。 */
    fun startOfNextDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}
