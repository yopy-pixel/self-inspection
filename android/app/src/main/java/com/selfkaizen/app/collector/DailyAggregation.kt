package com.selfkaizen.app.collector

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 日次集計の1行。 */
data class DailyAggregate(
    val date: String,
    val packageName: String,
    val totalMillis: Long,
    val launchCount: Int
)

/**
 * 区間から日次集計を作る。
 *
 * Android に依存しない純粋関数なので JVM でテストできる。
 *
 * ## 満たすべき性質
 *
 * 1. **画面 OFF を差し引く。** 差し引かないと実測で約3000倍に膨らむ。
 * 2. **日付境界で分割する。** 深夜をまたぐ区間は各日に按分する。
 * 3. **「その日全体」を渡すこと。** 一部の窓だけを渡して日次を置換すると、
 *    その日の他の時間帯のデータが消える（実データ検証で発見されたバグ）。
 *    呼び出し側は必ず日単位の範囲で集計すること。
 *
 * @param intervals 対象期間の区間（画面 OFF 差し引き前）。
 * @param screenOff 対象期間の画面 OFF / ロック期間。
 * @param zone 日付の判定に使うタイムゾーン。
 */
fun aggregateDaily(
    intervals: List<FocusInterval>,
    screenOff: List<ScreenOffPeriod> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault()
): List<DailyAggregate> =
    aggregateDailyDated(
        dated = intervals.map {
            DatedInterval(
                interval = it,
                localDate = DailyAggregator.toLocalDate(it.startTime, zone),
                zoneId = zone.id
            )
        },
        screenOff = screenOff
    )

/**
 * 区間と、その区間を収集した時点の「端末ローカルの日」情報の組。
 *
 * `localDate` / `zoneId` は**収集時に確定した値**を使う。
 * 集計時のタイムゾーンで計算し直すと、渡航・夏時間・手動変更のあとに
 * 過去の日付が変わってしまう。
 */
data class DatedInterval(
    val interval: FocusInterval,
    /** 区間開始時点のローカル日付 "YYYY-MM-DD"。 */
    val localDate: String,
    /** 収集時のタイムゾーンID 例 "Asia/Tokyo"。 */
    val zoneId: String
)

/**
 * 保存済みの日付情報を使って日次集計を作る。
 *
 * `aggregateDaily` との違いは、日付とタイムゾーンを**引数で受け取る**こと。
 * これにより集計時の端末タイムゾーンに依存しなくなる。
 *
 * @param dated 対象期間の区間（収集時の日付情報つき）。
 * @param screenOff 対象期間の画面 OFF / ロック期間。
 */
fun aggregateDailyDated(
    dated: List<DatedInterval>,
    screenOff: List<ScreenOffPeriod> = emptyList()
): List<DailyAggregate> {
    val totals = mutableMapOf<String, MutableMap<String, Long>>()
    val counts = mutableMapOf<String, MutableMap<String, Int>>()

    for (d in dated) {
        val interval = d.interval
        val end = interval.endTime ?: continue
        if (end <= interval.startTime) continue

        // 収集時のタイムゾーン。壊れていたらシステム既定へフォールバック。
        val zone = runCatching { ZoneId.of(d.zoneId) }.getOrElse { ZoneId.systemDefault() }
        val baseDate: LocalDate = runCatching { LocalDate.parse(d.localDate) }.getOrElse {
            Instant.ofEpochMilli(interval.startTime).atZone(zone).toLocalDate()
        }

        // 画面 OFF を差し引く（1区間が複数に分かれることがある）。
        val segments = subtractScreenOff(listOf(interval), screenOff)

        for (segment in segments) {
            val segmentEnd = segment.endTime ?: continue
            if (segmentEnd <= segment.startTime) continue

            // 日付境界をまたぐ区間を各日に按分する。
            // 日付は「基準日 + 何日経過したか」で求めるため、
            // 集計時のタイムゾーンには依存しない。
            var cursor = segment.startTime
            while (cursor < segmentEnd) {
                val date = dateFor(cursor, baseDate, zone)
                val nextMidnight = date.plusDays(1).atStartOfDay(zone)
                    .toInstant().toEpochMilli()
                val partEnd = minOf(segmentEnd, nextMidnight)
                if (partEnd <= cursor) break // 念のための無限ループ防止

                val key = date.toString()
                totals.getOrPut(key) { mutableMapOf() }
                    .merge(segment.packageName, partEnd - cursor) { a, b -> a + b }
                counts.getOrPut(key) { mutableMapOf() }
                    .merge(segment.packageName, 1) { a, b -> a + b }

                cursor = partEnd
            }
        }
    }

    return totals.flatMap { (date, perPackage) ->
        perPackage.map { (pkg, total) ->
            DailyAggregate(
                date = date,
                packageName = pkg,
                totalMillis = total,
                launchCount = counts[date]?.get(pkg) ?: 0
            )
        }
    }
}

/**
 * `at` が属するローカル日付を、基準日から数えて求める。
 *
 * 集計時のタイムゾーンではなく、区間を収集したときのゾーンで
 * 日境界を判定する。夏時間のある地域でも正しく動くよう、
 * 「ゾーンでの翌日 0:00」を1日ずつ進めて判定する。
 */
private fun dateFor(at: Long, baseDate: LocalDate, zone: ZoneId): LocalDate {
    var date = baseDate
    // 通常は1〜2日で抜ける。異常データでも止まるよう上限を設ける。
    repeat(MAX_DAY_WALK) {
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (at < nextMidnight) return date
        date = date.plusDays(1)
    }
    return date
}

/** 日付探索の上限。1区間がこれを超えて続くことは実用上ない。 */
private const val MAX_DAY_WALK = 400

/**
 * 集計すべき日単位の範囲を求める。
 *
 * `rebuildDailySummaries` が「窓」ではなく「日全体」を集計するために使う。
 *
 * なぜ必要か: 日次集計は日単位で `DELETE → INSERT` する。
 * 窓の範囲だけで集計して日全体を置換すると、
 * その日の他の時間帯のデータが毎回消える。
 */
fun fullDayRange(
    from: Long,
    to: Long,
    zone: ZoneId = ZoneId.systemDefault()
): LongRange {
    val start = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()
    // to が属する日の翌日 0:00 まで（その日全体を含める）。
    val end = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start until maxOf(end, start)
}
