package com.selfkaizen.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.selfkaizen.app.MainActivity
import com.selfkaizen.app.R
import com.selfkaizen.app.data.TodayUsage
import com.selfkaizen.app.ui.DurationFormat

/**
 * ウィジェットの描画（SPEC §7）。
 *
 * 標準とミニの2種。**どちらもバーの長さで状態を伝える**ため色は使わない。
 * 文字列は `strings.xml` から取り、組み立てだけをここで行う。
 */
object WidgetRenderer {

    /** 標準ウィジェット: `Today` / `3h 42m` / バー / `limit 4h 00m · 18m left` */
    fun buildStandard(context: Context, usage: TodayUsage, hasPermission: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)
        val state = WidgetState.stateOf(usage, hasPermission)

        views.setTextViewText(R.id.widget_label, context.getString(R.string.label_today))
        views.setTextViewText(R.id.widget_total, totalText(context, usage, state))
        views.setTextViewText(R.id.widget_detail, detailText(context, usage, state))

        applyBar(views, usage, state)
        applyClick(context, views)
        return views
    }

    /** ミニウィジェット: `Today` / `3:42` / バー */
    fun buildMini(context: Context, usage: TodayUsage, hasPermission: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage_mini)
        val state = WidgetState.stateOf(usage, hasPermission)

        views.setTextViewText(R.id.widget_label, context.getString(R.string.label_today))
        views.setTextViewText(R.id.widget_total, compactText(context, usage, state))

        applyBar(views, usage, state)
        applyClick(context, views)
        return views
    }

    // ------------------------------------------------------------------

    /** 大きい数字。データが信用できないときは数字を出さない。 */
    private fun totalText(context: Context, usage: TodayUsage, state: WidgetState.State): String =
        when (state) {
            // 古い値・権限なしで数字を出すと「使っていない」と誤読される。
            // **嘘の数字より記号の方がまし。**
            WidgetState.State.NO_PERMISSION,
            WidgetState.State.STALE -> context.getString(R.string.widget_none)

            // 上限が無くても、今日どれだけ使ったかは出す価値がある。
            else -> DurationFormat.long(usage.todayMillis)
        }

    /** ミニ用の `3:42`。 */
    private fun compactText(context: Context, usage: TodayUsage, state: WidgetState.State): String =
        when (state) {
            WidgetState.State.NO_PERMISSION,
            WidgetState.State.STALE -> context.getString(R.string.widget_none)
            else -> DurationFormat.hourMinute(usage.todayMillis)
        }

    /**
     * 詳細行。状態によって意味が変わる。
     *
     * 通常は `limit 4h 00m · 18m left`。
     * 上限が無い / 権限が無い / 収集が止まっている場合は、
     * **その事実を優先して出す**（数字より先に知るべき情報のため）。
     */
    private fun detailText(context: Context, usage: TodayUsage, state: WidgetState.State): String =
        when (state) {
            WidgetState.State.NO_PERMISSION ->
                context.getString(R.string.widget_no_permission)

            WidgetState.State.STALE -> {
                val ago = usage.lastCollectedAt
                    ?.let { DurationFormat.ago(it, System.currentTimeMillis()) }
                    ?: DurationFormat.ago(0L, System.currentTimeMillis())
                context.getString(R.string.widget_stale, ago)
            }

            WidgetState.State.DISABLED ->
                context.getString(R.string.state_disabled)

            else -> {
                val limit = context.getString(
                    R.string.label_limit,
                    DurationFormat.limit(usage.limitMillis)
                )
                val stateWord = if (state == WidgetState.State.OVER) {
                    context.getString(
                        R.string.state_over,
                        DurationFormat.long(-usage.remainingMillis)
                    )
                } else {
                    context.getString(
                        R.string.state_left,
                        DurationFormat.long(usage.remainingMillis)
                    )
                }
                "$limit · $stateWord"
            }
        }

    /** バーの塗りと表示可否。 */
    private fun applyBar(views: RemoteViews, usage: TodayUsage, state: WidgetState.State) {
        if (!WidgetState.shouldShowBar(state)) {
            // 上限が無い / データが信用できないときはバーを隠す。
            // 「何かに対する進捗」に見えると誤解を招く。
            views.setViewVisibility(R.id.widget_bar, View.GONE)
            return
        }
        views.setViewVisibility(R.id.widget_bar, View.VISIBLE)
        views.setProgressBar(
            R.id.widget_bar,
            WidgetState.PROGRESS_MAX,
            WidgetState.progressOf(usage.fraction),
            false
        )
    }

    /** タップでアプリを開く。 */
    private fun applyClick(context: Context, views: RemoteViews) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
    }
}
