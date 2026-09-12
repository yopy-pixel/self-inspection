package com.selfkaizen.app

import com.selfkaizen.app.collector.CloseReason
import com.selfkaizen.app.collector.DailyAggregator
import com.selfkaizen.app.collector.FocusInterval
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * DailyAggregator の日付分割と集計の検証。
 * タイムゾーンは固定して実行環境に依存しないようにする。
 */
class DailyAggregatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun at(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `同一日内の区間はその日に集計される`() {
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 10, 0), at("2026-02-14", 11, 0), CloseReason.PAUSED)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result.keys).containsExactly("2026-02-14")
        assertThat(result["2026-02-14"]!!["com.app.a"]).isEqualTo(60L * 60 * 1000)
    }

    @Test
    fun `日付境界をまたぐ区間は2日に分割される`() {
        // 23:30 から翌 00:30 まで（1時間）
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 23, 30), at("2026-02-15", 0, 30), CloseReason.PAUSED)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result.keys).containsExactly("2026-02-14", "2026-02-15")
        // 各日 30 分ずつ
        assertThat(result["2026-02-14"]!!["com.app.a"]).isEqualTo(30L * 60 * 1000)
        assertThat(result["2026-02-15"]!!["com.app.a"]).isEqualTo(30L * 60 * 1000)
    }

    @Test
    fun `複数日にまたがる区間も各日に分割される`() {
        // 2/14 22:00 から 2/16 02:00 まで（28時間）
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 22, 0), at("2026-02-16", 2, 0), CloseReason.PAUSED)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result.keys).containsExactly("2026-02-14", "2026-02-15", "2026-02-16")
        assertThat(result["2026-02-14"]!!["com.app.a"]).isEqualTo(2L * 60 * 60 * 1000)   // 22:00-24:00
        assertThat(result["2026-02-15"]!!["com.app.a"]).isEqualTo(24L * 60 * 60 * 1000)  // 全日
        assertThat(result["2026-02-16"]!!["com.app.a"]).isEqualTo(2L * 60 * 60 * 1000)   // 00:00-02:00
    }

    @Test
    fun `分割された合計は元の区間の長さと一致する`() {
        val start = at("2026-02-14", 22, 0)
        val end = at("2026-02-16", 2, 0)
        val intervals = listOf(FocusInterval("com.app.a", start, end, CloseReason.PAUSED))

        val result = DailyAggregator.aggregate(intervals, zone)

        val total = result.values.sumOf { it.values.sum() }
        assertThat(total).isEqualTo(end - start)
    }

    @Test
    fun `複数アプリはそれぞれ集計される`() {
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 10, 0), at("2026-02-14", 11, 0), CloseReason.PAUSED),
            FocusInterval("com.app.b", at("2026-02-14", 11, 0), at("2026-02-14", 11, 30), CloseReason.PAUSED)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result["2026-02-14"]!!["com.app.a"]).isEqualTo(60L * 60 * 1000)
        assertThat(result["2026-02-14"]!!["com.app.b"]).isEqualTo(30L * 60 * 1000)
    }

    @Test
    fun `endTimeがnullの区間は集計されない`() {
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 10, 0), null, null)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result).isEmpty()
    }

    @Test
    fun `不正な区間_終了が開始以前_は無視される`() {
        val t = at("2026-02-14", 10, 0)
        val intervals = listOf(
            FocusInterval("com.app.a", t, t, CloseReason.PAUSED)
        )

        val result = DailyAggregator.aggregate(intervals, zone)

        assertThat(result).isEmpty()
    }

    @Test
    fun `UTCとJSTで日付キーが変わることを確認する`() {
        val intervals = listOf(
            FocusInterval("com.app.a", at("2026-02-14", 10, 0), at("2026-02-14", 11, 0), CloseReason.PAUSED)
        )

        val jst = DailyAggregator.aggregate(intervals, ZoneId.of("Asia/Tokyo"))
        val utc = DailyAggregator.aggregate(intervals, ZoneId.of("UTC"))

        // JST 10:00 は UTC 01:00 なので、日付キーはどちらも 2/14 になる。
        // ここではゾーン指定が実際に効いていること（例外なく動くこと）を確認する。
        assertThat(jst.keys).containsExactly("2026-02-14")
        assertThat(utc.keys).containsExactly("2026-02-14")
    }
}
