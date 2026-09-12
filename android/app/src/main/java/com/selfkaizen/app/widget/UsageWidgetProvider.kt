package com.selfkaizen.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * 標準ウィジェット（SPEC §7）。
 *
 * **これが本命の介入点。**
 * 自分を「自堕落」と表現する人が自分から監視アプリを開くことは期待できない。
 * ホーム画面に出ていれば、見なくても目に入る。
 *
 * データ読み込みは非同期なので、ここでは描画せず
 * [WidgetUpdateWorker] に投げる（プロセスが死んでも WorkManager が拾う）。
 */
class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.i(TAG, "onUpdate: ${appWidgetIds.size} 個")
        WidgetUpdateWorker.enqueue(context)
    }

    /** ウィジェット追加時・最初の配置時。すぐ実値を出したい。 */
    override fun onEnabled(context: Context) {
        WidgetUpdateWorker.enqueue(context)
    }

    /** サイズ変更（リサイズ）時。RemoteViews を作り直す。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetUpdateWorker.enqueue(context)
    }

    private companion object {
        const val TAG = "UsageWidget"
    }
}
