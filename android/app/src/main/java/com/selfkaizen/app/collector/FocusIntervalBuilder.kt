package com.selfkaizen.app.collector

/**
 * UsageEvents のイベント種別。
 *
 * IMPORTANT: ACTIVITY_RESUMED / ACTIVITY_PAUSED は API 29 で追加されたが、
 * AOSP 実ソース（sources/android-34/.../UsageEvents.java）で
 *   public static final int MOVE_TO_FOREGROUND = 1;
 *   public static final int ACTIVITY_RESUMED = MOVE_TO_FOREGROUND;
 *   public static final int MOVE_TO_BACKGROUND = 2;
 *   public static final int ACTIVITY_PAUSED = MOVE_TO_BACKGROUND;
 * と別名定義されており値は同一。定数はコンパイル時にインライン展開されるため、
 * minSdk 26 でもそのまま参照してよい（実行時分岐は不要）。
 */
object UsageEventType {
    /** 前面に移動（= MOVE_TO_FOREGROUND, 値1）。使用開始。 */
    const val ACTIVITY_RESUMED = 1

    /** 背景に移動（= MOVE_TO_BACKGROUND, 値2）。使用終了。 */
    const val ACTIVITY_PAUSED = 2

    /**
     * 日付境界で stats がロールオーバーした際、前面にいたコンポーネントを閉じるイベント。
     * AOSP では ACTIVITY_PAUSED 相当として扱われる。
     *
     * {@code @hide} のため定数名を参照できず、リテラル値を使う必要がある。
     * 由来: `UsageEvents.Event.END_OF_DAY = 3`
     *
     * **【重要・2026-09-14 に AOSP 実装で確定】このイベントは `queryEvents()` には
     * 決して現れない。** `UserUsageStatsService.rolloverStats()` は
     * `IntervalStats.update(...)` を呼ぶだけで、イベントログに書く `addEvent()` を
     * 呼んでいない（`android-33` のソースで確認）。`update()` は集計値のみを更新する。
     *
     * したがって以下の処理は**実際には到達しない防御コード**である。
     * 残しているのは、将来のバージョンや OEM 差異で公開された場合の安全のため。
     *
     * **日付境界をまたぐ使用の分割は `DailyAggregator` が区間を日付で切り分けて行う。**
     * そちらが本来の機構であり、テストで担保されている。
     */
    const val END_OF_DAY = 3

    /**
     * 前日から前面にいたコンポーネントが継続していることを示すイベント。
     * AOSP では ACTIVITY_RESUMED 相当として扱われる。
     *
     * {@code @hide} のためリテラル値を使う。
     * 由来: `UsageEvents.Event.CONTINUE_PREVIOUS_DAY = 4`
     *
     * **【重要・2026-09-14 に AOSP 実装で確定】Android 13 (API 33) のソースでは
     * この定数はコメントと名前マップにしか現れず、発行箇所が存在しない。**
     * `queryEvents()` には現れないため、以下の処理は到達しない。
     */
    const val CONTINUE_PREVIOUS_DAY = 4

    /**
     * デバイスがシャットダウンした。この時刻は「UsageStats DB が最後に永続化された時刻」で
     * あり実際の停止時刻ではない。DEVICE_SHUTDOWN〜DEVICE_STARTUP 間の
     * 対応する閉じイベントを持たない開区間は、閉じ時刻が不明なため破棄する。
     * 由来: UsageEvents.Event.DEVICE_SHUTDOWN = 26
     */
    const val DEVICE_SHUTDOWN = 26

    /** デバイスが起動した。由来: UsageEvents.Event.DEVICE_STARTUP = 27 */
    const val DEVICE_STARTUP = 27

    /**
     * 画面が操作可能になった。由来: UsageEvents.Event.SCREEN_INTERACTIVE = 15
     * packageName は "android"（DEVICE_EVENT_PACKAGE_NAME）。
     */
    const val SCREEN_INTERACTIVE = 15

    /**
     * 画面が完全に OFF、または ambient 表示のみ。
     * 由来: UsageEvents.Event.SCREEN_NON_INTERACTIVE = 16
     *
     * **エミュレータでの実データ検証（2026-09-13）で、このイベントが
     * 過大計上を防ぐために必須であることが判明した。**
     * 実測で「設定」アプリの自前集計 188秒 に対し
     * `queryUsageStats` は 0.06秒 という 3000倍の乖離が出た。
     * 原因は、この時間帯に画面 OFF があったにもかかわらず
     * RESUMED→PAUSED の区間をそのまま計上していたこと。
     */
    const val SCREEN_NON_INTERACTIVE = 16

    /** キーガード（ロック画面）が表示された。由来: KEYGUARD_SHOWN = 17 */
    const val KEYGUARD_SHOWN = 17

    /** キーガードが解除された。由来: KEYGUARD_HIDDEN = 18 */
    const val KEYGUARD_HIDDEN = 18

    /** デバイスレベルのイベントに付与される packageName。 */
    const val DEVICE_EVENT_PACKAGE_NAME = "android"
}

/**
 * 区間をどう閉じたか。これを記録しないと「実測値」と「欠損」を区別できない。
 */
enum class CloseReason {
    /** ACTIVITY_PAUSED により正常に閉じた。 */
    PAUSED,

    /** 次アプリの ACTIVITY_RESUMED により暗黙に閉じた（PAUSED が来なかった）。 */
    NEXT_RESUMED,

    /** 日付境界（END_OF_DAY）で閉じた。**到達しない防御コード**（型コメント参照）。 */
    END_OF_DAY,

    /** シャットダウンにより閉じた。endTime は不明なため区間を破棄する。 */
    DEVICE_SHUTDOWN,

    /**
     * 収集時に未クローズ区間を検出して打ち切った。
     * endTime は推定値であり、実測ではない。
     */
    STALE_SWEEP
}

/** 収集対象の生イベント。 */
data class RawEvent(
    val eventType: Int,
    val packageName: String?,
    val timestamp: Long,
    /**
     * アクティビティのクラス名（`UsageEvents.Event.getClassName()`）。
     *
     * **これが集計精度の鍵。** プラットフォームの公式実装
     * （`UsageStats.updateActivity()`）はアクティビティ単位で状態を追跡し、
     * 同じアクティビティが RESUMED の間だけ使用時間を加算する。
     *
     * パッケージ単位の単純な追跡では、1つのアプリが複数のアクティビティを
     * 持つ場合（設定アプリ・ランチャー等）に過大計上する。
     * 実データ検証で settings 1.58倍 / launcher 4.75倍の乖離が出た。
     *
     * 注意: 公式実装は `instanceId` を使うが、`Event.getInstanceId()` は
     * `@hide` / `@SystemApi` であり公開SDKからは参照できない
     * （android.jar に存在しないことを確認済み）。
     * そのため公開APIである className で代用する。
     * null / 空の場合はパッケージ単位のフォールバックを使う。
     */
    val className: String? = null
)

/** 確定した前面滞在区間。 */
data class FocusInterval(
    val packageName: String,
    val startTime: Long,
    val endTime: Long?,
    val closeReason: CloseReason?
) {
    /** 使用時間（ミリ秒）。endTime が未確定なら 0。 */
    val durationMillis: Long
        get() = endTime?.let { (it - startTime).coerceAtLeast(0L) } ?: 0L
}

/**
 * 画面 OFF / ロック中に経過した時間を除外するための区間。
 *
 * 用途: FocusInterval から差し引く「使用していない時間帯」。
 */
data class ScreenOffPeriod(
    val startTime: Long,
    val endTime: Long
)

/**
 * UsageEvents の生イベント列を、前面アプリの滞在区間列に変換するステートマシン。
 *
 * 設計上の要点:
 *  - アプリ切替時に PAUSED が来る前に次の RESUMED が来ることがある。
 *    「前の区間が未クローズなら、新 RESUMED の時刻で暗黙に閉じてから新規開始」する。
 *  - 終了点は PAUSED のみを使う。STOPPED/DESTROYED を混ぜると過大計上になる。
 *  - END_OF_DAY は閉じ、CONTINUE_PREVIOUS_DAY は開きとして扱う（日付境界）。
 *    **ただし両者は `queryEvents()` には現れない**ため、この経路は到達しない。
 *    日付境界の分割は `DailyAggregator` が区間を日付で切り分けて行う。
 *  - DEVICE_SHUTDOWN をまたぐ開区間は破棄する（閉じ時刻が不明なため）。
 *  - **SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN の区間を差し引く。**
 *    画面 OFF やロック中は「アプリが前面のまま」でも使用時間ではない。
 *    これを除外しないと実測で 3000 倍の過大計上が出る（下記参照）。
 *
 * このクラスは Android フレームワークに依存しない純粋なロジックなので、
 * JVM ユニットテストで境界ケースを検証できる。
 */
class FocusIntervalBuilder {

    private val intervals = mutableListOf<FocusInterval>()

    /** 画面 OFF / ロック中だった期間。使用時間から差し引く。 */
    private val screenOffPeriods = mutableListOf<ScreenOffPeriod>()

    /**
     * アクティビティインスタンスID → そのインスタンスの現在の状態。
     *
     * プラットフォームの `UsageStats.updateActivity()` と同じ方式。
     * パッケージ単位ではなくインスタンス単位で追跡することが精度の鍵。
     */
    private val activityState = mutableMapOf<String, Int>()

    /** アクティビティキー → packageName。状態更新時にパッケージを引くために必要。 */
    private val activityPackage = mutableMapOf<String, String>()

    /** 現在「前面」とみなしているパッケージ。区間の生成単位。 */
    private var openPackage: String? = null
    private var openStart: Long = 0L

    /** 画面 OFF / ロックが start から続いているか。 */
    private var screenOffStart: Long? = null

    /**
     * イベントを1件処理する。イベントは時刻昇順で渡すこと。
     *
     * 使用時間の加算判定は `UsageStats.updateActivity()` に倣い、
     * **instanceId 単位**で行う。パッケージ単位で「前のアプリを閉じる」方式だと、
     * 1つのアプリが複数のアクティビティを持つ場合（設定アプリ・ランチャー等）に
     * 過大計上する。実測で settings が 1.58倍、launcher が 4.75倍になった。
     */
    fun onEvent(event: RawEvent) {
        when (event.eventType) {
            UsageEventType.ACTIVITY_RESUMED,
            UsageEventType.CONTINUE_PREVIOUS_DAY ->
                if (!isStale(event)) onActivityResumed(event)

            UsageEventType.ACTIVITY_PAUSED ->
                if (!isStale(event)) onActivityPaused(event)

            // STOPPED / DESTROYED は「そのインスタンスがもう無い」ことを意味する。
            // 終了点には使わない（過大計上の原因）が、状態からは削除する。
            23, 24 -> { // ACTIVITY_STOPPED, ACTIVITY_DESTROYED
                val key = activityKey(event) ?: return
                activityState.remove(key)
                activityPackage.remove(key)
            }

            UsageEventType.END_OF_DAY ->
                if (!isStale(event)) closeInterval(event.timestamp, CloseReason.END_OF_DAY)

            UsageEventType.DEVICE_SHUTDOWN -> {
                // シャットダウン時点で画面 OFF 扱いにする（以降は使用時間ではない）。
                closeScreenOffIfOpen(event.timestamp)
                discardOpenInterval()
                activityState.clear()
                activityPackage.clear()
            }

            UsageEventType.DEVICE_STARTUP -> {
                // 起動時点で開いている区間は、シャットダウン〜起動の間に
                // 閉じイベントを失っている可能性がある。安全のため破棄する。
                discardOpenInterval()
                activityState.clear()
                activityPackage.clear()
            }

            // 画面 OFF: ここから使用時間ではない期間が始まる。
            UsageEventType.SCREEN_NON_INTERACTIVE -> {
                if (screenOffStart == null) screenOffStart = event.timestamp
            }

            // ロック画面表示も使用時間には含めない。
            UsageEventType.KEYGUARD_SHOWN -> {
                if (screenOffStart == null) screenOffStart = event.timestamp
            }

            // 画面 ON / ロック解除: 使用時間の計測を再開する。
            UsageEventType.SCREEN_INTERACTIVE,
            UsageEventType.KEYGUARD_HIDDEN -> closeScreenOffIfOpen(event.timestamp)

            else -> Unit
        }
    }

    /**
     * 現在開いている区間より古いイベントかどうか。
     *
     * 実データ検証（2026-09-14）で、DB に「終了 < 開始」の負の区間が
     * 実際に記録された。原因は、前回の未クローズ区間をシードした後、
     * 今回の読み取り範囲に**それより古いイベント**が含まれ、
     * そのイベントが区間を過去方向に閉じてしまったこと。
     *
     * 開いている区間の開始時刻より前のイベントは、因果的にそれへ影響できない。
     * したがって無視する。これにより
     *  - 負の区間が生成されない
     *  - シードした区間が古いイベントで失われない
     * の両方が保証される。
     *
     * 画面 OFF / ロックのイベントはこの対象外とする。
     * これらは区間とは独立しており、除外期間として保持する必要があるため。
     */
    private fun isStale(event: RawEvent): Boolean =
        openPackage != null && event.timestamp < openStart

    /**
     * ACTIVITY_RESUMED の処理。
     *
     * 公式実装と同じく「そのインスタンス自身が RESUMED だった場合のみ」
     * 時間を加算する。パッケージが同じでも別インスタンスなら加算しない。
     */
    private fun onActivityResumed(event: RawEvent) {
        val pkg = event.packageName ?: return
        val key = activityKey(event) ?: pkg

        // すでに前面のパッケージと同一で、かつ同じインスタンスなら
        // 使用時間は連続している。区間を閉じずに状態だけ更新する。
        val samePkg = openPackage == pkg

        if (!samePkg) {
            // 別パッケージへ切り替わった。前の区間を閉じる。
            closeInterval(event.timestamp, CloseReason.NEXT_RESUMED)
            openPackage = pkg
            openStart = event.timestamp
        } else {
            // 同一パッケージ。区間は継続したまま。
            // ただし「同一パッケージ内で、これまで前面だったインスタンスが
            // PAUSED になり、別インスタンスが RESUMED」という場合は、
            // パッケージとしては前面のままなので区間を継続してよい。
            if (openPackage == null) {
                openPackage = pkg
                openStart = event.timestamp
            }
        }

        activityState[key] = UsageEventType.ACTIVITY_RESUMED
        activityPackage[key] = pkg
    }

    /**
     * 前回の収集で未クローズだった区間を引き継ぐ。
     *
     * **差分収集の要。** 前回「まだ使用中」だったアプリの開始時刻を
     * そのまま引き継ぐことで、アプリが長時間前面にあり続けても
     * 1つの連続した区間として正しく伸ばせる。
     *
     * これを渡さないと、長時間の使用が「収集のたびに開始される
     * 短い区間」に分断され、合計時間が過少になる。
     *
     * @param packageName 前回未クローズだったアプリ。
     * @param startTime その区間の開始時刻。
     */
    fun seedOpenInterval(packageName: String, startTime: Long) {
        openPackage = packageName
        openStart = startTime
    }

    /**
     * アクティビティを一意に識別するキー。
     *
     * 公式実装は instanceId を使うが `@hide` のため公開SDKでは使えない。
     * className で代用する。className が null（DEVICE_* 等）の場合は
     * null を返し、呼び出し側でパッケージ単位にフォールバックする。
     *
     * 制限: 同じクラスの Activity が同時に複数インスタンス存在する場合は
     * 区別できない。ただしその状況は稀であり、パッケージ単位よりは
     * はるかに精度が高い。
     */
    private fun activityKey(event: RawEvent): String? =
        event.className?.takeIf { it.isNotEmpty() }?.let { "${event.packageName}/$it" }


    /**
     * ACTIVITY_PAUSED の処理。
     *
     * インスタンスを PAUSED にし、「このパッケージの前面アクティビティが
     * 全て無くなった場合のみ」区間を閉じる。
     * 同一パッケージの別アクティビティがまだ前面なら区間は継続する。
     */
    private fun onActivityPaused(event: RawEvent) {
        val pkg = event.packageName ?: return
        val key = activityKey(event) ?: pkg
        activityState[key] = UsageEventType.ACTIVITY_PAUSED

        if (openPackage != pkg) return

        // このパッケージに、まだ RESUMED のアクティビティが残っているか。
        val stillForeground = activityState.any { (otherKey, state) ->
            otherKey != key &&
                state == UsageEventType.ACTIVITY_RESUMED &&
                activityPackage[otherKey] == pkg
        }

        if (!stillForeground) {
            closeInterval(event.timestamp, CloseReason.PAUSED)
        }
    }

    private fun closeInterval(at: Long, reason: CloseReason) {
        val pkg = openPackage ?: return

        // 時刻が逆行するイベントで区間を閉じようとした場合は無視する。
        //
        // 実データ検証（2026-09-14）で、DB に「終了 < 開始」の負の区間が
        // 実際に記録されることを確認した（start 21:43:46 → end 21:40:58）。
        //
        // 原因: 前回の未クローズ区間をシードした後、今回の読み取り範囲に
        // それより古いイベントが含まれていると、その古いイベントが
        // 「次のはじまり」として区間を過去方向に閉じてしまう。
        //
        // 現実にも NTP 同期・タイムゾーン変更・手動での時計変更で
        // 時刻は逆行しうるため、ここで防ぐ。
        if (at < openStart) return

        intervals += FocusInterval(
            packageName = pkg,
            startTime = openStart,
            endTime = at,
            closeReason = reason
        )
        openPackage = null
        openStart = 0L
    }

    /** 画面 OFF 期間が開いていれば閉じる。 */
    private fun closeScreenOffIfOpen(at: Long) {
        val start = screenOffStart ?: return
        if (at > start) {
            screenOffPeriods += ScreenOffPeriod(start, at)
        }
        screenOffStart = null
    }

    /**
     * 開区間を破棄する。シャットダウン/再起動をまたぐ場合、
     * 閉じ時刻が不明なため区間を確定できない。
     */
    private fun discardOpenInterval() {
        openPackage = null
        openStart = 0L
    }

    /**
     * 収集時点でまだ開いている区間を、指定時刻で打ち切って確定する。
     * endTime は推定値なので closeReason = STALE_SWEEP として区別する。
     *
     * @param now 現在時刻。openStart より前なら何もしない。
     */
    fun finish(now: Long): List<FocusInterval> {
        val result = collect(now)
        return result.closed + listOfNotNull(
            result.stillOpen?.copy(
                endTime = now,
                closeReason = CloseReason.STALE_SWEEP
            )
        )
    }

    /**
     * 収集結果。定期収集ではこの2つを区別して保存する必要がある。
     */
    data class FocusCollection(
        /** 確定した区間。 */
        val closed: List<FocusInterval>,
        /**
         * 収集時点でまだ前面にあった区間（endTime 未確定）。
         *
         * これを**閉じずに保存**しておくことが差分収集の鍵。
         * 次回の収集でこの区間の開始時刻から再開することで、
         * アプリが長時間前面にあり続けても正しく1つの区間として伸ばせる。
         */
        val stillOpen: FocusInterval?
    )

    /**
     * 収集結果を返す。開いている区間は閉じずに `stillOpen` として返す。
     *
     * `finish()` との違い: `finish()` は未クローズ区間を STALE_SWEEP として
     * 確定してしまう。定期収集では「まだ使用中」を次回に引き継ぐ必要があるため、
     * こちらを使う。
     */
    fun collect(now: Long): FocusCollection {
        // 画面 OFF 期間を先に確定させる（開区間を閉じる前に）。
        closeScreenOffIfOpen(now)

        val open = openPackage?.let { pkg ->
            if (now > openStart) {
                FocusInterval(
                    packageName = pkg,
                    startTime = openStart,
                    endTime = null,
                    closeReason = null
                )
            } else {
                null
            }
        }

        openPackage = null
        openStart = 0L

        return FocusCollection(closed = intervals.toList(), stillOpen = open)
    }

    /** 画面 OFF / ロック中だった期間の一覧。 */
    fun screenOffPeriods(): List<ScreenOffPeriod> = screenOffPeriods.toList()

    /**
     * 画面 OFF / ロック中だった時間を差し引いた、実効使用時間を返す。
     *
     * パッケージごとに、FocusInterval の合計から
     * screenOffPeriods と重なる部分を除いたミリ秒を返す。
     *
     * なぜ必要か: 画面 OFF 中もアプリは「前面」のままなので、
     * RESUMED→PAUSED をそのまま計上すると実際には使っていない時間まで
     * 使用時間に含まれてしまう。エミュレータでの実測で
     * 自前集計 188秒 に対し `queryUsageStats` が 0.06秒 という
     * 乖離が出た原因がこれ。
     */
    fun effectiveUsageByPackage(now: Long): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        // finish() は冪等ではないため、未確定なら確定させてから使う。
        val finalized = if (openPackage != null || screenOffStart != null) {
            finish(now)
        } else {
            intervals.toList()
        }
        val offPeriods = screenOffPeriods()

        for (interval in finalized) {
            val end = interval.endTime ?: continue
            if (end <= interval.startTime) continue

            // 区間の長さから、画面 OFF と重なる部分を引く。
            var offOverlap = 0L
            for (off in offPeriods) {
                val overlapStart = maxOf(interval.startTime, off.startTime)
                val overlapEnd = minOf(end, off.endTime)
                if (overlapEnd > overlapStart) {
                    offOverlap += overlapEnd - overlapStart
                }
            }
            val effective = ((end - interval.startTime) - offOverlap).coerceAtLeast(0L)
            result.merge(interval.packageName, effective) { a, b -> a + b }
        }
        return result
    }
}
