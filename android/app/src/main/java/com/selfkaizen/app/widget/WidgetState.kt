package com.selfkaizen.app.widget

import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.rules.UsageStatus
import kotlin.math.roundToInt

/**
 * ウィジェットの表示判断。
 *
 * **Android に依存しない純粋なロジック**にしてある。
 * ウィジェットは「その場で気づく用」であり、
 * 判断を間違えると気づく機会そのものを失うため、境界をテストで固定する。
 */
object WidgetState {

    /** `ProgressBar` の `max`。0..1000 の整数で塗り率を表す。 */
    const val PROGRESS_MAX = 1000

    /**
     * 収集が止まっているとみなすまでの時間。
     *
     * 収集は15分間隔なので、1時間止まっていれば異常と判断できる。
     * ただし端末を触っていない時間帯もあるため、余裕を取って3時間とする。
     */
    const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L

    /** ウィジェットが表示すべき状態。 */
    enum class State {
        /** 上限内。 */
        LEFT,

        /** 上限超過。 */
        OVER,

        /** 上限が設定されていない（`RuleSettings.dailyLimitEnabled = false`）。 */
        DISABLED,

        /** 使用状況アクセスが許可されていない。 */
        NO_PERMISSION,

        /** 収集が止まっている（アプリは動いているがデータが古い）。 */
        STALE
    }

    /**
     * 状態を決める。**優先順位がある。**
     *
     * 権限なし → 収集停止 → 上限なし → 超過 → 上限内、の順で判定する。
     * 「データが古いのに『上限内』と表示する」のが最も危険なので、
     * 古さの判定は上限の判定より先に置く。
     */
    fun stateOf(
        usage: TodayUsage,
        hasPermission: Boolean,
        now: Long = System.currentTimeMillis()
    ): State = when {
        !hasPermission -> State.NO_PERMISSION

        usage.lastCollectedAt == null ||
            now - usage.lastCollectedAt > STALE_AFTER_MILLIS -> State.STALE

        usage.status == UsageStatus.DISABLED -> State.DISABLED
        usage.status == UsageStatus.EXCEEDED -> State.OVER
        else -> State.LEFT
    }

    /** 塗り率（0.0〜1.0）を `ProgressBar` の progress 値に変換する。 */
    fun progressOf(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX)

    /**
     * バーを表示すべきか。
     *
     * 上限が無い（DISABLED）ときにバーを出すと、
     * 「何かに対する進捗」に見えて誤解を招く。**隠す。**
     * 権限なし・収集停止のときも、古い値をバーで見せてはいけない。
     */
    fun shouldShowBar(state: State): Boolean =
        state == State.LEFT || state == State.OVER
}
