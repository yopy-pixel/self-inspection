package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.ui.DurationFormat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 画面表記の検証。
 *
 * `design/SPEC.md` §1 の語彙方針に従っていることを確認する:
 * 数字と単位記号（h / m）だけで表現し、文章を増やさない。
 */
class DurationFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---------------- リング内（long / ringLabel） ----------------

    @Test
    fun `時間と分を h と m で表す`() {
        assertThat(DurationFormat.long(3 * 3600_000L + 42 * 60_000L)).isEqualTo("3h 42m")
        assertThat(DurationFormat.ringLabel(3 * 3600_000L + 42 * 60_000L)).isEqualTo("3h 42m")
    }

    @Test
    fun `1時間未満は分だけ`() {
        assertThat(DurationFormat.long(18 * 60_000L)).isEqualTo("18m")
        assertThat(DurationFormat.long(0L)).isEqualTo("0m")
    }

    @Test
    fun `ちょうどの時間は 0分 を付ける`() {
        // long は2桁ゼロ埋め、ringLabel はゼロ埋めなし（SPEC のモックに合わせる）
        assertThat(DurationFormat.long(4 * 3600_000L)).isEqualTo("4h 00m")
        assertThat(DurationFormat.ringLabel(4 * 3600_000L)).isEqualTo("4h 0m")
    }

    @Test
    fun `負の値は0として扱う`() {
        assertThat(DurationFormat.long(-5_000L)).isEqualTo("0m")
        assertThat(DurationFormat.compact(-5_000L)).isEqualTo("0m")
    }

    // ---------------- 省スペース（compact） ----------------

    @Test
    fun `ランキング用は詰めて表す`() {
        assertThat(DurationFormat.compact(1 * 3600_000L + 12 * 60_000L)).isEqualTo("1h12")
        assertThat(DurationFormat.compact(48 * 60_000L)).isEqualTo("48m")
        assertThat(DurationFormat.compact(2 * 3600_000L)).isEqualTo("2h")
    }

    @Test
    fun `compactは分を2桁に揃える`() {
        // 1h5 ではなく 1h05（桁揺れを防ぐ）
        assertThat(DurationFormat.compact(1 * 3600_000L + 5 * 60_000L)).isEqualTo("1h05")
    }

    // ---------------- 上限（limit） ----------------

    @Test
    fun `上限は分を必ず2桁で出す`() {
        assertThat(DurationFormat.limit(4 * 3600_000L)).isEqualTo("4h 00m")
        assertThat(DurationFormat.limit(4 * 3600_000L + 30 * 60_000L)).isEqualTo("4h 30m")
        assertThat(DurationFormat.limit(30 * 60_000L)).isEqualTo("0h 30m")
    }

    // ---------------- 収集時刻（clock / ago） ----------------

    @Test
    fun `同じ日なら時刻だけ`() {
        val t = at("2026-09-14", 14, 5)
        assertThat(DurationFormat.clock(t, at("2026-09-14", 20, 0), zone)).isEqualTo("14:05")
    }

    @Test
    fun `日をまたぐなら日付を付ける`() {
        val t = at("2026-09-13", 23, 50)
        assertThat(DurationFormat.clock(t, at("2026-09-14", 1, 0), zone)).isEqualTo("9/13 23:50")
    }

    @Test
    fun `経過時間を短く表す`() {
        val now = at("2026-09-14", 12, 0)
        assertThat(DurationFormat.ago(now - 30_000L, now)).isEqualTo("just now")
        assertThat(DurationFormat.ago(now - 30 * 60_000L, now)).isEqualTo("30m ago")
        assertThat(DurationFormat.ago(now - 5 * 3600_000L, now)).isEqualTo("5h ago")
        assertThat(DurationFormat.ago(now - 3 * 24 * 3600_000L, now)).isEqualTo("3d ago")
    }

    @Test
    fun `未来の時刻はjust nowになる`() {
        val now = at("2026-09-14", 12, 0)
        assertThat(DurationFormat.ago(now + 60_000L, now)).isEqualTo("just now")
    }

    // ---------------- 曜日 ----------------

    @Test
    fun `曜日は1文字で7列に収める`() {
        assertThat(DurationFormat.dayInitial(DayOfWeek.MONDAY)).isEqualTo("M")
        assertThat(DurationFormat.dayInitial(DayOfWeek.TUESDAY)).isEqualTo("T")
        assertThat(DurationFormat.dayInitial(DayOfWeek.WEDNESDAY)).isEqualTo("W")
        assertThat(DurationFormat.dayInitial(DayOfWeek.THURSDAY)).isEqualTo("T")
        assertThat(DurationFormat.dayInitial(DayOfWeek.FRIDAY)).isEqualTo("F")
        assertThat(DurationFormat.dayInitial(DayOfWeek.SATURDAY)).isEqualTo("S")
        assertThat(DurationFormat.dayInitial(DayOfWeek.SUNDAY)).isEqualTo("S")
    }
}
