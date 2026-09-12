package com.selfkaizen.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfkaizen.app.collector.UsageAccess
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.SettingsRepository
import com.selfkaizen.app.data.TodayUsageRepository

/**
 * ウィジェットを最新の状態に更新する Worker。
 *
 * **ウィジェットは「その場で気づく用」**（SPEC §7）。
 * 古い値を表示し続けると、その役割を果たせないだけでなく
 * 「使っていない」という**嘘**を見せることになる。
 * そのため更新は収集の完了に合わせて行う。
 *
 * データ読み込みが非同期なので、`AppWidgetProvider.onUpdate` から
 * 直接ではなく Worker 経由で行う（プロセスが死んでも WorkManager が再実行する）。
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        // 権限が無ければウィジェットにその旨を出す（数字は出さない）。
        val hasPermission = UsageAccess.hasPermission(context)

        val usage = if (hasPermission) {
            runCatching {
                val dao = DatabaseProvider.get(context).usageDao()
                TodayUsageRepository(dao, SettingsRepository(dao)).load()
            }.getOrNull()
        } else {
            null
        }

        updateAll(context, usage, hasPermission)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "widget_update"

        /** 全ウィジェット（標準・ミニ）を更新する。 */
        fun updateAll(
            context: Context,
            usage: com.selfkaizen.app.data.TodayUsage?,
            hasPermission: Boolean
        ) {
            val manager = AppWidgetManager.getInstance(context) ?: return

            // 標準
            val standard = manager.getAppWidgetIds(
                ComponentName(context, UsageWidgetProvider::class.java)
            )
            standard?.forEach { id ->
                if (usage != null) {
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.buildStandard(context, usage, hasPermission)
                    )
                } else {
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.buildStandard(context, emptyUsage(), hasPermission)
                    )
                }
            }

            // ミニ
            val mini = manager.getAppWidgetIds(
                ComponentName(context, UsageWidgetMiniProvider::class.java)
            )
            mini?.forEach { id ->
                val u = usage ?: emptyUsage()
                manager.updateAppWidget(id, WidgetRenderer.buildMini(context, u, hasPermission))
            }
        }

        /**
         * データが読めないときのプレースホルダ。
         *
         * 状態は権限の有無で `NO_PERMISSION` / `STALE` に落ちるため、
         * 数字は表示されない（[WidgetRenderer] 参照）。
         */
        private fun emptyUsage() = com.selfkaizen.app.data.TodayUsage(
            todayMillis = 0L,
            limitMillis = 0L,
            status = com.selfkaizen.app.rules.UsageStatus.WITHIN_LIMIT,
            lastCollectedAt = null,
            remainingMillis = 0L,
            fraction = 0f
        )

        /** いますぐ更新する（収集完了時・再起動時・アプリ起動時）。 */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                // 連続で呼ばれても実行は1回にまとめる（無駄な起こしを避ける）。
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
