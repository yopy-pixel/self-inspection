package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.notify.LimitAlertPolicy
import com.selfkaizen.app.rules.UsageStatus
import org.junit.Test

/**
 * 「上限を超えた瞬間に1回だけ知らせる」判断の検証。
 *
 * 通知は**介入そのもの**なので、ここでの誤りは生活に直結する。
 * 特に避けるべき2つ:
 *  - **超過していないのに知らせる**（誤警報。信頼を失う）
 *  - **同じ日に何度も知らせる**（15分ごとに鳴り、無視されるようになる）
 */
class LimitAlertPolicyTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L
    private val today = "2026-09-13"
    private val yesterday = "2026-09-12"

    private fun usage(
        used: Long,
        limit: Long = 4 * hour,
        status: UsageStatus = UsageStatus.WITHIN_LIMIT,
        lastCollectedAt: Long? = now - 60_000L
    ) = TodayUsage(
        todayMillis = used,
        limitMillis = limit,
        status = status,
        lastCollectedAt = lastCollectedAt,
        remainingMillis = limit - used,
        fraction = if (limit <= 0L) 1f else (used.toDouble() / limit).toFloat()
    )

    private fun should(
        usage: TodayUsage,
        alertedDate: String? = null,
        hasPermission: Boolean = true
    ) = LimitAlertPolicy.shouldAlert(usage, today, alertedDate, hasPermission, now)

    // ---------------- 超過の境界 ----------------

    @Test
    fun `上限を1ミリ秒でも超えたら知らせる`() {
        val u = usage(4 * hour + 1, status = UsageStatus.EXCEEDED)
        assertThat(should(u)).isTrue()
    }

    @Test
    fun `ちょうど上限では知らせない`() {
        // RuleEngine と同じ「超えたら超過」。ちょうどは超過ではない。
        val u = usage(4 * hour, status = UsageStatus.WITHIN_LIMIT)
        assertThat(should(u)).isFalse()
    }

    @Test
    fun `上限未満では知らせない`() {
        val u = usage(3 * hour, status = UsageStatus.WITHIN_LIMIT)
        assertThat(should(u)).isFalse()
    }

    @Test
    fun `接近しているだけでは知らせない`() {
        // 接近（CLOSE）は超過ではない。ここで鳴らすと CLOSE の意味が無くなる。
        val u = usage(4 * hour - 5 * 60_000L, status = UsageStatus.APPROACHING)
        assertThat(should(u)).isFalse()
    }

    // ---------------- 1日1回 ----------------

    @Test
    fun `今日すでに知らせていれば知らせない`() {
        // 15分ごとの収集で毎回鳴らさないための要。
        val u = usage(6 * hour, status = UsageStatus.EXCEEDED)
        assertThat(should(u, alertedDate = today)).isFalse()
    }

    @Test
    fun `昨日知らせていても今日は知らせる`() {
        val u = usage(6 * hour, status = UsageStatus.EXCEEDED)
        assertThat(should(u, alertedDate = yesterday)).isTrue()
    }

    @Test
    fun `未通知なら知らせる`() {
        val u = usage(6 * hour, status = UsageStatus.EXCEEDED)
        assertThat(should(u, alertedDate = null)).isTrue()
    }

    // ---------------- 判断できないとき ----------------

    @Test
    fun `権限が無ければ知らせない`() {
        val u = usage(9 * hour, status = UsageStatus.EXCEEDED)
        assertThat(should(u, hasPermission = false)).isFalse()
    }

    @Test
    fun `収集が止まっていれば超過でも知らせない`() {
        // 古いデータで「超過」と言うと、実際には超えていないのに
        // 誤った行動を促すことになる。
        val u = usage(
            9 * hour,
            status = UsageStatus.EXCEEDED,
            lastCollectedAt = now - 4 * hour
        )
        assertThat(should(u)).isFalse()
    }

    @Test
    fun `一度も収集できていなければ知らせない`() {
        val u = usage(9 * hour, status = UsageStatus.EXCEEDED, lastCollectedAt = null)
        assertThat(should(u)).isFalse()
    }

    @Test
    fun `上限が無効なら知らせない`() {
        val u = usage(9 * hour, limit = 0L, status = UsageStatus.DISABLED)
        assertThat(should(u)).isFalse()
    }

    // ---------------- 実際の流れ ----------------

    @Test
    fun `超えた直後に一度だけ知らせ、以降は黙る`() {
        // 15分ごとに収集が走る状況を模す。
        val alerted = mutableListOf<Int>()

        // 0:00 から15分ごとに増えていく使用時間
        for (step in 0..20) {
            val used = step * 15 * 60_000L
            val u = usage(
                used,
                status = if (used > 4 * hour) UsageStatus.EXCEEDED else UsageStatus.WITHIN_LIMIT
            )
            // 最後に知らせた日付を worker と同じように持ち回る
            val last = alerted.lastOrNull()?.let { today }
            if (LimitAlertPolicy.shouldAlert(u, today, last, true, now)) {
                alerted += step
            }
        }

        assertThat(alerted).hasSize(1)
        // 4時間を超える最初の刻み（4時間15分 = step 17）
        assertThat(alerted.first()).isEqualTo(17)
    }
}
