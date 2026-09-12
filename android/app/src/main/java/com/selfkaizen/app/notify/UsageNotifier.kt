package com.selfkaizen.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.selfkaizen.app.MainActivity
import com.selfkaizen.app.R
import com.selfkaizen.app.ui.DurationFormat

/**
 * 通知の表示。
 *
 * 文言の組み立てだけを担う。**何を伝えるかの判断は [NotifyPolicy]** にあり、
 * そちらは Android に依存しない純粋なロジックとしてテストしてある。
 *
 * 通知は「気づく機会」そのものなので、**嘘を出さない**ことを最優先にする。
 * 権限が無い場合は数字を一切出さない（[NotifyContent.NoPermission] が
 * そもそも数字を持たないため、構造的に出せない）。
 */
class UsageNotifier(private val context: Context) {

    /**
     * 前回の通知を取り下げる。
     *
     * **12時間前の通知を残してはいけない。**
     * 朝に出た「18m left」は、夜には事実と異なる。
     * 消し忘れた通知は、無いより悪い（誤った安心を与える）。
     */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * 通知を表示する。
     *
     * @return 実際に表示したか。**権限が無い場合は false。**
     *   「出していないのに通知済みと記録する」事故を防ぐため、
     *   呼び出し側が結果を見られるようにしている。
     */
    fun notify(content: NotifyContent): Boolean {
        if (!canPost()) {
            // API 33+ で未許可。通知は黙って捨てられるため、ログだけ残す。
            Log.i(TAG, "通知が許可されていないため表示しない: $content")
            return false
        }

        ensureChannel()

        val title = context.getString(titleOf(content))
        val body = bodyOf(content)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // 展開時に「4h 00m のうち 3h 42m」まで出す。
            // 折りたたみ時は残り時間だけ（一目で足りる情報を優先）。
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailOf(content, body)))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    // ------------------------------------------------------------------

    private fun titleOf(content: NotifyContent): Int = when (content) {
        is NotifyContent.PastDay -> R.string.notification_title_yesterday
        else -> R.string.notification_title_today
    }

    /** 折りたたみ時の1行。 */
    private fun bodyOf(content: NotifyContent): String = when (content) {
        is NotifyContent.NoPermission ->
            context.getString(R.string.notification_no_permission)

        is NotifyContent.Stalled -> content.forMillis
            ?.let { context.getString(R.string.widget_stale, DurationFormat.agoMillis(it)) }
            ?: context.getString(R.string.label_never_collected)

        is NotifyContent.NoLimit ->
            context.getString(R.string.state_disabled)

        is NotifyContent.Remaining ->
            context.getString(R.string.state_left, DurationFormat.long(content.remainingMillis))

        is NotifyContent.Over ->
            context.getString(R.string.state_over, DurationFormat.long(content.overMillis))

        is NotifyContent.PastDay ->
            context.getString(R.string.notification_used, DurationFormat.long(content.usedMillis))
    }

    /** 展開時の本文。合計と上限まで出す。 */
    private fun detailOf(content: NotifyContent, body: String): String = when (content) {
        is NotifyContent.Remaining -> context.getString(
            R.string.notification_detail,
            DurationFormat.long(content.usedMillis),
            DurationFormat.limit(content.limitMillis)
        )

        is NotifyContent.Over -> context.getString(
            R.string.notification_detail,
            DurationFormat.long(content.usedMillis),
            DurationFormat.limit(content.limitMillis)
        )

        is NotifyContent.NoLimit ->
            context.getString(R.string.notification_used, DurationFormat.long(content.usedMillis))

        else -> body
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            // FLAG_IMMUTABLE は API 23+ で必須（未指定だと API 31+ で例外）。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                // IMPORTANCE_DEFAULT: 音は鳴るが、ヘッドアップ表示はしない。
                // 「気づく」ことは目的だが、作業を中断させるほどではない。
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(true)
            }
        )
    }

    /** 通知を出せるか。API 33+ では実行時許可が必要。 */
    fun canPost(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // API 32 以下は常に許可されている（インストール時に付与）。
            true
        }

    companion object {
        private const val TAG = "UsageNotifier"

        /** 通知チャンネル ID。 */
        const val CHANNEL_ID = "usage_status"

        /**
         * 通知 ID。
         *
         * **固定することで、常に最新の1件だけが残る。**
         * 過去の通知が積み上がると、古い数字が目に入り続ける。
         */
        const val NOTIFICATION_ID = 1001
    }
}
