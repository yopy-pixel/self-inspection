package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.data.UsageCondition
import com.selfkaizen.app.data.condition
import com.selfkaizen.app.notify.NotifyContent
import com.selfkaizen.app.notify.NotifyPolicy
import com.selfkaizen.app.rules.UsageStatus
import org.junit.Test

/**
 * 12時間ごとの通知の判断の検証（`PLAN-android.md` Phase 3）。
 *
 * 通知は**介入そのもの**なので、ここでの誤りは
 * 「実際には超過していないのに超過と言う」という形で
 * 生活に直接影響する。境界をテストで固定する。
 *
 * 特に守るべき性質:
 *  - **古いデータで「超過」と言わない**（[UsageCondition.STALE] が優先）
 *  - **権限が無いのに数字を出さない**（[NotifyContent.NoPermission] は数字を持たない）
 *  - **伝えることが無いときは通知しない**（null）
 */
class NotifyPolicyTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    private fun usage(
        todayMillis: Long = hour,
        limitMillis: Long = 4 * hour,
        status: UsageStatus = UsageStatus.WITHIN_LIMIT,
        lastCollectedAt: Long? = now - 60_000L
    ) = TodayUsage(
        todayMillis = todayMillis,
        limitMillis = limitMillis,
        status = status,
        lastCollectedAt = lastCollectedAt,
        remainingMillis = limitMillis - todayMillis,
        fraction = if (limitMillis <= 0L) 1f else (todayMillis.toDouble() / limitMillis).toFloat()
    )

    private fun decide(
        today: TodayUsage,
        yesterdayMillis: Long? = null,
        hasPermission: Boolean = true
    ) = NotifyPolicy.decide(today, yesterdayMillis, hasPermission, now)

    // ---------------- 優先順位 ----------------

    @Test
    fun `権限が無ければ数字を伴わない通知になる`() {
        val c = decide(usage(todayMillis = 3 * hour), hasPermission = false)
        // 記録できていないのだから、使用量を知っているはずがない。
        assertThat(c).isEqualTo(NotifyContent.NoPermission)
    }

    @Test
    fun `権限が無ければ超過していても超過と言わない`() {
        val c = decide(
            usage(todayMillis = 9 * hour, status = UsageStatus.EXCEEDED),
            hasPermission = false
        )
        assertThat(c).isEqualTo(NotifyContent.NoPermission)
    }

    @Test
    fun `収集が古ければ超過していても超過と言わない`() {
        // 3時間前に超過していたことは、いま超過していることを意味しない。
        val c = decide(
            usage(
                todayMillis = 9 * hour,
                status = UsageStatus.EXCEEDED,
                lastCollectedAt = now - 4 * hour
            )
        )
        assertThat(c).isInstanceOf(NotifyContent.Stalled::class.java)
    }

    @Test
    fun `一度も収集できていなければ経過時間は不明として扱う`() {
        val c = decide(usage(lastCollectedAt = null)) as NotifyContent.Stalled
        assertThat(c.forMillis).isNull()
    }

    @Test
    fun `収集停止は経過時間を伴う`() {
        val c = decide(usage(lastCollectedAt = now - 5 * hour)) as NotifyContent.Stalled
        assertThat(c.forMillis).isEqualTo(5 * hour)
    }

    // ---------------- 超過・残り ----------------

    @Test
    fun `超過は超過量を持つ`() {
        val c = decide(
            usage(
                todayMillis = 4 * hour + 42 * 60_000L,
                status = UsageStatus.EXCEEDED
            )
        ) as NotifyContent.Over

        assertThat(c.usedMillis).isEqualTo(4 * hour + 42 * 60_000L)
        assertThat(c.limitMillis).isEqualTo(4 * hour)
        assertThat(c.overMillis).isEqualTo(42 * 60_000L)
    }

    @Test
    fun `上限内は残り時間を持つ`() {
        val c = decide(usage(todayMillis = 2 * hour + 42 * 60_000L)) as NotifyContent.Remaining
        assertThat(c.usedMillis).isEqualTo(2 * hour + 42 * 60_000L)
        assertThat(c.remainingMillis).isEqualTo(hour + 18 * 60_000L)
    }

    // ---------------- 朝は昨日を出す ----------------

    @Test
    fun `今日まだ使っていなければ昨日の合計を出す`() {
        // 朝の「0m」は情報ではない。昨日の振り返りを出す。
        val c = decide(usage(todayMillis = 0L), yesterdayMillis = 3 * hour + 42 * 60_000L)
        assertThat(c).isEqualTo(NotifyContent.PastDay(3 * hour + 42 * 60_000L))
    }

    @Test
    fun `わずかな使用では昨日の合計を出す`() {
        val c = decide(usage(todayMillis = 60_000L), yesterdayMillis = 2 * hour)
        assertThat(c).isEqualTo(NotifyContent.PastDay(2 * hour))
    }

    @Test
    fun `今日それなりに使っていれば今日を出す`() {
        // 昨日の方が長くても、今日の状況の方が判断に要る。
        val c = decide(usage(todayMillis = 30 * 60_000L), yesterdayMillis = 8 * hour)
        assertThat(c).isInstanceOf(NotifyContent.Remaining::class.java)
    }

    @Test
    fun `昨日の合計には上限を添えない`() {
        // 上限は現在の設定であり、昨日その上限だったとは限らない。
        val c = decide(usage(todayMillis = 0L), yesterdayMillis = 9 * hour)
            as NotifyContent.PastDay
        assertThat(c.usedMillis).isEqualTo(9 * hour)
        // PastDay は usedMillis しか持たない = 上限を主張できない。
    }

    // ---------------- 通知しない場合 ----------------

    @Test
    fun `今日も昨日もデータが無ければ通知しない`() {
        assertThat(decide(usage(todayMillis = 0L), yesterdayMillis = null)).isNull()
    }

    @Test
    fun `昨日が0なら昨日の振り返りは出さない`() {
        // 集計行はあるが 0 の場合。「0m 使った」は伝える価値が無い。
        assertThat(decide(usage(todayMillis = 0L), yesterdayMillis = 0L)).isNull()
    }

    @Test
    fun `上限未設定で使用0でも判定不能であることを伝える`() {
        // 使っていなくても「上限が無いので判定していない」ことは伝える価値がある。
        // ユーザーが上限を入れない限り、この仕組みは永久に判定しないため。
        assertThat(
            decide(usage(todayMillis = 0L, limitMillis = 0L, status = UsageStatus.DISABLED))
        ).isEqualTo(NotifyContent.NoLimit(0L))
    }

    // ---------------- 上限未設定 ----------------

    @Test
    fun `上限未設定は使用量だけを伝える`() {
        val c = decide(
            usage(todayMillis = 3 * hour, status = UsageStatus.DISABLED)
        ) as NotifyContent.NoLimit
        assertThat(c.usedMillis).isEqualTo(3 * hour)
    }

    @Test
    fun `上限未設定でも朝は昨日の合計を出す`() {
        val c = decide(
            usage(todayMillis = 0L, status = UsageStatus.DISABLED),
            yesterdayMillis = 2 * hour
        )
        assertThat(c).isEqualTo(NotifyContent.PastDay(2 * hour))
    }

    // ---------------- 閾値の境界 ----------------

    @Test
    fun `閾値ちょうどでは今日を出す`() {
        val c = decide(
            usage(todayMillis = NotifyPolicy.NEGLIGIBLE_MILLIS),
            yesterdayMillis = 8 * hour
        )
        // 5分ちょうどは「まだ使っていない」に含めない。
        assertThat(c).isInstanceOf(NotifyContent.Remaining::class.java)
    }

    @Test
    fun `閾値を1ミリ秒下回れば昨日を出す`() {
        val c = decide(
            usage(todayMillis = NotifyPolicy.NEGLIGIBLE_MILLIS - 1),
            yesterdayMillis = 8 * hour
        )
        assertThat(c).isEqualTo(NotifyContent.PastDay(8 * hour))
    }

    // ---------------- 状態判定との一致 ----------------

    @Test
    fun `ウィジェットと同じ状態判定を使う`() {
        // 判断が2箇所にあると「ウィジェットは超過、通知は違う」が起こりうる。
        // 同じ関数を呼んでいることを明示的に固定する。
        val u = usage(todayMillis = 5 * hour, status = UsageStatus.EXCEEDED)
        assertThat(u.condition(hasPermission = true, now = now)).isEqualTo(UsageCondition.OVER)
        assertThat(decide(u)).isInstanceOf(NotifyContent.Over::class.java)
    }
}
