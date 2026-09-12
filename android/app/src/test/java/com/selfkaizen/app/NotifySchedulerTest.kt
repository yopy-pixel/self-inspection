package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.notify.NotifyScheduler
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 通知時刻の計算の検証。
 *
 * **時刻を間違えると通知が来なくなる**（あるいは1日中来なくなる）ため、
 * 境界をテストで固定する。`now` と `zone` を引数にしているのはこのため。
 *
 * 夏時間のある地域を必ず含める。日本には無いが、
 * `LocalDate.atTime(...).atZone(zone)` を使わずに
 * 「24時間を足す」実装にすると夏時間で1時間ずれる。
 */
class NotifySchedulerTest {

    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, text: String): Long =
        ZonedDateTime.parse(text).withZoneSameInstant(zone).toInstant().toEpochMilli()

    private fun localHour(epochMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    private fun localDate(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    @Test
    fun `時刻前なら当日のその時刻になる`() {
        val now = at(utc, "2024-01-15T08:00:00Z")
        val next = NotifyScheduler.nextTrigger(now, utc, 9)

        assertThat(localDate(next, utc)).isEqualTo("2024-01-15")
        assertThat(localHour(next, utc)).isEqualTo(9)
    }

    @Test
    fun `時刻を過ぎていれば翌日になる`() {
        val now = at(utc, "2024-01-15T09:00:01Z")
        val next = NotifyScheduler.nextTrigger(now, utc, 9)

        assertThat(localDate(next, utc)).isEqualTo("2024-01-16")
        assertThat(localHour(next, utc)).isEqualTo(9)
    }

    @Test
    fun `ちょうどその時刻なら翌日にする`() {
        // そのアラームは既に過ぎている。同じ瞬間を返すと
        // 永久に発火しない/即発火を繰り返すことになる。
        val now = at(utc, "2024-01-15T09:00:00Z")
        val next = NotifyScheduler.nextTrigger(now, utc, 9)

        assertThat(localDate(next, utc)).isEqualTo("2024-01-16")
    }

    @Test
    fun `夜遅くなら翌朝になる`() {
        val now = at(utc, "2024-01-15T23:30:00Z")
        val next = NotifyScheduler.nextTrigger(now, utc, 9)

        assertThat(localDate(next, utc)).isEqualTo("2024-01-16")
        assertThat(localHour(next, utc)).isEqualTo(9)
    }

    @Test
    fun `朝と夜の2回で12時間間隔になる`() {
        val now = at(utc, "2024-01-15T00:00:00Z")
        val morning = NotifyScheduler.nextTrigger(now, utc, 9)
        val evening = NotifyScheduler.nextTrigger(now, utc, 21)

        assertThat(localHour(morning, utc)).isEqualTo(9)
        assertThat(localHour(evening, utc)).isEqualTo(21)
        assertThat(evening - morning).isEqualTo(12 * 60 * 60 * 1000L)
    }

    // ---------------- 夏時間 ----------------

    @Test
    fun `夏時間の切り替えをまたいでも現地時刻は9時になる`() {
        // 米国東部は 2024-03-10 02:00 に夏時間へ入る（1時間進む）。
        val zone = ZoneId.of("America/New_York")
        val now = at(zone, "2024-03-09T20:00:00-05:00")
        val next = NotifyScheduler.nextTrigger(now, zone, 9)

        assertThat(localDate(next, zone)).isEqualTo("2024-03-10")
        assertThat(localHour(next, zone)).isEqualTo(9)
    }

    @Test
    fun `夏時間が終わる日も現地時刻は9時になる`() {
        // 2024-11-03 02:00 に標準時へ戻る（1時間戻る）。
        val zone = ZoneId.of("America/New_York")
        val now = at(zone, "2024-11-02T20:00:00-04:00")
        val next = NotifyScheduler.nextTrigger(now, zone, 9)

        assertThat(localDate(next, zone)).isEqualTo("2024-11-03")
        assertThat(localHour(next, zone)).isEqualTo(9)
    }

    @Test
    fun `日本時間でも現地時刻は9時になる`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = at(zone, "2024-01-15T08:00:00+09:00")
        val next = NotifyScheduler.nextTrigger(now, zone, 9)

        assertThat(localDate(next, zone)).isEqualTo("2024-01-15")
        assertThat(localHour(next, zone)).isEqualTo(9)
    }

    // ---------------- 設定値 ----------------

    @Test
    fun `通知は12時間間隔の2回である`() {
        // 「12時間に1回でいい」という要件を数値で固定する。
        assertThat(NotifyScheduler.HOURS.toList()).containsExactly(9, 21).inOrder()
        val gap = NotifyScheduler.HOURS[1] - NotifyScheduler.HOURS[0]
        assertThat(gap).isEqualTo(12)
    }

    // ---------------- 張り直しの連鎖 ----------------
    //
    // 1回だけのアラームを都度張り直す実装なので、
    // 「張り直しの連鎖」そのものが壊れていないことを固定する。

    @Test
    fun `次に来る方の時刻が選ばれる`() {
        val morning = NotifyScheduler.nextTrigger(
            at(utc, "2024-01-15T08:00:00Z"), utc, NotifyScheduler.HOURS
        )
        assertThat(localDate(morning, utc)).isEqualTo("2024-01-15")
        assertThat(localHour(morning, utc)).isEqualTo(9)

        // 朝を過ぎていれば夜が選ばれる（朝を翌日に飛ばさない）。
        val evening = NotifyScheduler.nextTrigger(
            at(utc, "2024-01-15T10:00:00Z"), utc, NotifyScheduler.HOURS
        )
        assertThat(localDate(evening, utc)).isEqualTo("2024-01-15")
        assertThat(localHour(evening, utc)).isEqualTo(21)
    }

    @Test
    fun `張り直しを繰り返すと朝と夜が交互に来る`() {
        var now = at(utc, "2024-01-15T00:00:00Z")
        val hours = mutableListOf<Int>()
        repeat(4) {
            now = NotifyScheduler.nextTrigger(now, utc, NotifyScheduler.HOURS)
            hours += localHour(now, utc)
        }
        assertThat(hours).containsExactly(9, 21, 9, 21).inOrder()
    }

    @Test
    fun `張り直しの間隔はちょうど12時間`() {
        var now = at(utc, "2024-01-15T00:00:00Z")
        val triggers = mutableListOf<Long>()
        repeat(4) {
            now = NotifyScheduler.nextTrigger(now, utc, NotifyScheduler.HOURS)
            triggers += now
        }
        triggers.zipWithNext { a, b ->
            assertThat(b - a).isEqualTo(12 * 60 * 60 * 1000L)
        }
    }

    @Test
    fun `遅れて発火しても次は未来になる`() {
        // アラームは遅れて発火しうる。遅れた時刻から張り直しても
        // 「過去の時刻」を返してはいけない（即発火の無限ループになる）。
        val late = at(utc, "2024-01-15T09:37:00Z")
        val next = NotifyScheduler.nextTrigger(late, utc, NotifyScheduler.HOURS)
        assertThat(next).isGreaterThan(late)
        assertThat(localHour(next, utc)).isEqualTo(21)
    }

    @Test
    fun `夜に遅れて発火したら翌朝になる`() {
        val late = at(utc, "2024-01-15T23:50:00Z")
        val next = NotifyScheduler.nextTrigger(late, utc, NotifyScheduler.HOURS)
        assertThat(next).isGreaterThan(late)
        assertThat(localDate(next, utc)).isEqualTo("2024-01-16")
        assertThat(localHour(next, utc)).isEqualTo(9)
    }
}
