package com.selfkaizen.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfkaizen.app.collector.UsageAccess
import com.selfkaizen.app.data.CollectionState
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.SettingsRepository
import com.selfkaizen.app.data.TodayUsageRepository
import com.selfkaizen.app.data.unknownTodayUsage
import java.time.LocalDate

/**
 * 上限を超えた**その日のうちに**知らせる Worker。
 *
 * 12時間ごとのまとめ（[NotifyWorker]）とは別物。
 * あちらは 9:00 / 21:00 の振り返りで、**超過に気づくのが最大12時間遅れる。**
 * こちらは収集の直後に呼ばれ、超過から15分以内に知らせる。
 *
 * **収集はしない。** 呼び出し元（[com.selfkaizen.app.collector.UsageCollectorWorker]）
 * が収集した直後に呼ぶので、ここで二重に収集すると無駄に端末を起こす。
 * 自分で収集しない代わりに、**保存済みの値が古ければ何もしない**
 * （[com.selfkaizen.app.data.condition] が `STALE` を返すため）。
 *
 * 通知は [UsageNotifier] の固定 ID を使うため、
 * **まとめ通知とは同時に1件しか出ない**（常に最新の状態が残る）。
 */
class LimitAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val dao = DatabaseProvider.get(context).usageDao()
        val repository = TodayUsageRepository(dao, SettingsRepository(dao))

        val today = LocalDate.now().toString()
        val usage = runCatching { repository.load() }
            .getOrElse {
                Log.w(TAG, "使用状況を読めなかった", it)
                unknownTodayUsage()
            }

        val should = LimitAlertPolicy.shouldAlert(
            usage = usage,
            today = today,
            alertedDate = dao.getState(KEY_ALERTED_DATE),
            hasPermission = UsageAccess.hasPermission(context)
        )
        if (!should) return Result.success()

        val posted = runCatching {
            UsageNotifier(context).notify(
                NotifyContent.Over(
                    usedMillis = usage.todayMillis,
                    limitMillis = usage.limitMillis,
                    overMillis = -usage.remainingMillis
                )
            )
        }.getOrElse {
            Log.w(TAG, "超過を通知できなかった", it)
            false
        }

        // **実際に出せたときだけ記録する。**
        // 権限が無くて出せなかった場合に記録すると、
        // 後で許可してもその日はもう知らせてもらえなくなる。
        if (posted) {
            // 記録してから次回以降は知らせない（1日1回）。
            dao.putState(CollectionState(KEY_ALERTED_DATE, today))
            Log.i(
                TAG,
                "上限超過を通知: ${usage.todayMillis}ms / 上限 ${usage.limitMillis}ms"
            )
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "LimitAlertWorker"

        /** 多重登録を防ぐための一意名。 */
        const val WORK_NAME = "limit_alert"

        /**
         * 最後に超過を知らせたローカル日付（"YYYY-MM-DD"）。
         *
         * 日付を保存するので、日が変われば自動的にまた知らせられる。
         * フラグ（真偽値）にすると日付のリセットを別途書くことになる。
         */
        const val KEY_ALERTED_DATE = "limit_alerted_date"

        /**
         * 上限超過の判定を1回走らせる。
         *
         * 収集の直後に呼ぶ。収集より先に呼ぶと、古い合計で判定することになる。
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LimitAlertWorker>()
                .expediteIfSupported()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                // 未実行のものが残っていれば置き換える（重複通知を防ぐ）。
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
