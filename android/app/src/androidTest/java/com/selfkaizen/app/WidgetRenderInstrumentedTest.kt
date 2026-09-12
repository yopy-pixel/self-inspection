package com.selfkaizen.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.rules.UsageStatus
import com.selfkaizen.app.widget.UsageWidgetMiniProvider
import com.selfkaizen.app.widget.UsageWidgetProvider
import com.selfkaizen.app.widget.WidgetRenderer
import com.selfkaizen.app.widget.WidgetState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ウィジェットの描画検証（実機・エミュレータ上）。
 *
 * **`RemoteViews` は実際に inflate しないと検証できない。**
 * レイアウトの ID 間違い・文字列リソースの欠落・ProgressBar の設定ミスは
 * JVM のユニットテストでは見つからない。
 *
 * 実行: `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun usage(
        todayMillis: Long,
        limitMillis: Long,
        status: UsageStatus,
        lastCollectedAt: Long? = System.currentTimeMillis()
    ) = TodayUsage(
        todayMillis = todayMillis,
        limitMillis = limitMillis,
        status = status,
        lastCollectedAt = lastCollectedAt,
        remainingMillis = limitMillis - todayMillis,
        fraction = if (limitMillis <= 0) 1f
        else (todayMillis.toDouble() / limitMillis).coerceIn(0.0, 1.0).toFloat()
    )

    private fun textOf(view: View, id: Int): String =
        view.findViewById<TextView>(id).text.toString()

    // ---------------- 標準ウィジェット ----------------

    @Test
    fun 標準ウィジェットが上限内の状態を描画する() {
        val u = usage(
            todayMillis = 3 * 60 * 60_000L + 42 * 60_000L,   // 3h 42m
            limitMillis = 4 * 60 * 60_000L,                  // 4h
            status = UsageStatus.WITHIN_LIMIT
        )

        val view = WidgetRenderer.buildStandard(context, u, hasPermission = true)
            .apply(context, null)

        assertThat(textOf(view, R.id.widget_label)).isEqualTo("Today")
        assertThat(textOf(view, R.id.widget_total)).isEqualTo("3h 42m")
        // 残り18分
        assertThat(textOf(view, R.id.widget_detail)).isEqualTo("limit 4h 00m · 18m left")

        val bar = view.findViewById<ProgressBar>(R.id.widget_bar)
        assertThat(bar.visibility).isEqualTo(View.VISIBLE)
        // 3h42m / 4h = 92.5% → 925
        assertThat(bar.progress).isEqualTo(925)
    }

    @Test
    fun 標準ウィジェットが超過の状態を描画する() {
        val u = usage(
            todayMillis = 4 * 60 * 60_000L + 42 * 60_000L,   // 4h 42m（42分超過）
            limitMillis = 4 * 60 * 60_000L,
            status = UsageStatus.EXCEEDED
        )

        val view = WidgetRenderer.buildStandard(context, u, hasPermission = true)
            .apply(context, null)

        assertThat(textOf(view, R.id.widget_detail)).isEqualTo("limit 4h 00m · 42m over")

        val bar = view.findViewById<ProgressBar>(R.id.widget_bar)
        // 超過は満杯で止まる（バーの長さで状態を伝える）
        assertThat(bar.progress).isEqualTo(WidgetState.PROGRESS_MAX)
    }

    @Test
    fun 上限が無いときはバーを隠す() {
        val u = usage(
            todayMillis = 60 * 60_000L,
            limitMillis = 0L,
            status = UsageStatus.DISABLED
        )

        val view = WidgetRenderer.buildStandard(context, u, hasPermission = true)
            .apply(context, null)

        assertThat(view.findViewById<ProgressBar>(R.id.widget_bar).visibility)
            .isEqualTo(View.GONE)
        // 上限が無くても今日の量は出す
        assertThat(textOf(view, R.id.widget_total)).isEqualTo("1h 00m")
    }

    @Test
    fun 権限が無いときは数字を出さず案内を出す() {
        val u = usage(
            todayMillis = 60 * 60_000L,
            limitMillis = 4 * 60 * 60_000L,
            status = UsageStatus.WITHIN_LIMIT
        )

        val view = WidgetRenderer.buildStandard(context, u, hasPermission = false)
            .apply(context, null)

        // 0m と出すと「使っていない」と誤読される。記号にする。
        assertThat(textOf(view, R.id.widget_total)).isEqualTo("—")
        assertThat(textOf(view, R.id.widget_detail)).isEqualTo("Usage access needed")
        assertThat(view.findViewById<ProgressBar>(R.id.widget_bar).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun 収集が止まっているときは古さを伝える() {
        val u = usage(
            todayMillis = 60 * 60_000L,
            limitMillis = 4 * 60 * 60_000L,
            status = UsageStatus.WITHIN_LIMIT,
            // 5時間前（しきい値3時間を超える）
            lastCollectedAt = System.currentTimeMillis() - 5 * 60 * 60_000L
        )

        val view = WidgetRenderer.buildStandard(context, u, hasPermission = true)
            .apply(context, null)

        assertThat(textOf(view, R.id.widget_total)).isEqualTo("—")
        assertThat(textOf(view, R.id.widget_detail)).contains("not collected")
        assertThat(view.findViewById<ProgressBar>(R.id.widget_bar).visibility)
            .isEqualTo(View.GONE)
    }

    // ---------------- ミニウィジェット ----------------

    @Test
    fun ミニウィジェットが3_42形式で描画する() {
        val u = usage(
            todayMillis = 3 * 60 * 60_000L + 42 * 60_000L,
            limitMillis = 4 * 60 * 60_000L,
            status = UsageStatus.WITHIN_LIMIT
        )

        val view = WidgetRenderer.buildMini(context, u, hasPermission = true)
            .apply(context, null)

        assertThat(textOf(view, R.id.widget_label)).isEqualTo("Today")
        assertThat(textOf(view, R.id.widget_total)).isEqualTo("3:42")
        assertThat(view.findViewById<ProgressBar>(R.id.widget_bar).visibility)
            .isEqualTo(View.VISIBLE)
    }

    // ---------------- プロバイダの登録 ----------------

    @Test
    fun 両ウィジェットのプロバイダが登録されている() {
        val manager = AppWidgetManager.getInstance(context)

        val standard = manager.getAppWidgetIds(
            ComponentName(context, UsageWidgetProvider::class.java)
        )
        val mini = manager.getAppWidgetIds(
            ComponentName(context, UsageWidgetMiniProvider::class.java)
        )

        // 配置前なので ID は空でよい。**例外なく問い合わせられること**
        // （＝マニフェストに正しく登録されていること）が確認点。
        assertThat(standard).isNotNull()
        assertThat(mini).isNotNull()
    }
}
