package com.selfkaizen.app.data

import com.selfkaizen.app.rules.UsageStatus

/**
 * 「いまの状態」の判断。
 *
 * **ウィジェットと通知で同じ判断を使う。**
 * 別々に書くと「ウィジェットは超過、通知は超過じゃない」という
 * 食い違いが起きる。どちらが正しいか分からない状態が最悪なので、
 * 判断は1箇所に置く。
 *
 * 優先順位がある:
 *   権限なし → 収集停止 → 上限なし → 超過 → 上限内
 *
 * **データが古いのに「超過」と出さない**のが重要。
 * 実際には超過していないのに誤った行動を促すことになる。
 */
enum class UsageCondition {
    /** 上限内。 */
    LEFT,

    /** 上限超過。 */
    OVER,

    /** 上限が設定されていない。 */
    DISABLED,

    /** 使用状況アクセスが許可されていない。 */
    NO_PERMISSION,

    /** 収集が止まっている（データが古い）。 */
    STALE
}

/**
 * 収集が止まっているとみなすまでの時間。
 *
 * 収集は15分間隔なので、1時間止まっていれば異常と判断できる。
 * ただし端末を触っていない時間帯もあるため、余裕を取って3時間とする。
 */
const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L

/** 状態を決める。 */
fun TodayUsage.condition(
    hasPermission: Boolean,
    now: Long = System.currentTimeMillis()
): UsageCondition = when {
    !hasPermission -> UsageCondition.NO_PERMISSION

    lastCollectedAt == null || now - lastCollectedAt > STALE_AFTER_MILLIS ->
        UsageCondition.STALE

    status == UsageStatus.DISABLED -> UsageCondition.DISABLED
    status == UsageStatus.EXCEEDED -> UsageCondition.OVER
    else -> UsageCondition.LEFT
}
