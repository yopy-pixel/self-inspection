package com.selfkaizen.app.collector

/**
 * 前回の収集から引き継ぐ未クローズ区間。
 */
data class SeedOpenInterval(
    val packageName: String,
    val startTime: Long
)

/** 区間構築の結果。 */
data class BuiltIntervals(
    val intervals: List<FocusInterval>,
    val screenOffPeriods: List<ScreenOffPeriod>
) {
    /**
     * 収集時点でまだ前面にあった区間（`finish` が STALE_SWEEP として閉じたもの）。
     *
     * これが次回の収集で引き継ぐ対象。
     * `persistIntervals` は STALE_SWEEP を `endTime = null` で保存するため、
     * DB 上ではこの区間だけが未クローズになる。
     */
    val stillOpen: FocusInterval?
        get() = intervals.lastOrNull { it.closeReason == CloseReason.STALE_SWEEP }
}

/**
 * イベント列から区間を構築する。
 *
 * Android に依存しない純粋関数なので JVM でテストできる。
 *
 * @param events 今回読み取った生イベント（時刻昇順）。
 * @param seedOpen 前回未クローズだった区間。**これが重要な修正点**（下記参照）。
 * @param now 収集時刻。
 */
fun buildIntervals(
    events: List<RawEvent>,
    seedOpen: SeedOpenInterval?,
    now: Long
): BuiltIntervals {
    val builder = FocusIntervalBuilder()

    // ------------------------------------------------------------------
    // 前回未クローズだった区間を引き継ぐ。
    //
    // なぜ必要か:
    // 収集は「前回カーソルから少し遡る」範囲しか読まない。
    // アプリを長時間使っていると、その RESUMED は今回の読み取り範囲より
    // 前に位置する。引き継がずにイベントだけを流すと、
    //
    //   - ビルダーは「前面のアプリ」を知らないまま
    //   - 後から届く PAUSED は openPackage が null なので無視される
    //   - 結果として区間が閉じられず、DB の行は endTime = null のまま残る
    //   - 12時間後の掃除（sweepStaleOpenEvents）で**まるごと削除される**
    //
    // つまり「15分以上連続して使ったアプリの記録が全部消える」。
    // 開始時刻を引き継ぐことで、PAUSED が正しく効くようになる。
    // ------------------------------------------------------------------
    if (seedOpen != null) {
        builder.seedOpenInterval(seedOpen.packageName, seedOpen.startTime)
    }

    for (event in events) {
        builder.onEvent(event)
    }

    return BuiltIntervals(
        intervals = builder.finish(now),
        screenOffPeriods = builder.screenOffPeriods()
    )
}

/**
 * 区間から画面 OFF / ロック期間を差し引く。
 *
 * 画面 OFF 中もアプリは「前面」のままなので、区間をそのまま計上すると
 * 実際には使っていない時間まで含まれてしまう。
 *
 * エミュレータでの実データ検証（2026-09-14）で、この除外がないと
 * 「設定」アプリが 562秒 と集計され、公式 `queryUsageStats` の 0.06秒 に対して
 * **約3000倍**に膨らむことを確認した。
 *
 * 差し引いた結果、1つの区間が複数に分かれることがある。
 *
 * @return 画面 OFF 期間を除いた区間列（元の順序を保つ）。
 */
fun subtractScreenOff(
    intervals: List<FocusInterval>,
    screenOff: List<ScreenOffPeriod>
): List<FocusInterval> {
    if (screenOff.isEmpty()) return intervals

    val result = mutableListOf<FocusInterval>()

    for (interval in intervals) {
        val end = interval.endTime ?: continue
        if (end <= interval.startTime) continue

        // 区間と重なる画面 OFF 期間を集めて、開始時刻順に処理する。
        val overlaps = screenOff
            .filter { it.endTime > interval.startTime && it.startTime < end }
            .sortedBy { it.startTime }

        var cursor = interval.startTime
        for (off in overlaps) {
            val offStart = maxOf(off.startTime, interval.startTime)
            val offEnd = minOf(off.endTime, end)

            // 画面 OFF の手前までを1区間として残す。
            if (offStart > cursor) {
                result += interval.copy(startTime = cursor, endTime = offStart)
            }
            cursor = maxOf(cursor, offEnd)
        }

        // 最後の画面 OFF より後ろを残す。
        if (cursor < end) {
            result += interval.copy(startTime = cursor, endTime = end)
        }
    }

    return result
}
