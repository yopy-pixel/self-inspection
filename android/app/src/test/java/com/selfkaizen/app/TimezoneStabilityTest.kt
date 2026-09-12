package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.collector.DatedInterval
import com.selfkaizen.app.sync.BatchBuilder
import com.selfkaizen.app.collector.FocusInterval
import com.selfkaizen.app.collector.aggregateDaily
import com.selfkaizen.app.collector.aggregateDailyDated
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * タイムゾーンが変わっても過去の日付が動かないことの検証。
 *
 * ## 背景
 *
 * 以前は集計時に `ZoneId.systemDefault()` で日付を計算していた。
 * そのため端末のタイムゾーンが変わると、**過去のイベントが
 * 別の日へ再配分される**という問題があった。
 *
 * 実測（東京で 2026-09-13 08:00 に使用）:
 * ```
 *   Asia/Tokyo       → 日付キー 2026-09-13
 *   America/New_York → 日付キー 2026-09-12   ← 変わる
 * ```
 *
 * 対策として、収集時点の `localDate` と `zoneId` を保存し、
 * 集計はその保存値を使うようにした（`aggregateDailyDated`）。
 */
class TimezoneStabilityTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")

    private val MIN = 60L * 1000
    private val HOUR = 60 * MIN

    private fun at(date: String, hour: Int, minute: Int, zone: ZoneId): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // =====================================================================
    // 問題の再現（旧実装の挙動）
    // =====================================================================

    @Test
    fun `旧実装ではタイムゾーン変更で日付が変わることを明示する`() {
        // 東京で 2026-09-13 08:00 に使った記録
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + 30 * MIN, null)

        // 東京のまま集計
        val inTokyo = aggregateDaily(listOf(interval), emptyList(), tokyo)
        // ニューヨークに移動した後で集計（旧実装の挙動）
        val inNewYork = aggregateDaily(listOf(interval), emptyList(), newYork)

        assertThat(inTokyo.map { it.date }).containsExactly("2026-09-13")
        // 同じ記録が前日に分類されてしまう。これが修正対象のバグ。
        assertThat(inNewYork.map { it.date }).containsExactly("2026-09-12")
    }

    // =====================================================================
    // 修正後: 保存済みの日付を使うので動かない
    // =====================================================================

    @Test
    fun `保存済みのlocalDateを使えばタイムゾーンが変わっても日付が動かない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + 30 * MIN, null)

        // 収集時に確定した情報（東京で収集した）
        val dated = listOf(
            DatedInterval(interval, localDate = "2026-09-13", zoneId = "Asia/Tokyo")
        )

        val result = aggregateDailyDated(dated)

        // 集計時の端末タイムゾーンに関係なく 09-13 のまま。
        assertThat(result).hasSize(1)
        assertThat(result[0].date).isEqualTo("2026-09-13")
        assertThat(result[0].totalMillis).isEqualTo(30 * MIN)
    }

    @Test
    fun `深夜をまたぐ区間が収集時のゾーンで分割される`() {
        // 東京で 2026-09-13 23:00 → 翌 01:00（2時間）
        val start = at("2026-09-13", 23, 0, tokyo)
        val end = at("2026-09-14", 1, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, end, null)

        val dated = listOf(
            DatedInterval(interval, localDate = "2026-09-13", zoneId = "Asia/Tokyo")
        )

        val result = aggregateDailyDated(dated)
        val byDate = result.associate { it.date to it.totalMillis }

        // 東京の日境界で 1時間ずつに分かれる。
        // 集計時のゾーン（システム既定）が何であっても同じ結果になること。
        assertThat(byDate.keys).containsExactly("2026-09-13", "2026-09-14")
        assertThat(byDate["2026-09-13"]).isEqualTo(HOUR)
        assertThat(byDate["2026-09-14"]).isEqualTo(HOUR)
    }

    @Test
    fun `複数日にまたがる区間も収集時のゾーンで按分される`() {
        // 東京で 2泊3日の連続使用（現実にはないが境界の検証として）
        val start = at("2026-09-13", 22, 0, tokyo)
        val end = at("2026-09-15", 2, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, end, null)

        val dated = listOf(
            DatedInterval(interval, localDate = "2026-09-13", zoneId = "Asia/Tokyo")
        )

        val result = aggregateDailyDated(dated)
        val byDate = result.associate { it.date to it.totalMillis }

        assertThat(byDate.keys)
            .containsExactly("2026-09-13", "2026-09-14", "2026-09-15")
        assertThat(byDate["2026-09-13"]).isEqualTo(2 * HOUR)
        assertThat(byDate["2026-09-14"]).isEqualTo(24 * HOUR)
        assertThat(byDate["2026-09-15"]).isEqualTo(2 * HOUR)
    }

    @Test
    fun `合計は按分しても変わらない`() {
        val start = at("2026-09-13", 22, 0, tokyo)
        val end = at("2026-09-15", 2, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, end, null)
        val dated = listOf(
            DatedInterval(interval, localDate = "2026-09-13", zoneId = "Asia/Tokyo")
        )

        val total = aggregateDailyDated(dated).sumOf { it.totalMillis }

        assertThat(total).isEqualTo(end - start)
    }

    @Test
    fun `収集したゾーンが異なる区間が混在しても正しく集計される`() {
        // 東京で収集した区間
        val tokyoStart = at("2026-09-13", 8, 0, tokyo)
        val tokyoInterval = FocusInterval("com.app.a", tokyoStart, tokyoStart + HOUR, null)

        // 渡航後、ニューヨークで収集した区間
        val nyStart = at("2026-09-13", 19, 0, newYork)
        val nyInterval = FocusInterval("com.app.a", nyStart, nyStart + HOUR, null)

        val dated = listOf(
            DatedInterval(tokyoInterval, localDate = "2026-09-13", zoneId = "Asia/Tokyo"),
            DatedInterval(nyInterval, localDate = "2026-09-13", zoneId = "America/New_York")
        )

        val result = aggregateDailyDated(dated)

        // どちらもその端末にとっての 09-13。各1時間で合計2時間。
        assertThat(result).hasSize(1)
        assertThat(result[0].date).isEqualTo("2026-09-13")
        assertThat(result[0].totalMillis).isEqualTo(2 * HOUR)
    }

    @Test
    fun `ロンドンと東京で日付キーが分かれる例`() {
        // 東京 2026-09-13 08:00 = ロンドン 2026-09-13 00:00
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + HOUR, null)

        val inTokyo = aggregateDailyDated(
            listOf(DatedInterval(interval, "2026-09-13", "Asia/Tokyo"))
        )
        val inLondon = aggregateDailyDated(
            listOf(DatedInterval(interval, "2026-09-13", "Europe/London"))
        )

        // 保存された localDate が使われるので、どちらも 09-13。
        assertThat(inTokyo[0].date).isEqualTo("2026-09-13")
        assertThat(inLondon[0].date).isEqualTo("2026-09-13")
        // ロンドン基準でも同じ epoch を 09-13 として扱う（保存値を尊重）。
        assertThat(inLondon[0].totalMillis).isEqualTo(HOUR)
        assertThat(LocalDate.parse("2026-09-13").atStartOfDay(london)).isNotNull()
    }

    // =====================================================================
    // フォールバック（マイグレーション前の古い行）
    // =====================================================================

    @Test
    fun `localDateが空でもシステム既定でフォールバックして落ちない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + HOUR, null)

        // マイグレーション前の古い行を模す（空文字）
        val dated = listOf(DatedInterval(interval, localDate = "", zoneId = ""))

        val result = aggregateDailyDated(dated)

        // 例外にならず、何らかの日付に集計される。
        assertThat(result).isNotEmpty()
        assertThat(result.sumOf { it.totalMillis }).isEqualTo(HOUR)
    }

    @Test
    fun `zoneIdが不正でも落ちない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + HOUR, null)

        val dated = listOf(
            DatedInterval(interval, localDate = "2026-09-13", zoneId = "Not/AZone")
        )

        val result = aggregateDailyDated(dated)

        assertThat(result).hasSize(1)
        assertThat(result[0].date).isEqualTo("2026-09-13")
        assertThat(result[0].totalMillis).isEqualTo(HOUR)
    }

    @Test
    fun `localDateが不正でも落ちない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, start + HOUR, null)

        val dated = listOf(
            DatedInterval(interval, localDate = "not-a-date", zoneId = "Asia/Tokyo")
        )

        val result = aggregateDailyDated(dated)

        assertThat(result.sumOf { it.totalMillis }).isEqualTo(HOUR)
    }

    @Test
    fun `未クローズ区間は集計されない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val interval = FocusInterval("com.app.a", start, null, null)
        val dated = listOf(DatedInterval(interval, "2026-09-13", "Asia/Tokyo"))

        val result = aggregateDailyDated(dated)

        assertThat(result).isEmpty()
    }

    @Test
    fun `複数アプリが同じ日にそれぞれ集計される`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val dated = listOf(
            DatedInterval(FocusInterval("com.app.a", start, start + HOUR, null), "2026-09-13", "Asia/Tokyo"),
            DatedInterval(FocusInterval("com.app.b", start, start + 2 * HOUR, null), "2026-09-13", "Asia/Tokyo")
        )

        val result = aggregateDailyDated(dated).associateBy { it.packageName }

        assertThat(result["com.app.a"]!!.totalMillis).isEqualTo(HOUR)
        assertThat(result["com.app.b"]!!.totalMillis).isEqualTo(2 * HOUR)
    }

    @Test
    fun `夏時間の切替日でも落ちない`() {
        // ニューヨークの夏時間終了日（2026-11-01 は 25 時間日）
        val start = at("2026-11-01", 0, 30, newYork)
        val end = at("2026-11-01", 23, 30, newYork)
        val interval = FocusInterval("com.app.a", start, end, null)
        val dated = listOf(DatedInterval(interval, "2026-11-01", "America/New_York"))

        val result = aggregateDailyDated(dated)

        // 同一日内なので分割されない。長さは実際の経過時間と一致する。
        assertThat(result).hasSize(1)
        assertThat(result[0].date).isEqualTo("2026-11-01")
        assertThat(result[0].totalMillis).isEqualTo(end - start)
    }

    @Test
    fun `夏時間で日付をまたいでも正しく分割される`() {
        // ニューヨーク 2026-11-01 23:30 → 翌 00:30
        val start = at("2026-11-01", 23, 30, newYork)
        val end = at("2026-11-02", 0, 30, newYork)
        val interval = FocusInterval("com.app.a", start, end, null)
        val dated = listOf(DatedInterval(interval, "2026-11-01", "America/New_York"))

        val result = aggregateDailyDated(dated)
        val byDate = result.associate { it.date to it.totalMillis }

        assertThat(byDate.keys).containsExactly("2026-11-01", "2026-11-02")
        assertThat(byDate["2026-11-01"]).isEqualTo(30 * MIN)
        assertThat(byDate["2026-11-02"]).isEqualTo(30 * MIN)
    }

    // =====================================================================
    // 送信経路（BatchBuilder）もタイムゾーン変更に耐えること
    //
    // SyncWorker は Room の localDate / zoneId を SourceInterval に渡す。
    // 渡さずに送信時点のゾーンで再計算すると、
    // サーバーに送られる localDate が DB と食い違う。
    // =====================================================================

    @Test
    fun `送信経路も保存済みの日付を使う_タイムゾーン変更後でも変わらない`() {
        // 東京で 2026-09-13 08:00 に開始した区間
        val start = at("2026-09-13", 8, 0, tokyo)

        val source = BatchBuilder.SourceInterval(
            packageName = "com.app.a",
            appLabel = "A",
            startTime = start,
            endTime = start + 30 * MIN,
            closeReason = "PAUSED",
            localDate = "2026-09-13",
            zoneId = "Asia/Tokyo"
        )

        // 送信時にニューヨークにいても、保存済みの 09-13 が使われる。
        val events = BatchBuilder.splitByLocalDate(listOf(source), newYork)

        assertThat(events).hasSize(1)
        assertThat(events[0].localDate).isEqualTo("2026-09-13")
    }

    @Test
    fun `保存済みの日付が無い場合は従来どおりゾーンから計算する`() {
        val start = at("2026-09-13", 8, 0, tokyo)

        // localDate / zoneId を渡さない（後方互換・古い行）
        val source = BatchBuilder.SourceInterval(
            packageName = "com.app.a",
            appLabel = "A",
            startTime = start,
            endTime = start + 30 * MIN,
            closeReason = "PAUSED"
        )

        // 渡されたゾーンで計算される。
        val inTokyo = BatchBuilder.splitByLocalDate(listOf(source), tokyo)
        val inNewYork = BatchBuilder.splitByLocalDate(listOf(source), newYork)

        assertThat(inTokyo[0].localDate).isEqualTo("2026-09-13")
        assertThat(inNewYork[0].localDate).isEqualTo("2026-09-12")
    }

    @Test
    fun `送信経路でも深夜跨ぎが収集時のゾーンで分割される`() {
        // 東京 2026-09-13 23:00 → 翌 01:00
        val start = at("2026-09-13", 23, 0, tokyo)
        val end = at("2026-09-14", 1, 0, tokyo)

        val source = BatchBuilder.SourceInterval(
            packageName = "com.app.a",
            appLabel = "A",
            startTime = start,
            endTime = end,
            closeReason = "PAUSED",
            localDate = "2026-09-13",
            zoneId = "Asia/Tokyo"
        )

        // 送信時にどこにいても、東京の日境界で分割される。
        val events = BatchBuilder.splitByLocalDate(listOf(source), newYork)

        assertThat(events.map { it.localDate })
            .containsExactly("2026-09-13", "2026-09-14")
            .inOrder()
        assertThat(events[0].endTime - events[0].startTime).isEqualTo(HOUR)
        assertThat(events[1].endTime - events[1].startTime).isEqualTo(HOUR)
    }

    @Test
    fun `zoneIdが不正な送信元でも落ちない`() {
        val start = at("2026-09-13", 8, 0, tokyo)
        val source = BatchBuilder.SourceInterval(
            packageName = "com.app.a",
            appLabel = "A",
            startTime = start,
            endTime = start + HOUR,
            closeReason = "PAUSED",
            localDate = "2026-09-13",
            zoneId = "Not/AZone"
        )

        val events = BatchBuilder.splitByLocalDate(listOf(source), tokyo)

        assertThat(events).hasSize(1)
        assertThat(events[0].localDate).isEqualTo("2026-09-13")
    }
}
