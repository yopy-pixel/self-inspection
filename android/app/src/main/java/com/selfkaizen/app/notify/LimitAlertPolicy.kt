package com.selfkaizen.app.notify

import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.data.UsageCondition
import com.selfkaizen.app.data.condition

/**
 * 「上限を超えた瞬間」に知らせるための判断。
 *
 * **12時間ごとのまとめ通知（[NotifyPolicy]）とは目的が違う。**
 * あちらは振り返り（今日どうだったか）で、こちらは
 * **その場で気づく**ための通知。超過に気づくのが12時間後では遅い。
 *
 * **1日1回に限る。** 超過している間ずっと送ると、収集のたび
 * （15分ごと）に通知が来ることになり、**無視される通知になる。**
 * 読まれない通知は「気づく機会」として機能しない。
 *
 * Android に依存しない純粋なロジックにしてある。
 */
object LimitAlertPolicy {

    /**
     * いま知らせるべきか。
     *
     * @param today 今日のローカル日付（"YYYY-MM-DD"）
     * @param alertedDate 最後に知らせた日（"YYYY-MM-DD"）。null = 未通知
     */
    fun shouldAlert(
        usage: TodayUsage,
        today: String,
        alertedDate: String?,
        hasPermission: Boolean,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        // 今日もう知らせている。何度呼ばれても知らせない。
        if (alertedDate == today) return false

        // **超過のときだけ知らせる。**
        // 収集が止まっている（STALE）ときに「超過」と言ってはいけないし、
        // 権限が無ければ何も分からない。`condition()` がそれを保証する。
        return usage.condition(hasPermission, now) == UsageCondition.OVER
    }
}
