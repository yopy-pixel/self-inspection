package com.selfkaizen.app.widget

import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.data.UsageCondition
import com.selfkaizen.app.data.condition
import kotlin.math.roundToInt

/**
 * ウィジェットの表示判断。
 *
 * **Android に依存しない純粋なロジック**にしてある。
 * ウィジェットは「その場で気づく用」であり、
 * 判断を間違えると気づく機会そのものを失うため、境界をテストで固定する。
 *
 * 状態そのものの判断は [UsageCondition] に置いてある。
 * 通知も同じ判断を使うので、ここで二重に持つと
 * 「ウィジェットは超過、通知は超過じゃない」という食い違いが起きる。
 */
object WidgetState {

    /** `ProgressBar` の `max`。0..1000 の整数で塗り率を表す。 */
    const val PROGRESS_MAX = 1000

    /**
     * 状態を決める。
     *
     * 中身は [condition] に委譲する。ウィジェットと通知で判断を共有するため。
     */
    fun stateOf(
        usage: TodayUsage,
        hasPermission: Boolean,
        now: Long = System.currentTimeMillis()
    ): UsageCondition = usage.condition(hasPermission, now)

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
    fun shouldShowBar(state: UsageCondition): Boolean =
        state == UsageCondition.LEFT || state == UsageCondition.OVER
}
