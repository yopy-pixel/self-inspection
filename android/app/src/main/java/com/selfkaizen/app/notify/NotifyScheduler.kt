package com.selfkaizen.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.time.Instant
import java.time.ZoneId

/**
 * 通知のスケジューリング。
 *
 * **なぜ `WorkManager` ではなく `AlarmManager` か。**
 * `WorkManager` の定期実行は「前回の実行から12時間後」であり、
 * 壁時計の時刻を指定できない。今回は「朝と夜に1回ずつ」という
 * **時刻そのもの**が意味を持つ（振り返りの習慣にするため）ので、
 * 時刻を指定できる `AlarmManager` を使う。
 *
 * **なぜ正確なアラーム（`setExactAndAllowWhileIdle`）を使わないか。**
 * `SCHEDULE_EXACT_ALARM` 権限が必要で、ユーザーに追加の許可を求めることになる。
 * 振り返りが数十分ずれても目的は果たせる。
 *
 * **なぜ `setInexactRepeating` を使わないか（エミュレータで確認した問題）。**
 * 24時間間隔の `setInexactRepeating` は、OS が
 * **最大18時間遅らせてよい**アラームとして登録する。
 * エミュレータ（Android 16）の `dumpsys alarm` で実際に
 * `window=+18h0m0s0ms` となることを確認した。
 * これでは「9時の通知」が翌3時に来ることがあり、
 * **時刻を指定する意味が無い**（睡眠中に鳴る方が害が大きい）。
 *
 * そこで **1回だけの `setAndAllowWhileIdle` を都度張り直す**。
 * これは Doze 中でも発火を許される（頻度制限は9分に1回で、
 * 12時間間隔の用途では問題にならない）。
 * Doze でも「できるだけ早く」発火するため、18時間の遅延は起こらない。
 *
 * 張り直しは [NotifyAlarmReceiver] で行う。
 * **Worker の成否に依存させない**（受信は必ず走るが、Worker は遅延しうるため）。
 */
object NotifyScheduler {

    /** 通知する時刻（時）。朝と夜の2回 = 12時間間隔。 */
    val HOURS = intArrayOf(9, 21)

    /**
     * 次に来る通知時刻を登録する。
     *
     * 同じ `PendingIntent` への再登録は置き換えになるので、
     * アプリ起動のたびに呼んでも多重にはならない。
     */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTrigger(now, ZoneId.systemDefault(), HOURS)

        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                alarmIntent(context)
            )
        }.onFailure {
            Log.w(TAG, "アラームを登録できなかった", it)
        }

        Log.i(TAG, "次回の通知を登録: ${Instant.ofEpochMilli(triggerAt)}")
    }

    /** 登録を取り消す（通知を無効にしたとき用）。 */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(alarmIntent(context))
    }

    /**
     * 指定した複数の時刻のうち、`now` より後に来る**最も早い**もの。
     *
     * これが「朝と夜の2回」を1本のアラームで回すための入口。
     */
    fun nextTrigger(now: Long, zone: ZoneId, hours: IntArray): Long =
        hours.minOf { nextTrigger(now, zone, it) }

    /**
     * 次にその時刻が来る瞬間。
     *
     * ちょうどその時刻なら**翌日**にする。そのアラームは既に過ぎており、
     * 同じ瞬間を返すと即発火を繰り返すことになる。
     * `now` と `zone` を受け取るのはテストのため。
     */
    fun nextTrigger(now: Long, zone: ZoneId, hour: Int): Long {
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZoned.toLocalDate().atTime(hour, 0).atZone(zone)
        val target = if (today.toInstant().isAfter(Instant.ofEpochMilli(now))) {
            today
        } else {
            today.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotifyAlarmReceiver::class.java)
            .setAction(ACTION_TICK)
            // 他の PendingIntent と衝突しないための data。
            .setData(Uri.parse("selfkaizen://notify/tick"))

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val TAG = "NotifyScheduler"
    private const val ACTION_TICK = "com.selfkaizen.app.action.NOTIFY_TICK"
    private const val REQUEST_CODE = 0
}

/**
 * アラームを受け取って通知処理を起動する。
 *
 * **ここで DB や `UsageStats` を触らない。**
 * `BroadcastReceiver` は約10秒で強制終了され、
 * 途中で殺されると中途半端な状態が残る。
 * 実行の保証とリトライは `WorkManager` に任せる。
 */
class NotifyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "通知時刻: ${intent.action}")

        // **次のアラームを先に張る。**
        // 1回だけのアラームなので、ここで張り直さないと次が永遠に来ない。
        // Worker の中で張ると、Worker が失敗・遅延したときに連鎖が切れる。
        // 受信は必ず走るので、ここが最も確実な場所。
        NotifyScheduler.schedule(context)

        NotifyWorker.enqueue(context)
    }

    companion object {
        private const val TAG = "NotifyAlarmReceiver"
    }
}
