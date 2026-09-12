package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.ui.DashboardState
import com.selfkaizen.app.ui.DurationFormat
import org.junit.Test
import java.time.LocalDate

/**
 * 日別の内訳に切り替える機能の検証（純粋な部分）。
 *
 * 7日チャートの棒をタップすると、ランキングがその日の内訳に変わる。
 * **「いつの話か」が分からない数字は判断の役に立たない**ため、
 * 見出しの表示を壊さないことを確認する。
 */
class DaySelectionTest {

    // ---------------- 見出しの日付表記 ----------------

    @Test
    fun `日付は月日で短く表す`() {
        assertThat(DurationFormat.shortDate(LocalDate.of(2026, 9, 10))).isEqualTo("9/10")
        assertThat(DurationFormat.shortDate(LocalDate.of(2026, 12, 1))).isEqualTo("12/1")
        assertThat(DurationFormat.shortDate(LocalDate.of(2026, 1, 31))).isEqualTo("1/31")
    }

    @Test
    fun `年は出さない（直近7日しか扱わないため）`() {
        val s = DurationFormat.shortDate(LocalDate.of(2026, 9, 10))
        assertThat(s).doesNotContain("2026")
    }

    // ---------------- 選択状態 ----------------

    @Test
    fun `既定は今日を表示している`() {
        val state = DashboardState()
        assertThat(state.isTodaySelected).isTrue()
        assertThat(state.selectedDate).isNull()
    }

    @Test
    fun `日を選ぶと今日ではなくなる`() {
        val state = DashboardState(selectedDate = LocalDate.of(2026, 9, 10))
        assertThat(state.isTodaySelected).isFalse()
        assertThat(state.selectedDate).isEqualTo(LocalDate.of(2026, 9, 10))
    }

    @Test
    fun `選択を戻すと今日になる`() {
        val state = DashboardState(selectedDate = null)
        assertThat(state.isTodaySelected).isTrue()
    }

    // ---------------- 選択した日の値が使われること ----------------

    @Test
    fun `選択した日の合計と超過状態を保持する`() {
        val state = DashboardState(
            selectedDate = LocalDate.of(2026, 9, 10),
            selectedMillis = 5 * 60 * 60_000L,
            selectedExceeded = true
        )

        assertThat(state.selectedMillis).isEqualTo(5 * 60 * 60_000L)
        assertThat(state.selectedExceeded).isTrue()
        // 今日の値とは独立していること（混ざると誤読する）
        assertThat(state.todayMillis).isEqualTo(0L)
    }
}
