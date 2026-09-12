package com.selfkaizen.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.selfkaizen.app.collector.CollectionScheduler
import com.selfkaizen.app.notify.NotifyScheduler

/**
 * 端末再起動後に収集スケジュールを再登録する。
 *
 * 補足: WorkManager 自体も再起動後にスケジュールを復元する仕組みを持つが、
 * 明示的に再登録しておくことで、
 *  - WorkManager の DB が何らかの理由で失われた場合
 *  - アプリが強制停止された後
 * にも収集が再開される。
 *
 * `ExistingPeriodicWorkPolicy.KEEP` を使っているため、
 * 既にスケジュール済みでも二重登録にはならない。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        Log.i(TAG, "再起動を検知。収集スケジュールを再登録する: ${intent.action}")

        // 定期収集を再登録（既存があれば KEEP される）。
        CollectionScheduler.schedulePeriodic(context)

        // **アラームは端末再起動で消える。** 明示的に再登録しないと
        // 12時間ごとの通知が二度と来なくなる（WorkManager と違い
        // AlarmManager は復元の仕組みを持たない）。
        NotifyScheduler.schedule(context)

        // 再起動中に溜まったイベントをすぐ取り込む。
        // 起動直後は UsageStats サービスが未準備の可能性があるため、
        // Worker 側の失敗時 retry に委ねる。
        CollectionScheduler.collectNow(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
