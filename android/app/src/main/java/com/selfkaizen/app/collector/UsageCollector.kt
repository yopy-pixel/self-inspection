package com.selfkaizen.app.collector

import android.content.Context
import com.selfkaizen.app.data.AppDatabase
import com.selfkaizen.app.data.AppUsageEvent
import com.selfkaizen.app.data.CollectionKeys
import com.selfkaizen.app.data.CollectionState
import com.selfkaizen.app.data.DailySummary

/**
 * 収集の本体。UsageEvents を読み、区間へ変換し、冪等に保存する。
 *
 * 冪等性が最重要の要件:
 * WorkManager は遅延・再実行・重複実行があり得る。
 * 同じ期間を何度収集しても結果が変わらないこと。
 *
 * 差分収集:
 * 前回の収集完了時刻をカーソルとして保存し、そこから先だけを読む。
 * `queryEvents` は数日分しか保持しないため、これが欠損を防ぐ唯一の方法。
 */
class UsageCollector(
    private val context: Context,
    private val db: AppDatabase,
    private val reader: UsageStatsReader = UsageStatsReader(context),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 収集時点のタイムゾーン。
     *
     * 日付を「いつ収集したか」の時点で確定させるために使う。
     * 集計時に評価すると、渡航・夏時間・手動変更のあとに
     * 過去のデータが別の日へ移ってしまう。
     * テストから固定値を注入できるようにここで受け取る。
     */
    private val zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault
) {

    /**
     * 収集を1回実行する。
     *
     * @return 収集結果の要約（ログ・検証用）
     */
    suspend fun collect(): CollectionResult {
        val dao = db.usageDao()
        val currentTime = now()

        // カーソルが無ければ、安全に遡れる範囲から始める。
        val cursorRaw = dao.getState(CollectionKeys.LAST_COLLECTED_AT)
        val cursor = cursorRaw?.toLongOrNull()
            ?: (currentTime - UsageStatsReader.SAFE_LOOKBACK_MILLIS)

        // 境界の重複を避けるため、カーソルから少しだけ重ねて読む。
        // 前回の収集時に未確定だった区間（PAUSED がまだ来ていない）が、
        // 今回の収集で確定する可能性があるため。
        val from = (cursor - OVERLAP_MILLIS).coerceAtLeast(
            currentTime - UsageStatsReader.SAFE_LOOKBACK_MILLIS
        )

        // 1日単位に分割して読む（長期一括取得を避ける）。
        val events = reader.readChunked(from, currentTime)

        // 前回未クローズだった区間を取得する。
        // これを引き継がないと、長時間使ったアプリの PAUSED が効かず、
        // 12時間後の掃除で記録がまるごと消える（詳細は buildIntervals のコメント）。
        val seedOpen = dao.openEvents()
            .firstOrNull()
            ?.let { SeedOpenInterval(it.packageName, it.startTime) }

        // 区間へ変換する（画面 OFF 期間も同時に得る）。
        val built = buildIntervals(events, seedOpen, currentTime)

        // 保存する。
        val inserted = persistIntervals(built.intervals)

        // 画面 OFF / ロック期間を保存する（集計時の差し引きに使う）。
        persistScreenOffPeriods(built.screenOffPeriods)

        // 古すぎる未クローズ区間を掃除する。
        // 電源断などで閉じイベントを失った区間が永久に残るのを防ぐ。
        val swept = sweepStaleOpenEvents(currentTime, built.stillOpen)

        // 日次集計を再生成する（生イベントから再生成可能であること）。
        val summaries = rebuildDailySummaries(from, currentTime)

        // カーソルを進める。
        // 「今」ではなく「今」を保存する。次回はここから重ねて読む。
        dao.putState(
            CollectionState(CollectionKeys.LAST_COLLECTED_AT, currentTime.toString())
        )

        return CollectionResult(
            eventsRead = events.size,
            intervalsBuilt = built.intervals.size,
            eventsInserted = inserted,
            staleSwept = swept,
            summaryRows = summaries,
            from = from,
            to = currentTime
        )
    }

    /**
     * 画面 OFF / ロック期間を保存する。
     *
     * 重複は一意インデックスではなく `IGNORE` で防ぐ。
     * 同じ期間が再検出されても件数は増えない。
     */
    private suspend fun persistScreenOffPeriods(periods: List<ScreenOffPeriod>) {
        if (periods.isEmpty()) return
        val rows = periods.map {
            com.selfkaizen.app.data.ScreenOffPeriodEntity(
                startTime = it.startTime,
                endTime = it.endTime
            )
        }
        db.usageDao().insertScreenOffPeriods(rows)
    }

    /**
     * 区間を冪等に保存する。
     *
     * 未確定（STALE_SWEEP）の区間は **endTime = null（未クローズ）** として保存し、
     * 後で本当の終了点が判明したときに更新する。
     *
     * なぜ確定値をそのまま入れないか:
     * STALE_SWEEP の endTime は「収集時刻」という推定値にすぎない。
     * これを確定値として保存すると、次回以降に本当の PAUSED が来ても
     * 上書きされず、誤った値が永続化してしまう。
     */
    private suspend fun persistIntervals(intervals: List<FocusInterval>): Int {
        val dao = db.usageDao()

        // 日付とタイムゾーンを「収集した今」の時点で確定させる。
        // 集計時に評価すると、タイムゾーンが変わった後で
        // 過去のイベントが別の日へ再配分されてしまう。
        val zoneId = zone().id

        val rows = intervals.map { interval ->
            val isStale = interval.closeReason == CloseReason.STALE_SWEEP
            AppUsageEvent(
                packageName = interval.packageName,
                appLabel = interval.packageName, // 表示名は UI 側で解決
                startTime = interval.startTime,
                endTime = if (isStale) null else interval.endTime,
                closeReason = (interval.closeReason ?: CloseReason.STALE_SWEEP).name,
                localDate = DailyAggregator.toLocalDate(interval.startTime, zone()),
                zoneId = zoneId
            )
        }
        if (rows.isEmpty()) return 0

        // 新規のみ挿入（既存は無視）。
        val ids = dao.insertEvents(rows)
        val inserted = ids.count { it != -1L }

        // 既存の未クローズ区間のうち、今回の収集で終了点が判明したものを閉じる。
        // insertEvents は IGNORE なので、ここで明示的に更新する必要がある。
        for (interval in intervals) {
            val end = interval.endTime ?: continue
            if (interval.closeReason == CloseReason.STALE_SWEEP) continue
            dao.closeOpenEvent(
                packageName = interval.packageName,
                startTime = interval.startTime,
                endTime = end,
                closeReason = (interval.closeReason ?: CloseReason.PAUSED).name
            )
        }
        return inserted
    }

    /**
     * 古すぎる未クローズ区間を掃除する。
     *
     * シャットダウンや強制終了で閉じイベントを失った区間は、
     * 閉じ時刻が不明なため破棄するのが公式の推奨
     * （`UsageEvents.Event.DEVICE_SHUTDOWN` の Javadoc）。
     *
     * @return 削除した件数
     */
    private suspend fun sweepStaleOpenEvents(currentTime: Long, keep: FocusInterval?): Int {
        val dao = db.usageDao()

        // 「前面にいられるアプリは常に1つ」なので、未クローズ区間も本来1件以下。
        // 今回の収集で引き継いだ区間以外は放棄されたものとして破棄する。
        //
        // 実データ検証（Android 16, 2026-09-14）で、再起動をまたいだ区間が
        // 未クローズのまま残り、2件になることを確認した。
        // DEVICE_SHUTDOWN をまたぐ区間は閉じ時刻が不明なため、
        // 公式に「無視すべき」とされている。よって破棄が正しい。
        val openBefore = dao.openEvents()
        dao.deleteOpenEventsExcept(keep?.packageName, keep?.startTime)
        var swept = openBefore.count { it.startTime != keep?.startTime || it.packageName != keep?.packageName }

        // 12時間を超えて開いたままの区間も破棄する（電源断などで
        // 閉じイベントを失った場合の最終的な安全弁）。
        val threshold = currentTime - MAX_OPEN_INTERVAL_MILLIS
        for (open in dao.openEvents()) {
            if (open.startTime < threshold) {
                dao.deleteEvent(open.id)
                swept++
            }
        }
        return swept
    }

    /**
     * 日次集計を生イベントから作り直す。
     *
     * 集計はあくまでキャッシュであり、生イベントが真実の源。
     * これにより「閾値を後から変えて過去を再評価する」ことが可能になる。
     *
     * **重要（実データ検証で発見したバグの修正）:**
     * 集計は必ず「日全体」で行う。収集の窓（直近15分など）だけで集計して
     * 日次を置換すると、`replaceDailySummaries` は日全体を削除するため、
     * **その日の他の時間帯のデータが毎回消える**。
     * 実測で、生イベントの合計30秒に対して集計が13秒になり、
     * 17秒が失われていた。
     */
    private suspend fun rebuildDailySummaries(from: Long, to: Long): Int {
        val dao = db.usageDao()

        // 窓ではなく「日全体」に広げる。
        val range = fullDayRange(from, to)
        val events = dao.eventsBetween(range.first, range.last + 1)
        if (events.isEmpty()) return 0

        // 画面 OFF 期間も同じ範囲で取得する。
        val screenOff = dao.screenOffPeriodsBetween(range.first, range.last + 1)
            .map { ScreenOffPeriod(it.startTime, it.endTime) }

        // 純粋関数で集計する（画面 OFF の差し引きと日付分割を含む）。
        // 保存済みの localDate / zoneId を使う。
        // 集計時のタイムゾーンで計算し直すと、渡航後に過去の日付が変わる。
        // 空（マイグレーション前の古い行）は aggregateDailyDated 側で
        // システム既定にフォールバックする。
        val dated = events.map {
            DatedInterval(
                interval = FocusInterval(
                    packageName = it.packageName,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    closeReason = null
                ),
                localDate = it.localDate,
                zoneId = it.zoneId
            )
        }
        val aggregates = aggregateDailyDated(dated, screenOff)

        var rows = 0
        for ((date, items) in aggregates.groupBy { it.date }) {
            val summaries = items.map {
                DailySummary(
                    date = it.date,
                    packageName = it.packageName,
                    appLabel = it.packageName,
                    totalMillis = it.totalMillis,
                    launchCount = it.launchCount
                )
            }
            dao.replaceDailySummaries(date, summaries)
            rows += summaries.size
        }
        return rows
    }

    companion object {
        /**
         * カーソルから遡って重ねて読む幅。
         *
         * 前回の収集時に未確定だった区間が、今回確定する可能性を拾うため。
         * 5分あれば通常のアプリ切替は十分カバーできる。
         */
        const val OVERLAP_MILLIS = 5L * 60 * 1000

        /**
         * 未クローズ区間を破棄するまでの猶予。
         *
         * これを超えて開いたままの区間は、閉じ時刻が不明として破棄する。
         * 12時間あれば「アプリを使ったまま端末を放置」は十分カバーできる。
         */
        const val MAX_OPEN_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
    }
}

/** 収集1回分の結果。ログと検証に使う。 */
data class CollectionResult(
    val eventsRead: Int,
    val intervalsBuilt: Int,
    val eventsInserted: Int,
    val staleSwept: Int,
    val summaryRows: Int,
    val from: Long,
    val to: Long
) {
    fun summary(): String =
        "events=$eventsRead intervals=$intervalsBuilt inserted=$eventsInserted " +
            "swept=$staleSwept summaries=$summaryRows " +
            "range=${DailyAggregator.toLocalDate(from)}..${DailyAggregator.toLocalDate(to)}"
}
