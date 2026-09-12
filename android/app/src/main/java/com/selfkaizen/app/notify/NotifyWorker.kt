package com.selfkaizen.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfkaizen.app.collector.UsageAccess
import com.selfkaizen.app.collector.UsageCollector
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.SettingsRepository
import com.selfkaizen.app.data.TodayUsageRepository
import com.selfkaizen.app.data.unknownTodayUsage
import java.time.LocalDate

/**
 * 12時間ごとの通知を出す Worker。
 *
 * **順序が本質: 収集 → 判定 → 通知。**
 * 収集を飛ばして保存済みの値で通知すると、
 * 「3時間前は超過していた」という**過去の事実**を
 * 「いま超過している」として伝えることになる。
 * 通知は介入なので、ここで嘘をつくと行動を誤らせる。
 *
 * 収集に失敗した場合は保存済みの値で判定を続ける。
 * その場合データが古いので、[com.selfkaizen.app.data.condition] により
 * `STALE` となり「収集が止まっている」という**事実**が通知される
 * （超過とは言わない）。
 *
 * なお、定期収集 ([com.selfkaizen.app.collector.UsageCollectorWorker]) と
 * 同時に走る可能性がある。収集は冪等（挿入は IGNORE、集計は日全体の再生成）
 * なので、重複しても結果は変わらない。
 */
class NotifyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val hasPermission = UsageAccess.hasPermission(context)

        val db = DatabaseProvider.get(context)
        val dao = db.usageDao()
        val repository = TodayUsageRepository(dao, SettingsRepository(dao))

        // 1. 収集（通知の直前に必ず行う）
        if (hasPermission) {
            runCatching { UsageCollector(context, db).collect() }
                .onSuccess { Log.i(TAG, "通知前の収集: ${it.summary()}") }
                .onFailure { Log.w(TAG, "通知前の収集に失敗。保存済みの値で判定する", it) }
        }

        // 2. 判定
        val today = runCatching { repository.load() }
            .getOrElse {
                Log.w(TAG, "使用状況を読めなかった", it)
                unknownTodayUsage()
            }

        val yesterday = runCatching {
            repository.totalFor(LocalDate.now().minusDays(1))
        }.getOrElse {
            Log.w(TAG, "昨日の合計を読めなかった", it)
            null
        }

        val content = NotifyPolicy.decide(
            today = today,
            yesterdayMillis = yesterday,
            hasPermission = hasPermission
        )

        // 3. 通知
        val notifier = UsageNotifier(context)
        // 前回の通知は12時間前の情報。残すと嘘になるので必ず取り下げる。
        notifier.cancel()
        if (content == null) {
            Log.i(TAG, "伝えることが無いため通知しない")
        } else {
            notifier.notify(content)
            Log.i(TAG, "通知: $content")
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "NotifyWorker"

        /** 多重登録を防ぐための一意名。 */
        const val WORK_NAME = "usage_notification"

        /**
         * いますぐ通知判定を1回走らせる。
         *
         * `AlarmManager` のブロードキャストから呼ぶ。
         * `BroadcastReceiver` は約10秒で殺されるため、
         * DB と `UsageStats` を触る処理を直接書いてはいけない。
         * WorkManager に渡すことで、実行の保証とリトライを得る。
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotifyWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                // 未実行のものが残っていれば置き換える（重複通知を防ぐ）。
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
