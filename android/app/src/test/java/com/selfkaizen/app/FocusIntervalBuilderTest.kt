package com.selfkaizen.app

import com.selfkaizen.app.collector.CloseReason
import com.selfkaizen.app.collector.FocusInterval
import com.selfkaizen.app.collector.FocusIntervalBuilder
import com.selfkaizen.app.collector.RawEvent
import com.selfkaizen.app.collector.UsageEventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FocusIntervalBuilder のステートマシン検証。
 *
 * PLAN-android.md の「二重計上の落とし穴」「日付境界」「再起動」に対応する。
 */
class FocusIntervalBuilderTest {

    private fun resumed(pkg: String, at: Long) =
        RawEvent(UsageEventType.ACTIVITY_RESUMED, pkg, at)

    private fun paused(pkg: String, at: Long) =
        RawEvent(UsageEventType.ACTIVITY_PAUSED, pkg, at)

    @Test
    fun `通常の使用開始と終了で1区間ができる`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 1000))
        b.onEvent(paused("com.app.a", 5000))

        val result = b.finish(9000)

        assertThat(result).hasSize(1)
        assertThat(result[0].packageName).isEqualTo("com.app.a")
        assertThat(result[0].startTime).isEqualTo(1000)
        assertThat(result[0].endTime).isEqualTo(5000)
        assertThat(result[0].durationMillis).isEqualTo(4000)
        assertThat(result[0].closeReason).isEqualTo(CloseReason.PAUSED)
    }

    @Test
    fun `PAUSEDが来る前に次のRESUMEDが来たら暗黙に閉じる_二重計上防止`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 1000))
        // PAUSED が来ないまま次アプリへ切替
        b.onEvent(resumed("com.app.b", 3000))
        b.onEvent(paused("com.app.b", 4000))

        val result = b.finish(9000)

        assertThat(result).hasSize(2)
        assertThat(result[0].packageName).isEqualTo("com.app.a")
        assertThat(result[0].endTime).isEqualTo(3000)
        assertThat(result[0].closeReason).isEqualTo(CloseReason.NEXT_RESUMED)
        assertThat(result[1].packageName).isEqualTo("com.app.b")
        assertThat(result[1].durationMillis).isEqualTo(1000)
    }

    @Test
    fun `合計時間が区間の重複で膨らまない`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(resumed("com.app.b", 1000))
        b.onEvent(resumed("com.app.a", 2000))
        b.onEvent(paused("com.app.a", 3000))

        val total = b.finish(3000).sumOf { it.durationMillis }

        // 0..1000 + 1000..2000 + 2000..3000 = 3000。重複があれば 3000 を超える。
        assertThat(total).isEqualTo(3000)
    }

    @Test
    fun `END_OF_DAYで閉じ翌日のCONTINUE_PREVIOUS_DAYで再開する`() {
        val b = FocusIntervalBuilder()
        // 前日 23:50 に開始
        b.onEvent(resumed("com.app.a", 0))
        // 日付境界で閉じられる
        b.onEvent(RawEvent(UsageEventType.END_OF_DAY, "com.app.a", 600))
        // 翌日、同じアプリが継続している
        b.onEvent(RawEvent(UsageEventType.CONTINUE_PREVIOUS_DAY, "com.app.a", 600))
        b.onEvent(paused("com.app.a", 1200))

        val result = b.finish(2000)

        // 2区間に分割されること（前日分と翌日分）
        assertThat(result).hasSize(2)
        assertThat(result[0].closeReason).isEqualTo(CloseReason.END_OF_DAY)
        assertThat(result[0].durationMillis).isEqualTo(600)
        assertThat(result[1].startTime).isEqualTo(600)
        assertThat(result[1].durationMillis).isEqualTo(600)
        // 欠損も混入もなく、合計は連続した 1200
        assertThat(result.sumOf { it.durationMillis }).isEqualTo(1200)
    }

    @Test
    fun `END_OF_DAYを無視した場合との差を明示する`() {
        // END_OF_DAY を処理しないと、区間が閉じられず翌日へ持ち越される。
        // このテストは「処理した場合に正しく分割される」ことの対照として置く。
        val handled = FocusIntervalBuilder().apply {
            onEvent(resumed("com.app.a", 0))
            onEvent(RawEvent(UsageEventType.END_OF_DAY, "com.app.a", 600))
            onEvent(RawEvent(UsageEventType.CONTINUE_PREVIOUS_DAY, "com.app.a", 600))
            onEvent(paused("com.app.a", 1200))
        }.finish(2000)

        assertThat(handled).hasSize(2)
        assertThat(handled.sumOf { it.durationMillis }).isEqualTo(1200)
    }

    @Test
    fun `DEVICE_SHUTDOWNをまたぐ開区間は破棄される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 1000))
        // シャットダウン。閉じ時刻が不明なので区間は確定できない。
        b.onEvent(RawEvent(UsageEventType.DEVICE_SHUTDOWN, null, 2000))
        // 再起動後
        b.onEvent(RawEvent(UsageEventType.DEVICE_STARTUP, null, 90000))
        b.onEvent(resumed("com.app.b", 91000))
        b.onEvent(paused("com.app.b", 92000))

        val result = b.finish(99000)

        // 破棄された区間が含まれないこと（膨らんでいないこと）
        assertThat(result).hasSize(1)
        assertThat(result[0].packageName).isEqualTo("com.app.b")
        assertThat(result.sumOf { it.durationMillis }).isEqualTo(1000)
    }

    @Test
    fun `未クローズ区間はSTALE_SWEEPとして確定される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 1000))
        // PAUSED が来ないまま収集終了
        val result = b.finish(5000)

        assertThat(result).hasSize(1)
        assertThat(result[0].closeReason).isEqualTo(CloseReason.STALE_SWEEP)
        assertThat(result[0].durationMillis).isEqualTo(4000)
    }

    @Test
    fun `STOPPEDやSCREENイベントは区間に影響しない`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 1000))
        // ACTIVITY_STOPPED(23) / SCREEN_NON_INTERACTIVE(16) は無視されるべき
        b.onEvent(RawEvent(23, "com.app.a", 2000))
        b.onEvent(RawEvent(16, "android", 3000))
        b.onEvent(paused("com.app.a", 4000))

        val result = b.finish(9000)

        assertThat(result).hasSize(1)
        // 終了点は PAUSED の 4000 であること（STOPPED の 2000 ではない）
        assertThat(result[0].endTime).isEqualTo(4000)
        assertThat(result[0].durationMillis).isEqualTo(3000)
    }

    @Test
    fun `packageNameがnullのイベントは無視される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, null, 1000))
        b.onEvent(resumed("com.app.a", 2000))
        b.onEvent(paused("com.app.a", 3000))

        val result = b.finish(9000)

        assertThat(result).hasSize(1)
        assertThat(result[0].packageName).isEqualTo("com.app.a")
    }

    // ---------------------------------------------------------------------
    // 画面 OFF / ロックの除外
    //
    // エミュレータでの実データ検証（2026-09-13）で、この除外が無いと
    // 「設定」アプリの使用時間が 188秒 と集計され、queryUsageStats の
    // 0.06秒 に対して約3000倍の過大計上になることが判明した。
    // 以下のテストはその回帰を防ぐためのもの。
    // ---------------------------------------------------------------------

    @Test
    fun `画面OFF中の時間は使用時間から除外される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        // 10秒使ったあと画面を消す
        b.onEvent(RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 10_000))
        // 50秒後に画面を点ける
        b.onEvent(RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 60_000))
        // さらに10秒使って終了
        b.onEvent(paused("com.app.a", 70_000))

        val effective = b.effectiveUsageByPackage(70_000)

        // 実使用は 10秒 + 10秒 = 20秒。画面OFF中の50秒は含まれない。
        assertThat(effective["com.app.a"]).isEqualTo(20_000)
    }

    @Test
    fun `画面OFFを除外しないと過大計上になることを明示する`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 10_000))
        b.onEvent(RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 60_000))
        b.onEvent(paused("com.app.a", 70_000))

        val raw = b.finish(70_000).sumOf { it.durationMillis }
        val effective = b.effectiveUsageByPackage(70_000)["com.app.a"]

        // 生の区間は 70秒、実効は 20秒。この差が過大計上そのもの。
        assertThat(raw).isEqualTo(70_000)
        assertThat(effective).isEqualTo(20_000)
        assertThat(raw).isGreaterThan(effective!!)
    }

    @Test
    fun `ロック画面表示中も使用時間から除外される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(RawEvent(UsageEventType.KEYGUARD_SHOWN, "android", 5_000))
        b.onEvent(RawEvent(UsageEventType.KEYGUARD_HIDDEN, "android", 25_000))
        b.onEvent(paused("com.app.a", 30_000))

        val effective = b.effectiveUsageByPackage(30_000)

        // 5秒 + 5秒 = 10秒。ロック中の20秒は除外。
        assertThat(effective["com.app.a"]).isEqualTo(10_000)
    }

    @Test
    fun `画面OFFのまま収集が終わっても正しく除外される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 10_000))
        // 画面OFFのまま収集終了
        val effective = b.effectiveUsageByPackage(100_000)

        // 使用は最初の10秒のみ。
        assertThat(effective["com.app.a"]).isEqualTo(10_000)
    }

    @Test
    fun `画面OFFが複数回あってもすべて除外される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 5_000))
        b.onEvent(RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 10_000))
        b.onEvent(RawEvent(UsageEventType.SCREEN_NON_INTERACTIVE, "android", 20_000))
        b.onEvent(RawEvent(UsageEventType.SCREEN_INTERACTIVE, "android", 30_000))
        b.onEvent(paused("com.app.a", 40_000))

        val effective = b.effectiveUsageByPackage(40_000)

        // 使用 5 + 10 + 10 = 25秒。画面OFF 5 + 10 = 15秒を除外。
        assertThat(effective["com.app.a"]).isEqualTo(25_000)
    }

    @Test
    fun `シャットダウン時点で画面OFF扱いになり以降は計上されない`() {
        val b = FocusIntervalBuilder()
        b.onEvent(resumed("com.app.a", 0))
        b.onEvent(RawEvent(UsageEventType.DEVICE_SHUTDOWN, "android", 5_000))
        b.onEvent(RawEvent(UsageEventType.DEVICE_STARTUP, "android", 50_000))
        b.onEvent(resumed("com.app.b", 51_000))
        b.onEvent(paused("com.app.b", 52_000))

        val effective = b.effectiveUsageByPackage(60_000)

        // シャットダウンをまたぐ com.app.a の区間は破棄される。
        assertThat(effective["com.app.a"]).isNull()
        assertThat(effective["com.app.b"]).isEqualTo(1_000)
    }

    // ---------------------------------------------------------------------
    // 複数アクティビティを持つアプリの扱い（instanceId 単位の追跡）
    //
    // 実データ検証（2026-09-14）で、パッケージ単位の追跡では
    // 「設定」が 1.58倍、ランチャーが 4.75倍に過大計上されることが判明した。
    // 原因は、1つのアプリが複数のアクティビティを持ち、
    // それらが同時にライフサイクルイベントを出すこと。
    // 以下のテストは instanceId 単位の追跡が正しいことを検証する。
    // ---------------------------------------------------------------------

    @Test
    fun `同一パッケージ内で別アクティビティに切り替わっても区間は連続する`() {
        val b = FocusIntervalBuilder()
        // 設定アプリ: InstanceA が前面
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.settings", 1_000, className = "C100"))
        // 同じパッケージの別アクティビティ InstanceB が前面になる
        // （公式ロジックでは InstanceA は PAUSED、InstanceB が RESUMED）
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.settings", 2_000, className = "C100"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.settings", 2_000, className = "C101"))
        // 最後に両方閉じる
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.settings", 5_000, className = "C101"))

        val effective = b.effectiveUsageByPackage(5_000)

        // パッケージとして前面だったのは 1秒〜5秒 の 4秒間。
        // 途中で区間が分断されたり、過大に膨らんだりしないこと。
        assertThat(effective["com.settings"]).isEqualTo(4_000)
    }

    @Test
    fun `同一パッケージの別アクティビティが前面ならPAUSEDで区間を閉じない`() {
        val b = FocusIntervalBuilder()
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.settings", 0, className = "C1"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.settings", 0, className = "C2"))
        // Instance1 だけ PAUSED。Instance2 がまだ前面なので区間は継続すべき。
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.settings", 3_000, className = "C1"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.settings", 10_000, className = "C2"))

        val effective = b.effectiveUsageByPackage(10_000)

        // 0〜10秒が連続した1区間として計上される（分断されない）。
        assertThat(effective["com.settings"]).isEqualTo(10_000)
    }

    @Test
    fun `別パッケージへの切替では区間が閉じる`() {
        val b = FocusIntervalBuilder()
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.a", 0, className = "C1"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.b", 2_000, className = "C2"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.app.b", 3_000, className = "C2"))

        val effective = b.effectiveUsageByPackage(3_000)

        assertThat(effective["com.app.a"]).isEqualTo(2_000)
        assertThat(effective["com.app.b"]).isEqualTo(1_000)
    }

    @Test
    fun `STOPPEDでアクティビティが状態から削除される`() {
        val b = FocusIntervalBuilder()
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.a", 0, className = "C1"))
        // STOPPED は終了点には使わないが、状態からは消える
        b.onEvent(RawEvent(23, "com.app.a", 2_000, className = "C1"))
        // その後に別アプリへ
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.b", 3_000, className = "C2"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.app.b", 4_000, className = "C2"))

        val effective = b.effectiveUsageByPackage(4_000)

        // STOPPED を終了点に使うと com.app.a が 3秒になる。正しくは 3秒で閉じる
        // （次アプリの RESUMED で暗黙に閉じる）。
        assertThat(effective["com.app.a"]).isEqualTo(3_000)
        assertThat(effective["com.app.b"]).isEqualTo(1_000)
    }

    // ---------------------------------------------------------------------
    // 時刻の逆行への耐性
    //
    // 実データ検証（2026-09-14）で、DB に「終了 < 開始」の負の区間が
    // 実際に記録された。原因は、シードした未クローズ区間より古いイベントが
    // 読み取り範囲に含まれ、それが区間を過去方向に閉じていたこと。
    // NTP 同期・タイムゾーン変更・手動での時計変更でも起こりうる。
    // ---------------------------------------------------------------------

    @Test
    fun `逆行イベントで負の区間が生成されない`() {
        val b = FocusIntervalBuilder()
        // 開始が 10秒地点
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.a", 10_000, className = "C1"))
        // それより古い時刻のイベントが届く（時計の逆行・順序の乱れ）
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.b", 5_000, className = "C2"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.app.b", 20_000, className = "C2"))

        val result = b.finish(30_000)

        // 負の区間が1つも無いこと。
        assertThat(result.all { it.durationMillis >= 0 }).isTrue()
        assertThat(result.none { (it.endTime ?: 0) < it.startTime }).isTrue()
    }

    @Test
    fun `シード区間より古いイベントは区間を閉じない`() {
        val b = FocusIntervalBuilder()
        // 前回未クローズだった区間を引き継ぐ（30秒地点から）
        b.seedOpenInterval("com.app.a", 30_000)
        // 今回の範囲に、それより古いイベントが含まれている
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.b", 10_000, className = "C2"))
        // その後、正しい時刻で別アプリに切り替わる
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_RESUMED, "com.app.c", 40_000, className = "C3"))
        b.onEvent(RawEvent(UsageEventType.ACTIVITY_PAUSED, "com.app.c", 50_000, className = "C3"))

        val result = b.finish(60_000)

        // 負の区間が無いこと。
        assertThat(result.all { it.durationMillis >= 0 }).isTrue()
        // com.app.a は 30秒開始のまま、40秒で閉じる（過去の10秒には引きずられない）。
        val a = result.first { it.packageName == "com.app.a" }
        assertThat(a.startTime).isEqualTo(30_000)
        assertThat(a.endTime).isEqualTo(40_000)
    }
}
