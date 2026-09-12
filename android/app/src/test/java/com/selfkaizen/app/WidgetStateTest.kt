package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.rules.UsageStatus
import com.selfkaizen.app.ui.DurationFormat
import com.selfkaizen.app.widget.WidgetState
import org.junit.Test

/**
 * ウィジェットの表示判断の検証。
 *
 * ウィジェットは「**その場で気づく用**」（SPEC §7）であり、
 * 判断を間違えると気づく機会そのものを失う。
 * 特に **古いデータで「超過」と表示する**のは、
 * 実際には超過していない場合に誤った行動を促すため避ける。
 */
class WidgetStateTest {

    private val now = 1_700_000_000_000L

    private fun usage(
        todayMillis: Long = 60 * 60_000L,
        limitMillis: Long = 4 * 60 * 60_000L,
        status: UsageStatus = UsageStatus.WITHIN_LIMIT,
        lastCollectedAt: Long? = now - 60_000L,
        remainingMillis: Long = limitMillis - todayMillis,
        fraction: Float = 0.25f
    ) = TodayUsage(
        todayMillis = todayMillis,
        limitMillis = limitMillis,
        status = status,
        lastCollectedAt = lastCollectedAt,
        remainingMillis = remainingMillis,
        fraction = fraction
    )

    // ---------------- 状態の判定 ----------------

    @Test
    fun `上限内は LEFT`() {
        val s = WidgetState.stateOf(usage(), hasPermission = true, now = now)
        assertThat(s).isEqualTo(WidgetState.State.LEFT)
    }

    @Test
    fun `超過は OVER`() {
        val s = WidgetState.stateOf(
            usage(status = UsageStatus.EXCEEDED),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.OVER)
    }

    @Test
    fun `上限なしは DISABLED`() {
        val s = WidgetState.stateOf(
            usage(status = UsageStatus.DISABLED),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.DISABLED)
    }

    @Test
    fun `権限が無ければ NO_PERMISSION`() {
        val s = WidgetState.stateOf(usage(), hasPermission = false, now = now)
        assertThat(s).isEqualTo(WidgetState.State.NO_PERMISSION)
    }

    @Test
    fun `未収集なら STALE`() {
        val s = WidgetState.stateOf(
            usage(lastCollectedAt = null),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.STALE)
    }

    @Test
    fun `収集が古ければ STALE`() {
        val old = now - WidgetState.STALE_AFTER_MILLIS - 1
        val s = WidgetState.stateOf(
            usage(lastCollectedAt = old),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.STALE)
    }

    @Test
    fun `古さの境界ちょうどは STALE にしない`() {
        val edge = now - WidgetState.STALE_AFTER_MILLIS
        val s = WidgetState.stateOf(
            usage(lastCollectedAt = edge),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.LEFT)
    }

    // ---------------- 優先順位（重要） ----------------

    @Test
    fun `権限なしは収集停止より優先される`() {
        // 権限が無いのに「収集が止まっています」と言っても意味がない。
        // まず許可してもらう必要がある。
        val s = WidgetState.stateOf(
            usage(lastCollectedAt = null),
            hasPermission = false,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.NO_PERMISSION)
    }

    @Test
    fun `データが古いときは超過と表示しない`() {
        // **これが最も重要。** 古いデータで「超過」と出すと、
        // 実際には超過していないのに誤った行動を促す。
        // 判断できないときは「判断できない」と伝える。
        val old = now - WidgetState.STALE_AFTER_MILLIS - 1
        val s = WidgetState.stateOf(
            usage(status = UsageStatus.EXCEEDED, lastCollectedAt = old),
            hasPermission = true,
            now = now
        )
        assertThat(s).isEqualTo(WidgetState.State.STALE)
    }

    // ---------------- バーの塗り ----------------

    @Test
    fun `塗り率が progress 値になる`() {
        assertThat(WidgetState.progressOf(0f)).isEqualTo(0)
        assertThat(WidgetState.progressOf(1f)).isEqualTo(1000)
        assertThat(WidgetState.progressOf(0.5f)).isEqualTo(500)
        assertThat(WidgetState.progressOf(0.25f)).isEqualTo(250)
    }

    @Test
    fun `塗り率は0から1に丸められる`() {
        assertThat(WidgetState.progressOf(1.5f)).isEqualTo(1000)   // 超過で満杯
        assertThat(WidgetState.progressOf(-0.2f)).isEqualTo(0)
    }

    // ---------------- バーの表示可否 ----------------

    @Test
    fun `上限内と超過ではバーを出す`() {
        assertThat(WidgetState.shouldShowBar(WidgetState.State.LEFT)).isTrue()
        assertThat(WidgetState.shouldShowBar(WidgetState.State.OVER)).isTrue()
    }

    @Test
    fun `上限なしと判断不能ではバーを隠す`() {
        // 上限が無いのにバーを出すと「何かに対する進捗」に見えて誤解を招く。
        assertThat(WidgetState.shouldShowBar(WidgetState.State.DISABLED)).isFalse()
        assertThat(WidgetState.shouldShowBar(WidgetState.State.NO_PERMISSION)).isFalse()
        assertThat(WidgetState.shouldShowBar(WidgetState.State.STALE)).isFalse()
    }

    // ---------------- ミニ用の表記（SPEC §1「単位表記」） ----------------

    @Test
    fun `ミニは 3_42 形式で分を2桁に揃える`() {
        assertThat(DurationFormat.hourMinute(3 * 60 * 60_000L + 42 * 60_000L)).isEqualTo("3:42")
        assertThat(DurationFormat.hourMinute(48 * 60_000L)).isEqualTo("0:48")
        assertThat(DurationFormat.hourMinute(0L)).isEqualTo("0:00")
        assertThat(DurationFormat.hourMinute(12 * 60 * 60_000L + 5 * 60_000L)).isEqualTo("12:05")
        assertThat(DurationFormat.hourMinute(-5000L)).isEqualTo("0:00")
    }
}
