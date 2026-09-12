package com.selfkaizen.app.collector

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfkaizen.app.data.DatabaseProvider
import java.util.concurrent.TimeUnit

/**
 * 使用状況を定期的に収集する Worker。
 *
 * 設計上の判断:
 *  - **ForegroundService は使わない。** Android 14 以降は FGS type が必須だが、
 *    この用途に該当する type が存在しない（公式は WorkManager への移行を推奨）。
 *  - **15分間隔は「最短」であって保証ではない。** Doze 中は遅延する。
 *    そのため「15分きっかり」を前提にせず、**前回カーソルからの差分収集**で
 *    取りこぼしを補う設計にしている（[UsageCollector]）。
 */
class UsageCollectorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = DatabaseProvider.get(applicationContext)

        // 使用状況アクセスが未許可なら、リトライしても無駄なので成功扱いで終わる。
        // （ユーザーが許可するまでデータは取れない）
        if (!UsageAccess.hasPermission(applicationContext)) {
            Log.i(TAG, "使用状況アクセスが未許可のため収集をスキップ")
            return Result.success()
        }

        return try {
            val collector = UsageCollector(applicationContext, db)
            val result = collector.collect()
            Log.i(TAG, "収集完了: ${result.summary()}")

            db.usageDao().putState(
                com.selfkaizen.app.data.CollectionState(
                    com.selfkaizen.app.data.CollectionKeys.LAST_RESULT,
                    result.summary()
                )
            )

            // ウィジェットを最新化する（SPEC §7「その場で気づく用」）。
            //
            // 収集直後に更新するのが最も効率的。別途タイマーを回すと
            // 端末を余計に起こすことになる。
            com.selfkaizen.app.widget.WidgetUpdateWorker.enqueue(applicationContext)

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "収集に失敗", t)
            // 一時的な失敗（DB ロック等）はリトライする。
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "UsageCollectorWorker"
        private const val MAX_RETRIES = 3

        /** 定期収集の一意名。多重登録を防ぐ。 */
        const val PERIODIC_WORK_NAME = "usage_collection_periodic"

        /** 起動時の即時収集の一意名。 */
        const val IMMEDIATE_WORK_NAME = "usage_collection_immediate"
    }
}

/** 収集のスケジューリング。 */
object CollectionScheduler {

    /**
     * 15分間隔の定期収集を登録する。
     *
     * `ExistingPeriodicWorkPolicy.KEEP` を使うことで、
     * アプリ起動のたびに呼んでも既存のスケジュールを維持する
     * （UPDATE だと実行タイミングがリセットされ続け、
     *  結果として一度も実行されない可能性がある）。
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageCollectorWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    // 電池切れ間際に走らせない。
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UsageCollectorWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * いますぐ1回収集する。
     *
     * アプリ起動時・再起動後に呼ぶ。
     * 定期実行の15分を待たずに最新状態を取り込むため。
     */
    fun collectNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageCollectorWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UsageCollectorWorker.IMMEDIATE_WORK_NAME,
            // 未実行のものが残っていれば置き換える（重複実行を防ぐ）。
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
