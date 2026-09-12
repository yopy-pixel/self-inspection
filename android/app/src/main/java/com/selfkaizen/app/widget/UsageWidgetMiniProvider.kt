package com.selfkaizen.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * ミニウィジェット（SPEC §7）。
 *
 * 標準との違いは**上限と残りを出さないこと**。
 * 置ける場所が狭いので、今日の量とバーだけに絞る。
 * 役割（その場で気づく用）は標準と同じ。
 */
class UsageWidgetMiniProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdateWorker.enqueue(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateWorker.enqueue(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetUpdateWorker.enqueue(context)
    }
}
