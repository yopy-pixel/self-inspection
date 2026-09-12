package com.selfkaizen.app.ui

import java.util.Locale

/**
 * 画面表示用の時間表記。
 *
 * `design/SPEC.md` §1 の方針に従い、**数字と単位記号だけ**で表現する。
 * 英語なのは `h` / `m` という単位記号のみで、文章ではない。
 *
 * 表記の使い分け（SPEC §1「単位表記」）:
 *  - [long] リング内・状態行     → `3h 42m` / `18m` / `1h 18m`
 *  - [compact] ランキング・チャート → `1h12` / `48m`
 *  - [limit] 上限               → `4h 00m`
 */
object DurationFormat {

    /** `3h 42m` / `18m` / `0m`。0 時間なら分だけ。 */
    fun long(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
    }

    /** `3h 42m` のリング内表示用（分は2桁ゼロ埋めしない。SPECのモックに合わせる）。 */
    fun ringLabel(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** `1h12` / `48m`。省スペース優先。 */
    fun compact(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) {
            if (m > 0) "${h}h${m.toString().padStart(2, '0')}" else "${h}h"
        } else {
            "${m}m"
        }
    }

    /**
     * `3:42` / `0:48`。**ミニウィジェット専用**（SPEC §1「単位表記」）。
     *
     * 分は必ず2桁に揃える。桁が揃わないと、ウィジェットの幅で
     * 数字が横に揺れて落ち着かない。
     * 1時間未満でも `0:48` と時間を出す（`48m` にしない）のは、
     * ミニでは単位記号を置く余白がないため。
     */
    fun hourMinute(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "$h:${m.toString().padStart(2, '0')}"
    }

    /** `4h 00m`。上限は分数を必ず2桁で出す（桁揃えのため）。 */
    fun limit(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "${h}h ${m.toString().padStart(2, '0')}m"
    }

    /**
     * 収集時刻を `14:05` 形式にする。
     * 日付をまたぐ場合は `9/12 14:05`。
     */
    fun clock(epochMillis: Long, nowMillis: Long, zone: java.time.ZoneId): String {
        val t = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        val n = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val hm = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
        return if (t.toLocalDate() == n.toLocalDate()) {
            hm
        } else {
            "${t.monthValue}/${t.dayOfMonth} $hm"
        }
    }

    /**
     * 絶対時刻 `2026-09-13 01:05:42`。
     *
     * **最終同期の表示にはこちらを使う。** 「2m ago」は目安としてしか読めず、
     * 秒が分からないため、同期が実際にいつ走ったのかを確かめられない。
     * 日付を省かないのも同じ理由（日をまたいだ同期を見分けられない）。
     */
    fun timestamp(epochMillis: Long, zone: java.time.ZoneId): String {
        val t = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second
        )
    }

    /**
     * 「どれくらい前か」を短く表す。収集停止の検知に使う（B-4）。
     * `2h ago` / `3d ago` / `just now`
     */
    fun ago(epochMillis: Long, nowMillis: Long): String =
        agoMillis((nowMillis - epochMillis).coerceAtLeast(0L))

    /**
     * 「どれくらい前か」を**経過時間から**表す。
     *
     * 既に経過時間が分かっている場合（通知の判定など）に使う。
     * `ago(epoch, now)` に経過時間を渡すと **必ず `just now` になる**
     * （未来の時刻として計算されるため）ので、経路を分けてある。
     */
    fun agoMillis(elapsedMillis: Long): String {
        val diff = elapsedMillis.coerceAtLeast(0L)
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }

    /**
     * `9/10` 形式の短い日付。
     *
     * ランキング見出しに「いつの話か」を示すために使う。
     * 年は出さない（直近7日しか扱わないため）。
     */
    fun shortDate(date: java.time.LocalDate): String =
        "${date.monthValue}/${date.dayOfMonth}"

    /** 曜日1文字（M T W T F S S）。 */
    fun dayInitial(dayOfWeek: java.time.DayOfWeek): String = when (dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "M"
        java.time.DayOfWeek.TUESDAY -> "T"
        java.time.DayOfWeek.WEDNESDAY -> "W"
        java.time.DayOfWeek.THURSDAY -> "T"
        java.time.DayOfWeek.FRIDAY -> "F"
        java.time.DayOfWeek.SATURDAY -> "S"
        java.time.DayOfWeek.SUNDAY -> "S"
    }
}
