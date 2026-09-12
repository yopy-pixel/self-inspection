package com.selfkaizen.app.notify

import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.data.UsageCondition
import com.selfkaizen.app.data.condition

/**
 * 通知の内容。
 *
 * **状態ごとに必要な情報だけを持たせる**（sealed interface）。
 * 「使った量」と「上限」を常に持つクラスにすると、
 * 権限が無いのに 0m を使った量として表示する、といった
 * **嘘の数字**が簡単に混入する。
 * ここでは `NoPermission` が数字を1つも持たないので、
 * そもそも表示できない。
 */
sealed interface NotifyContent {

    /** 使用状況アクセスが無く、何も記録できていない。 */
    data object NoPermission : NotifyContent

    /**
     * 収集が止まっている。
     *
     * @param forMillis 最後の収集からの経過。null = 一度も収集できていない。
     */
    data class Stalled(val forMillis: Long?) : NotifyContent

    /** 上限が設定されていない（記録はできている）。 */
    data class NoLimit(val usedMillis: Long) : NotifyContent

    /** 上限内。 */
    data class Remaining(
        val usedMillis: Long,
        val limitMillis: Long,
        val remainingMillis: Long
    ) : NotifyContent

    /** 上限超過。 */
    data class Over(
        val usedMillis: Long,
        val limitMillis: Long,
        val overMillis: Long
    ) : NotifyContent

    /**
     * 昨日の合計。
     *
     * **上限は添えない。** 上限設定は現在の値であり、
     * 昨日その上限が適用されていたとは限らない。
     * 適用されていない上限で「超過」と言うのは誤りなので、
     * 昨日については事実（合計）だけを伝える。
     */
    data class PastDay(val usedMillis: Long) : NotifyContent
}

/**
 * 12時間ごとの通知で何を伝えるかを決める。
 *
 * **Android に依存しない純粋なロジック。** 通知は
 * 「気づく機会」そのものなので、判断の境界をテストで固定する。
 *
 * 設計上の判断:
 *  - **通知の前に必ず収集する**（呼び出し側の責務。[NotifyWorker]）。
 *    古い数字で通知すると、実際には超過していないのに
 *    「超過」と言うことになる。
 *  - 朝（今日まだ使っていない時間帯）は**昨日の合計**を出す。
 *    `0m` と出しても何の情報も無いため。
 *  - 伝えることが無いときは **null を返す**（通知しない）。
 */
object NotifyPolicy {

    /**
     * 「今日はまだ使っていない」とみなす閾値。
     *
     * 厳密に 0 とすると、朝の数分の操作で昨日の合計が消えてしまう。
     */
    const val NEGLIGIBLE_MILLIS = 5L * 60 * 1000

    /**
     * @param today 今日の使用状況（収集直後の値）
     * @param yesterdayMillis 昨日の合計。null = 昨日のデータが無い
     * @return 通知すべき内容。null = 伝えることが無いので通知しない
     */
    fun decide(
        today: TodayUsage,
        yesterdayMillis: Long?,
        hasPermission: Boolean,
        now: Long = System.currentTimeMillis()
    ): NotifyContent? {
        when (today.condition(hasPermission, now)) {
            UsageCondition.NO_PERMISSION -> return NotifyContent.NoPermission

            UsageCondition.STALE -> return NotifyContent.Stalled(
                today.lastCollectedAt?.let { (now - it).coerceAtLeast(0L) }
            )

            UsageCondition.DISABLED -> {
                // 上限未設定は「判定できていない」という事実を伝える。
                // これだけは使用量 0 でも伝える価値がある
                // （ユーザーが上限を入れない限り永久に判定されないため）。
                val used = today.todayMillis
                return if (used < NEGLIGIBLE_MILLIS && (yesterdayMillis ?: 0L) > 0L) {
                    NotifyContent.PastDay(yesterdayMillis!!)
                } else {
                    NotifyContent.NoLimit(used)
                }
            }

            UsageCondition.OVER -> return NotifyContent.Over(
                usedMillis = today.todayMillis,
                limitMillis = today.limitMillis,
                overMillis = -today.remainingMillis
            )

            UsageCondition.LEFT -> {
                val yesterday = yesterdayMillis ?: 0L
                // 朝は昨日の振り返りを出す。0m は情報ではない。
                if (today.todayMillis < NEGLIGIBLE_MILLIS && yesterday > 0L) {
                    return NotifyContent.PastDay(yesterday)
                }
                // 何も使っておらず、昨日のデータも無い = 伝えることが無い。
                if (today.todayMillis == 0L) return null

                return NotifyContent.Remaining(
                    usedMillis = today.todayMillis,
                    limitMillis = today.limitMillis,
                    remainingMillis = today.remainingMillis
                )
            }
        }
    }
}
