package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.collector.CloseReason
import com.selfkaizen.app.collector.FocusInterval
import com.selfkaizen.app.rules.RuleEngine
import com.selfkaizen.app.rules.RuleSettings
import com.selfkaizen.app.rules.UsageStatus
import com.selfkaizen.app.rules.ViolationType
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * RuleEngine の意味論検証。
 *
 * **対象は「1日の合計上限」のみ。**
 * 勤務時間帯のルールは 2026-09-14 に廃止された（`RuleEngine` のクラスコメント参照）。
 * アプリは違反を判定せず、使用量を示すだけ。判断は本人が行う。
 *
 * 「接近」は**固定分数**（既定10分）であり、パーセントではない。
 *
 * 日付: 2026-09-14 は月曜、09-13 は日曜。
 */
class RuleEngineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val monday = LocalDate.parse("2026-09-14")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun iv(pkg: String, start: Long, end: Long) =
        FocusInterval(pkg, start, end, CloseReason.PAUSED)

    /** 既定: 上限4時間 / 接近10分。 */
    private val settings = RuleSettings()

    private fun evaluate(
        date: LocalDate = monday,
        intervals: List<FocusInterval>,
        s: RuleSettings = settings
    ) = RuleEngine.evaluate(date, intervals, s, zone)

    // ---------------- 1日の合計上限 ----------------

    @Test
    fun `上限内なら違反ゼロ`() {
        val intervals = listOf(iv("com.a", at("2026-09-14", 20), at("2026-09-14", 22)))

        val violations = evaluate(intervals = intervals)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `上限を超えたら違反1件`() {
        // 19:00-23:59（4時間59分）
        val intervals = listOf(iv("com.a", at("2026-09-14", 19), at("2026-09-14", 23, 59)))

        val violations = evaluate(intervals = intervals)

        val limit = violations.filter { it.type == ViolationType.DAILY_TOTAL_LIMIT }
        assertThat(limit).hasSize(1)
        assertThat(limit[0].thresholdMillis).isEqualTo(240 * 60_000L)
        assertThat(limit[0].actualMillis).isEqualTo(299 * 60_000L)
    }

    @Test
    fun `上限ちょうどは超過ではない`() {
        val intervals = listOf(iv("com.a", at("2026-09-14", 0), at("2026-09-14", 4)))

        val violations = evaluate(intervals = intervals)

        assertThat(violations.filter { it.type == ViolationType.DAILY_TOTAL_LIMIT }).isEmpty()
    }

    @Test
    fun `日をまたぐ区間は当日分だけ計上される`() {
        // 09-13 23:00 〜 09-14 01:00（2時間）。09-14 側は 1時間だけ。
        val intervals = listOf(iv("com.a", at("2026-09-13", 23), at("2026-09-14", 1)))

        // 上限90分: 当日分は60分なので超過しない。
        // 日単位の切り出しが無ければ120分となり超過してしまう。
        assertThat(evaluate(intervals = intervals, s = settings.copy(dailyLimitMinutes = 90)))
            .isEmpty()

        // 上限30分: 当日分60分が超過し、actualMillis も60分であること
        // （切り出しが効いていれば120分にはならない）
        val v = evaluate(intervals = intervals, s = settings.copy(dailyLimitMinutes = 30))
        assertThat(v).hasSize(1)
        assertThat(v[0].actualMillis).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `重複した区間は二重計上しない`() {
        // 意図的に重ねる（通常は起きないが、防御的に検証）
        val intervals = listOf(
            iv("com.a", at("2026-09-14", 20), at("2026-09-14", 22)),
            iv("com.b", at("2026-09-14", 21), at("2026-09-14", 23))
        )

        val total = RuleEngine.totalUsageMillis(intervals)

        // 20:00-23:00 = 3時間（重複を除いた実時間）
        assertThat(total).isEqualTo(3 * 60 * 60_000L)
    }

    @Test
    fun `未確定の区間は集計に含めない`() {
        val intervals = listOf(iv("com.a", at("2026-09-14", 10), at("2026-09-14", 12)))
            .map { it.copy(endTime = null) }

        assertThat(RuleEngine.totalUsageMillis(intervals)).isEqualTo(0L)
        assertThat(evaluate(intervals = intervals)).isEmpty()
    }

    @Test
    fun `0秒の区間は集計に含まれない`() {
        // 同一時刻で開始・終了した区間（長さ0）。
        // unionMillis の `e > s` と clipToWindow の `clippedEnd <= start` で除外される。
        val t = at("2026-09-14", 10)
        val intervals = listOf(iv("com.a", t, t))

        assertThat(RuleEngine.totalUsageMillis(intervals)).isEqualTo(0L)
        assertThat(evaluate(intervals = intervals)).isEmpty()
    }

    @Test
    fun `1ミリ秒でも正の長さなら集計に含まれる`() {
        // 「0秒は除外、0より大きければ含む」という境界の確認。
        val t = at("2026-09-14", 10)
        val intervals = listOf(iv("com.a", t, t + 1))

        assertThat(RuleEngine.totalUsageMillis(intervals)).isEqualTo(1L)
    }

    @Test
    fun `負の長さの区間は0として扱われ集計に含まれない`() {
        // endTime < startTime は durationMillis が 0 に丸められる。
        val t = at("2026-09-14", 10)
        val intervals = listOf(iv("com.a", t, t - 1_000))

        assertThat(RuleEngine.totalUsageMillis(intervals)).isEqualTo(0L)
        assertThat(evaluate(intervals = intervals)).isEmpty()
    }

    // ---------------- 「接近」の状態（UI表示用・違反ではない） ----------------

    @Test
    fun `接近の状態遷移が確定仕様どおり`() {
        val limit = settings.dailyLimitMillis               // 4時間
        val threshold = settings.approachingThresholdMillis // 10分

        // 残り11分 → 上限内（OK）
        assertThat(RuleEngine.status(limit - 11 * 60_000L, settings))
            .isEqualTo(UsageStatus.WITHIN_LIMIT)

        // 残り10分ちょうど → 接近（CLOSE）
        assertThat(RuleEngine.status(limit - threshold, settings))
            .isEqualTo(UsageStatus.APPROACHING)

        // 上限ちょうど（残り0）→ 接近（超過ではない）
        assertThat(RuleEngine.status(limit, settings))
            .isEqualTo(UsageStatus.APPROACHING)

        // 1分超過 → 超過（OVER）
        assertThat(RuleEngine.status(limit + 60_000L, settings))
            .isEqualTo(UsageStatus.EXCEEDED)
    }

    @Test
    fun `接近はパーセントではなく固定分数で判定する`() {
        // 上限を1時間に変えても、接近は「残り10分」で判定される。
        // パーセント判定なら 1時間の10% = 残り6分 となり、残り8分は接近にならない。
        val oneHour = settings.copy(dailyLimitMinutes = 60)
        val eightMinLeft = oneHour.dailyLimitMillis - 8 * 60_000L

        assertThat(RuleEngine.status(eightMinLeft, oneHour))
            .isEqualTo(UsageStatus.APPROACHING)

        // 残り10分より1分多ければ接近ではない
        val elevenMinLeft = oneHour.dailyLimitMillis - 11 * 60_000L
        assertThat(RuleEngine.status(elevenMinLeft, oneHour))
            .isEqualTo(UsageStatus.WITHIN_LIMIT)
    }

    @Test
    fun `上限ルールを無効にすると状態はDISABLED`() {
        assertThat(RuleEngine.status(999 * 60_000L, settings.copy(dailyLimitEnabled = false)))
            .isEqualTo(UsageStatus.DISABLED)
    }

    @Test
    fun `上限ルールを無効にすると違反も出ない`() {
        val intervals = listOf(iv("com.a", at("2026-09-14", 0), at("2026-09-14", 10)))

        val violations = evaluate(intervals = intervals, s = settings.copy(dailyLimitEnabled = false))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `接近は違反として記録されない`() {
        // 残り5分（接近）の状態でも、超過していなければ違反は出ない
        val intervals = listOf(iv("com.a", at("2026-09-14", 0), at("2026-09-14", 3, 55)))

        val violations = evaluate(intervals = intervals)

        assertThat(violations.filter { it.type == ViolationType.DAILY_TOTAL_LIMIT }).isEmpty()
    }

    // ---------------- 設定の検証 ----------------

    @Test
    fun `不正な上限値は例外になる`() {
        assertThat(runCatching { RuleSettings(dailyLimitMinutes = -1) }.isFailure).isTrue()
        assertThat(runCatching { RuleSettings(approachingThresholdMinutes = -1) }.isFailure).isTrue()
    }

    @Test
    fun `時間の表記`() {
        assertThat(RuleEngine.formatDuration(240 * 60_000L)).isEqualTo("4時間")
        assertThat(RuleEngine.formatDuration(252 * 60_000L)).isEqualTo("4時間12分")
        assertThat(RuleEngine.formatDuration(23 * 60_000L)).isEqualTo("23分")
        assertThat(RuleEngine.formatDuration(0)).isEqualTo("0分")
    }
}
