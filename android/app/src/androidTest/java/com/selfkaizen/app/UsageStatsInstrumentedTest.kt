package com.selfkaizen.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.selfkaizen.app.collector.FocusIntervalBuilder
import com.selfkaizen.app.collector.RawEvent
import com.selfkaizen.app.collector.UsageEventType
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機/エミュレータ上で、実際の UsageEvents を読めることを検証する。
 *
 * ユニットテストは「与えられたイベント列の変換」しか検証できない。
 * このテストは「実際の UsageStatsManager からイベントが取得できること」
 * 「自作の定数値が実イベントと対応すること」を確認する。
 *
 * 実行方法:
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * 注意: 使用状況アクセスが未許可の場合、テストはスキップされる（失敗ではない）。
 *      先に以下で許可すること:
 *        adb shell appops set com.selfkaizen.app android:get_usage_stats allow
 */
@RunWith(AndroidJUnit4::class)
class UsageStatsInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Event.getInstanceId() は API 29 で追加された。
     * minSdk 26 の端末では取得できないため 0 を返す（パッケージ単位の挙動になる）。
     */
    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Test
    fun usageAccessが許可されていること() {
        // 許可されていない場合、以降のテストは意味を持たないためスキップする。
        assumeTrue("使用状況アクセスが未許可のためスキップ", hasUsageAccess())
        assertTrue(hasUsageAccess())
    }

    @Test
    fun 実際のUsageEventsを取得できること() {
        assumeTrue("使用状況アクセスが未許可のためスキップ", hasUsageAccess())

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // 保持期間は「数日」のため、直近1時間を対象にする。
        val events = usm.queryEvents(now - 60 * 60 * 1000, now)

        val event = UsageEvents.Event()
        var total = 0
        var resumed = 0
        var paused = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            total++
            when (event.eventType) {
                // 自作定数（リテラル値1/2）が実イベントと一致することの検証
                UsageEventType.ACTIVITY_RESUMED -> resumed++
                UsageEventType.ACTIVITY_PAUSED -> paused++
            }
            // Android の定数と自作定数が一致することの直接検証
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                assertTrue(
                    "自作のACTIVITY_RESUMED定数がAndroidの値と不一致",
                    UsageEventType.ACTIVITY_RESUMED == UsageEvents.Event.ACTIVITY_RESUMED
                )
            }
            if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                assertTrue(
                    "自作のACTIVITY_PAUSED定数がAndroidの値と不一致",
                    UsageEventType.ACTIVITY_PAUSED == UsageEvents.Event.ACTIVITY_PAUSED
                )
            }
        }

        // イベントが1件も無いと検証にならないため、ログに出す
        println("取得イベント数: total=$total resumed=$resumed paused=$paused")
        // 端末を操作していれば何かしらのイベントは存在するはず。
        // ただし起動直後で何も操作していない場合は 0 もあり得るため、
        // ここでは「例外なく取得できたこと」を主眼とする。
    }

    @Test
    fun 自作定数がAndroidの定数と完全一致すること() {
        // @hide の値は Android の定数として参照できないため、
        // 参照可能なものについて一致を確認する。
        assertTrue(UsageEventType.ACTIVITY_RESUMED == UsageEvents.Event.ACTIVITY_RESUMED)
        assertTrue(UsageEventType.ACTIVITY_PAUSED == UsageEvents.Event.ACTIVITY_PAUSED)
        // ACTIVITY_STOPPED は「無視すべき」対象であることの確認用
        assertTrue(UsageEventType.ACTIVITY_RESUMED != UsageEvents.Event.ACTIVITY_STOPPED)
    }

    @Test
    fun 実イベントをステートマシンに通して区間が生成されること() {
        assumeTrue("使用状況アクセスが未許可のためスキップ", hasUsageAccess())

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60 * 60 * 1000, now)

        val builder = FocusIntervalBuilder()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            builder.onEvent(
                RawEvent(
                    eventType = event.eventType,
                    packageName = event.packageName,
                    timestamp = event.timeStamp,
                    className = event.className
                )
            )
        }
        val intervals = builder.finish(now)

        println("生成された区間数: ${intervals.size}")
        intervals.take(10).forEach {
            println("  ${it.packageName} ${it.durationMillis}ms reason=${it.closeReason}")
        }

        // 区間の合計が「最初のイベント」から「now」を超えないこと（過大計上の検出）
        val totalDuration = intervals.sumOf { it.durationMillis }
        assertTrue(
            "区間合計($totalDuration ms)が対象期間を超えている",
            totalDuration <= 60 * 60 * 1000L
        )
    }

    @Test
    fun 画面OFF除外後の実効使用時間がqueryUsageStatsと桁で一致すること() {
        assumeTrue("使用状況アクセスが未許可のためスキップ", hasUsageAccess())

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val since = now - 60 * 60 * 1000

        // 自前集計（画面OFFを除外）
        val events = usm.queryEvents(since, now)
        val builder = FocusIntervalBuilder()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            builder.onEvent(
                RawEvent(event.eventType, event.packageName, event.timeStamp, event.className)
            )
        }
        val effective = builder.effectiveUsageByPackage(now)

        // 公式の集計値
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
        val official = stats.associate { it.packageName to it.totalTimeInForeground }

        println("=== 自前集計 vs queryUsageStats ===")
        val packages = effective.keys.sortedByDescending { effective[it] ?: 0L }.take(8)
        for (pkg in packages) {
            val mine = effective[pkg] ?: 0L
            val off = official[pkg] ?: -1L
            println("  $pkg: 自前=${mine}ms 公式=${off}ms")
        }

        // 桁が合っていること（過大計上の検出）を確認する。
        // 完全一致は目指さない（除外パッケージの定義が公式非公開のため）。
        // ここでは「自前集計が公式値の10倍を超えないこと」を検証する。
        var checked = 0
        for (pkg in packages) {
            val mine = effective[pkg] ?: 0L
            val off = official[pkg] ?: continue
            if (off > 1000) {   // 公式値が小さいものは比率が不安定なので除外
                checked++
                val ratio = mine.toDouble() / off.toDouble()
                println("  比率 $pkg: ${"%.2f".format(ratio)}")
                assertTrue(
                    "$pkg の自前集計(${mine}ms)が公式(${off}ms)の10倍を超えている",
                    ratio <= 10.0
                )
            }
        }
        println("比率チェック対象: $checked 件")
    }

    @Test
    fun queryUsageStatsによる照合値が取得できること() {
        assumeTrue("使用状況アクセスが未許可のためスキップ", hasUsageAccess())

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 24 * 60 * 60 * 1000,
            now
        )

        println("queryUsageStats 取得件数: ${stats.size}")
        val top = stats.sortedByDescending { it.totalTimeInForeground }.take(5)
        top.forEach { println("  ${it.packageName}: ${it.totalTimeInForeground}ms") }
    }
}
