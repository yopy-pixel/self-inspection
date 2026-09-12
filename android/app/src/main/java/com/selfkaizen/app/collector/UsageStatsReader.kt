package com.selfkaizen.app.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * Android の `UsageStatsManager` から生イベントを読み出す。
 *
 * このクラスだけが Android フレームワークに依存する。
 * 変換ロジック（[FocusIntervalBuilder]）は純粋なので JVM でテストできる。
 */
class UsageStatsReader(private val context: Context) {

    private val usm: UsageStatsManager?
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * 指定期間のイベントを読み出す。
     *
     * **必ず1日以内の範囲で呼ぶこと。**
     * `queryEvents` は長期の一括取得で空を返したり OOM したりする報告があり、
     * また保持期間自体が「数日」のため、長期間を一度に取る意味がない。
     *
     * @return 時刻昇順のイベント列
     */
    fun read(from: Long, to: Long): List<RawEvent> {
        val manager = usm ?: return emptyList()
        val events = manager.queryEvents(from, to) ?: return emptyList()

        val result = ArrayList<RawEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            result += RawEvent(
                eventType = event.eventType,
                packageName = event.packageName,
                timestamp = event.timeStamp,
                className = event.className
            )
        }
        return result
    }

    /**
     * 期間を最大1日ずつに分割して読み出す。
     *
     * 長期一括取得の問題を避けるため、必ず分割する。
     */
    fun readChunked(from: Long, to: Long, maxChunkMillis: Long = ONE_DAY_MILLIS): List<RawEvent> {
        if (to <= from) return emptyList()
        val result = ArrayList<RawEvent>()
        var cursor = from
        while (cursor < to) {
            val chunkEnd = minOf(cursor + maxChunkMillis, to)
            result += read(cursor, chunkEnd)
            cursor = chunkEnd
        }
        // チャンクをまたいだ並びを保証する。
        return result.sortedBy { it.timestamp }
    }

    /**
     * 公式の集計値（照合・検証用）。
     *
     * 自前集計の正しさを検証する唯一の客観的な手段。
     * アプリの画面には出さないが、ログに残して精度を監視する。
     */
    fun officialForegroundMillis(from: Long, to: Long): Map<String, Long> {
        val manager = usm ?: return emptyMap()
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
            ?: return emptyMap()
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .associate { it.packageName to it.totalTimeInForeground }
    }

    companion object {
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000

        /**
         * `queryEvents` の保持期間は「数日」と公式に明記されているが、
         * 正確な日数は非公開。安全側に倒して3日を上限の目安とする。
         * これを超えて遡る取得は欠損を前提とする。
         */
        const val SAFE_LOOKBACK_MILLIS = 3L * ONE_DAY_MILLIS

        /** Event.getInstanceId() が使えるか（@hide のため実質使えない）。 */
        val supportsInstanceId: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
