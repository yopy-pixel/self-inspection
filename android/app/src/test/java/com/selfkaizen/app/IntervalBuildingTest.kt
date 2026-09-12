package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.collector.FocusInterval
import com.selfkaizen.app.collector.RawEvent
import com.selfkaizen.app.collector.ScreenOffPeriod
import com.selfkaizen.app.collector.SeedOpenInterval
import com.selfkaizen.app.collector.UsageEventType
import com.selfkaizen.app.collector.aggregateDaily
import com.selfkaizen.app.collector.buildIntervals
import com.selfkaizen.app.collector.fullDayRange
import com.selfkaizen.app.collector.subtractScreenOff
import org.junit.Test

/**
 * 定期収集（Phase 3）のロジック検証。
 *
 * ここに含まれるテストは、実データ検証で実際に見つかった2件のバグの
 * 回帰テストである。修正前のコードでは失敗する。
 */
class IntervalBuildingTest {

    private val MIN = 60L * 1000
    private val HOUR = 60 * MIN

    private fun resumed(pkg: String, at: Long, cls: String? = null) =
        RawEvent(UsageEventType.ACTIVITY_RESUMED, pkg, at, cls)

    private fun paused(pkg: String, at: Long, cls: String? = null) =
        RawEvent(UsageEventType.ACTIVITY_PAUSED, pkg, at, cls)

    // =====================================================================
    // バグ1: 未クローズ区間を引き継がないと、長時間使用の記録が消える
    // =====================================================================

    @Test
    fun `引き継ぎが無いとPAUSEDが効かず区間が閉じない`() {
        // アプリの RESUMED は今回の読み取り範囲より前にある。
        // 今回読めるのは PAUSED だけ、という状況。
        val events = listOf(paused("com.app.a", 45 * MIN))

        val built = buildIntervals(events, seedOpen = null, now = 50 * MIN)

        // 引き継ぎが無いと、ビルダーは前面アプリを知らないので何も生成しない。
        // → DB の未クローズ行は閉じられず、12時間後の掃除で消える。
        assertThat(built.intervals).isEmpty()
    }

    @Test
    fun `引き継ぎがあればPAUSEDで正しく閉じる_バグ1の回帰テスト`() {
        // 前回の収集で com.app.a が使用中（0分開始）。
        // 今回は 45分の PAUSED だけが見える。
        val events = listOf(paused("com.app.a", 45 * MIN))

        val built = buildIntervals(
            events,
            seedOpen = SeedOpenInterval("com.app.a", 0),
            now = 50 * MIN
        )

        // 開始時刻が引き継がれ、PAUSED で正しく閉じる。
        assertThat(built.intervals).hasSize(1)
        assertThat(built.intervals[0].packageName).isEqualTo("com.app.a")
        assertThat(built.intervals[0].startTime).isEqualTo(0)
        assertThat(built.intervals[0].endTime).isEqualTo(45 * MIN)
        assertThat(built.intervals[0].durationMillis).isEqualTo(45 * MIN)
    }

    @Test
    fun `長時間の使用が引き継ぎで1区間として維持される`() {
        // 3回の収集をまたいで使い続けるケース。
        val seed = SeedOpenInterval("com.app.a", 0)

        // 1回目: まだ使用中
        val first = buildIntervals(emptyList(), seed, now = 15 * MIN)
        assertThat(first.intervals).hasSize(1)
        assertThat(first.intervals[0].startTime).isEqualTo(0)

        // 2回目: まだ使用中
        val second = buildIntervals(emptyList(), seed, now = 30 * MIN)
        assertThat(second.intervals[0].startTime).isEqualTo(0)

        // 3回目: ようやく閉じた。開始時刻は 0 のまま。
        val third = buildIntervals(listOf(paused("com.app.a", 60 * MIN)), seed, now = 65 * MIN)
        assertThat(third.intervals[0].startTime).isEqualTo(0)
        assertThat(third.intervals[0].durationMillis).isEqualTo(60 * MIN)
    }

    @Test
    fun `引き継いだ区間が別アプリの起動で暗黙に閉じる`() {
        val events = listOf(
            resumed("com.app.b", 20 * MIN),
            paused("com.app.b", 30 * MIN)
        )

        val built = buildIntervals(
            events,
            seedOpen = SeedOpenInterval("com.app.a", 0),
            now = 35 * MIN
        )

        // com.app.a は com.app.b の RESUMED 時点で閉じる。
        assertThat(built.intervals).hasSize(2)
        assertThat(built.intervals[0].packageName).isEqualTo("com.app.a")
        assertThat(built.intervals[0].endTime).isEqualTo(20 * MIN)
        assertThat(built.intervals[1].packageName).isEqualTo("com.app.b")
    }

    // =====================================================================
    // バグ2: 画面 OFF 中の時間を差し引かないと約3000倍に膨らむ
    // =====================================================================

    @Test
    fun `画面OFFを差し引かないと過大計上になることを明示する`() {
        // 0〜60分の区間。うち 10〜50分は画面 OFF。
        val interval = FocusInterval("com.app.a", 0, 60 * MIN, null)
        val screenOff = listOf(ScreenOffPeriod(10 * MIN, 50 * MIN))

        val raw = interval.durationMillis
        val effective = subtractScreenOff(listOf(interval), screenOff)
            .sumOf { it.durationMillis }

        // 生は60分、実効は20分。この差が過大計上そのもの。
        assertThat(raw).isEqualTo(60 * MIN)
        assertThat(effective).isEqualTo(20 * MIN)
    }

    @Test
    fun `画面OFFで区間が分割される_バグ2の回帰テスト`() {
        val interval = FocusInterval("com.app.a", 0, 60 * MIN, null)
        val screenOff = listOf(ScreenOffPeriod(10 * MIN, 50 * MIN))

        val result = subtractScreenOff(listOf(interval), screenOff)

        // 0〜10分 と 50〜60分 の2区間に分かれる。
        assertThat(result).hasSize(2)
        assertThat(result[0].startTime).isEqualTo(0)
        assertThat(result[0].endTime).isEqualTo(10 * MIN)
        assertThat(result[1].startTime).isEqualTo(50 * MIN)
        assertThat(result[1].endTime).isEqualTo(60 * MIN)
    }

    @Test
    fun `複数の画面OFF期間がすべて差し引かれる`() {
        val interval = FocusInterval("com.app.a", 0, 100 * MIN, null)
        val screenOff = listOf(
            ScreenOffPeriod(10 * MIN, 20 * MIN),
            ScreenOffPeriod(30 * MIN, 40 * MIN),
            ScreenOffPeriod(60 * MIN, 90 * MIN)
        )

        val effective = subtractScreenOff(listOf(interval), screenOff)
            .sumOf { it.durationMillis }

        // 100 - (10 + 10 + 30) = 50分
        assertThat(effective).isEqualTo(50 * MIN)
    }

    @Test
    fun `区間の外側の画面OFFは影響しない`() {
        val interval = FocusInterval("com.app.a", 20 * MIN, 30 * MIN, null)
        val screenOff = listOf(
            ScreenOffPeriod(0, 10 * MIN),      // 完全に前
            ScreenOffPeriod(40 * MIN, 50 * MIN) // 完全に後
        )

        val result = subtractScreenOff(listOf(interval), screenOff)

        assertThat(result).hasSize(1)
        assertThat(result[0].durationMillis).isEqualTo(10 * MIN)
    }

    @Test
    fun `区間を完全に覆う画面OFFは区間を消す`() {
        val interval = FocusInterval("com.app.a", 10 * MIN, 20 * MIN, null)
        val screenOff = listOf(ScreenOffPeriod(0, 60 * MIN))

        val result = subtractScreenOff(listOf(interval), screenOff)

        assertThat(result).isEmpty()
    }

    @Test
    fun `境界が接する画面OFFは区間を削らない`() {
        val interval = FocusInterval("com.app.a", 10 * MIN, 20 * MIN, null)
        // 区間の直前で終わる / 直後から始まる
        val screenOff = listOf(
            ScreenOffPeriod(0, 10 * MIN),
            ScreenOffPeriod(20 * MIN, 30 * MIN)
        )

        val result = subtractScreenOff(listOf(interval), screenOff)

        assertThat(result).hasSize(1)
        assertThat(result[0].durationMillis).isEqualTo(10 * MIN)
    }

    @Test
    fun `画面OFFが無ければ区間はそのまま`() {
        val intervals = listOf(
            FocusInterval("com.app.a", 0, 10 * MIN, null),
            FocusInterval("com.app.b", 10 * MIN, 20 * MIN, null)
        )

        val result = subtractScreenOff(intervals, emptyList())

        assertThat(result).isEqualTo(intervals)
    }

    @Test
    fun `未クローズ区間は差し引きの対象外`() {
        val interval = FocusInterval("com.app.a", 0, null, null)
        val screenOff = listOf(ScreenOffPeriod(10 * MIN, 20 * MIN))

        val result = subtractScreenOff(listOf(interval), screenOff)

        assertThat(result).isEmpty()
    }

    // =====================================================================
    // 画面 OFF 期間の収集
    // =====================================================================

    @Test
    fun `画面OFF期間が構築結果に含まれる`() {
        val events = listOf(
            resumed("com.app.a", 0),
            RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 10 * MIN),
            RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 50 * MIN),
            paused("com.app.a", 60 * MIN)
        )

        val built = buildIntervals(events, seedOpen = null, now = 65 * MIN)

        assertThat(built.screenOffPeriods).hasSize(1)
        assertThat(built.screenOffPeriods[0].startTime).isEqualTo(10 * MIN)
        assertThat(built.screenOffPeriods[0].endTime).isEqualTo(50 * MIN)

        // 区間と画面OFFを合成すると実効20分になる。
        val effective = subtractScreenOff(built.intervals, built.screenOffPeriods)
            .sumOf { it.durationMillis }
        assertThat(effective).isEqualTo(20 * MIN)
    }

    @Test
    fun `冪等性_同じ入力なら同じ結果`() {
        val events = listOf(
            resumed("com.app.a", 0),
            RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 10 * MIN),
            RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 50 * MIN),
            paused("com.app.a", 60 * MIN)
        )
        val seed = SeedOpenInterval("com.app.a", 0)

        val first = buildIntervals(events, seed, now = 65 * MIN)
        val second = buildIntervals(events, seed, now = 65 * MIN)

        assertThat(first.intervals).isEqualTo(second.intervals)
        assertThat(first.screenOffPeriods).isEqualTo(second.screenOffPeriods)
    }

    @Test
    fun `日付をまたぐ画面OFFでも正しく差し引かれる`() {
        // 区間 23:00〜翌01:00、画面OFF 23:30〜翌00:30
        val day = 24 * HOUR
        val interval = FocusInterval("com.app.a", day, day + 2 * HOUR, null)
        val screenOff = listOf(ScreenOffPeriod(day + 30 * MIN, day + 90 * MIN))

        val effective = subtractScreenOff(listOf(interval), screenOff)
            .sumOf { it.durationMillis }

        // 2時間 - 1時間 = 1時間
        assertThat(effective).isEqualTo(HOUR)
    }

    // =====================================================================
    // バグ3: 窓だけで集計して日全体を置換すると、その日のデータが消える
    // =====================================================================

    @Test
    fun `日全体を集計すれば1日のデータが失われない_バグ3の回帰テスト`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val dayStart = java.time.LocalDate.parse("2026-09-12")
            .atStartOfDay(zone).toInstant().toEpochMilli()

        // その日の前半（朝）と後半（夜）に使用がある。
        val morning = FocusInterval("com.app.a", dayStart + 9 * HOUR, dayStart + 10 * HOUR, null)
        val evening = FocusInterval("com.app.a", dayStart + 21 * HOUR, dayStart + 22 * HOUR, null)

        // 日全体を渡せば両方が反映される。
        val all = aggregateDaily(listOf(morning, evening), emptyList(), zone)
        assertThat(all).hasSize(1)
        assertThat(all[0].totalMillis).isEqualTo(2 * HOUR)

        // 窓（夜だけ）を渡すと朝のデータが反映されない。
        // これが「窓で集計して日全体を置換する」バグの正体。
        val windowOnly = aggregateDaily(listOf(evening), emptyList(), zone)
        assertThat(windowOnly[0].totalMillis).isEqualTo(HOUR)
        assertThat(windowOnly[0].totalMillis).isLessThan(all[0].totalMillis)
    }

    @Test
    fun `fullDayRangeが日全体を覆う`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val day = java.time.LocalDate.parse("2026-09-12")
        val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val range = fullDayRange(noon, noon, zone)

        // その日の 0:00 から翌日 0:00 まで。
        assertThat(range.first).isEqualTo(day.atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(range.last + 1).isEqualTo(midnight)
    }

    @Test
    fun `fullDayRangeは複数日にまたがる窓も覆う`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val d1 = java.time.LocalDate.parse("2026-09-12")
        val from = d1.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val to = d1.plusDays(1).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()

        val range = fullDayRange(from, to, zone)

        // 9/12 0:00 〜 9/14 0:00（9/13 全体を含む）。
        assertThat(range.first).isEqualTo(d1.atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(range.last + 1)
            .isEqualTo(d1.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    @Test
    fun `日次集計が画面OFFを差し引く`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val dayStart = java.time.LocalDate.parse("2026-09-12")
            .atStartOfDay(zone).toInstant().toEpochMilli()

        // 10:00〜11:00 の使用、うち 10:20〜10:50 は画面OFF。
        val interval = FocusInterval(
            "com.app.a",
            dayStart + 10 * HOUR,
            dayStart + 11 * HOUR,
            null
        )
        val screenOff = listOf(
            ScreenOffPeriod(dayStart + 10 * HOUR + 20 * MIN, dayStart + 10 * HOUR + 50 * MIN)
        )

        val result = aggregateDaily(listOf(interval), screenOff, zone)

        // 60分 - 30分 = 30分
        assertThat(result).hasSize(1)
        assertThat(result[0].totalMillis).isEqualTo(30 * MIN)
    }

    @Test
    fun `日次集計が日付境界で分割する`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val d1 = java.time.LocalDate.parse("2026-09-12")

        // 23:30 〜 翌 00:30
        val interval = FocusInterval(
            "com.app.a",
            d1.atTime(23, 30).atZone(zone).toInstant().toEpochMilli(),
            d1.plusDays(1).atTime(0, 30).atZone(zone).toInstant().toEpochMilli(),
            null
        )

        val result = aggregateDaily(listOf(interval), emptyList(), zone)

        assertThat(result).hasSize(2)
        val byDate = result.associateBy { it.date }
        assertThat(byDate["2026-09-12"]!!.totalMillis).isEqualTo(30 * MIN)
        assertThat(byDate["2026-09-13"]!!.totalMillis).isEqualTo(30 * MIN)
    }

    @Test
    fun `日次集計で未クローズ区間は除外される`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val interval = FocusInterval("com.app.a", 0, null, null)

        val result = aggregateDaily(listOf(interval), emptyList(), zone)

        assertThat(result).isEmpty()
    }
}
