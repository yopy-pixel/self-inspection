package com.selfkaizen.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfkaizen.app.data.CollectionState
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.UsageDao
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 収集済みデータをサーバーへ送る Worker。
 *
 * 設計方針:
 *  - **ローカルが唯一の真実。** 送信に失敗しても端末内の記録は一切失わない。
 *  - **UI のクリティカルパスに置かない。** 表示はローカルの Room を読む。
 *  - **冪等。** `batchId` は内容から決定的に算出するため、再送しても
 *    サーバー側で二重計上しない（[BatchBuilder.batchIdFor]）。
 *  - **カーソル方式。** 前回送信した位置から（少し重ねて）取得する。
 *    全日を毎回送ると通信量が無駄になるため。
 *
 * **トークンをログに出さないこと。**
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SyncSettingsStore(applicationContext)
        val settings = store.load()

        // 同期が無効、または未設定なら何もしない（ローカル動作には影響しない）。
        if (!settings.isConfigured) {
            Log.i(TAG, "同期が未設定または無効のためスキップ")
            return Result.success()
        }

        val dao = DatabaseProvider.get(applicationContext).usageDao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        // 前回のカーソルから取得する。少し重ねるのは、後から確定した
        // endTime を送り直すため（サーバー側は UPSERT で更新する）。
        //
        // **送信形式の版が上がったときは、カーソルを無視して送り直す。**
        // 記録済みの行の意味が変わった場合、送り直さないと
        // サーバー側の古い値が永久に残る（[SyncRange] 参照）。
        val cursor = dao.getState(KEY_CURSOR)?.toLongOrNull()
        val storedVersion = dao.getState(KEY_PAYLOAD_VERSION)?.toIntOrNull()
        val from = SyncRange.startFrom(cursor, storedVersion, now)

        // **表示名はここで解決する。**
        // `app_usage_event.app_label` はパッケージ名を持つ（言語設定で
        // 変わらない安定した識別子）。サーバーには `PackageManager` が
        // 無いので、送る時点で表示名に直す。
        val labels = AppLabelResolver(applicationContext)

        val events = dao.eventsBetween(from, now)
        val syncEvents = BatchBuilder.splitByLocalDate(
            events.map {
                BatchBuilder.SourceInterval(
                    packageName = it.packageName,
                    appLabel = labels.label(it.packageName),
                    startTime = it.startTime,
                    endTime = it.endTime,
                    closeReason = it.closeReason,
                    // 収集時に確定した日付情報をそのまま渡す。
                    // 送信時点のタイムゾーンで再計算すると、
                    // 渡航後に過去データの日付が変わってしまう。
                    localDate = it.localDate,
                    zoneId = it.zoneId
                )
            },
            zone
        )

        if (syncEvents.isEmpty()) {
            // UI が読むのは時刻だけ（内訳は出さない）。
            dao.putState(CollectionState(KEY_LAST_SYNC_AT, now.toString()))
            Log.i(TAG, "同期結果: 送信対象なし")
            // 送るものが無くても版は記録する。記録しないと毎回
            // 同じ広い範囲を走査し続けることになる。
            dao.putState(
                CollectionState(KEY_PAYLOAD_VERSION, SyncRange.versionAfterSuccess().toString())
            )
            return Result.success()
        }

        val batches = BatchBuilder.buildBatches(settings.deviceId, syncEvents)
        val client = SyncClient()

        var inserted = 0
        var duplicates = 0
        var networkError = false
        var rejection: SyncResult.Rejected? = null

        for (batch in batches) {
            when (val r = client.send(settings, batch)) {
                is SyncResult.Accepted -> inserted += r.inserted
                is SyncResult.Duplicate -> duplicates++
                is SyncResult.NetworkError -> {
                    networkError = true
                    break
                }
                is SyncResult.Rejected -> {
                    rejection = r
                    break
                }
            }
        }

        // 送信の内訳（挿入/重複/バッチ）は**開発者向けの情報**なので
        // logcat にだけ出す。UI には「いつ同期したか」しか出さない
        // （利用者にとって意味があるのは成否と時刻だけ）。
        val summary = buildString {
            append("挿入=").append(inserted)
            append(" 重複=").append(duplicates)
            append(" バッチ=").append(batches.size)
            rejection?.let { append(" 拒否=").append(it.status) }
            if (networkError) append(" 通信失敗")
        }
        // UI が読むのは時刻だけ。
        dao.putState(CollectionState(KEY_LAST_SYNC_AT, now.toString()))
        Log.i(TAG, "同期結果: $summary")

        return when {
            // 設定かサーバーの問題。再試行しても直らないので失敗させる。
            rejection != null -> Result.failure()

            // 通信の問題。再試行する価値がある。
            networkError -> Result.retry()

            else -> {
                // 全バッチ成功。カーソルを進める。
                val newest = syncEvents.maxOf { it.startTime }
                dao.putState(CollectionState(KEY_CURSOR, newest.toString()))
                // **成功したときだけ版を記録する。** 失敗時に記録すると、
                // 送り直しが二度と起こらず古い値が残る。
                dao.putState(
                    CollectionState(KEY_PAYLOAD_VERSION, SyncRange.versionAfterSuccess().toString())
                )
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        const val PERIODIC_WORK_NAME = "sync_periodic"
        const val IMMEDIATE_WORK_NAME = "sync_immediate"

        /** 前回カーソルから遡る量（後から確定した値を送り直すため）。 */
        private const val OVERLAP_MILLIS = SyncRange.OVERLAP_MILLIS

        /** 初回同期で遡る量。 */
        private const val FIRST_SYNC_WINDOW_MILLIS = SyncRange.FIRST_SYNC_WINDOW_MILLIS

        /** 同期カーソル（前回送信した最新の startTime）。 */
        const val KEY_CURSOR = "sync_cursor"

        /**
         * 前回送信したときの送信形式の版。
         *
         * 送る中身の意味を変えたときに、送り直しを1回だけ起こすために持つ。
         * 未記録（初版以前）は 0 として扱う。
         */
        const val KEY_PAYLOAD_VERSION = "sync_payload_version"

        /** 最終同期時刻。UI 表示用。 */
        const val KEY_LAST_SYNC_AT = "sync_last_at"
    }
}

/** 同期のスケジューリング。 */
object SyncScheduler {

    /**
     * 12時間ごとの定期同期を登録する。
     *
     * `PLAN-android.md` Phase 3 の方針:
     * 同期の目的は「サーバーを最新に保つこと」であり、UI の鮮度ではない。
     * アプリの表示は常にローカルを読むため、12時間で十分。
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    // ネットワークが必要。電池切れ間際には走らせない。
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            // KEEP にすることで、アプリ起動のたびに呼んでもスケジュールが
            // リセットされない（UPDATE だと一度も実行されないことがある）。
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** いますぐ1回同期する（アプリ起動時・設定変更時・手動実行）。 */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** 定期同期を止める（同期を無効化したとき）。 */
    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
    }
}
