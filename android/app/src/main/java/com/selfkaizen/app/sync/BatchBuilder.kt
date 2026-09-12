package com.selfkaizen.app.sync

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Room の区間から送信バッチを組み立てる。
 *
 * **Android API に依存しない純粋なロジック**（`ZoneId` だけは使う）。
 * サーバーとの契約で最も間違えやすい部分なので、境界をテストで固定する。
 *
 * 契約上の必須事項（`server/README.md` §4）:
 *  1. `localDate` は**端末が確定**して送る
 *  2. **日付境界で区間を分割**してから送る（1区間が複数日にまたがらない）
 *  3. `batchId` は再送で**変えない**（内容から決定的に算出する）
 */
object BatchBuilder {

    /** 1バッチの最大イベント数。サーバー側の上限と一致させること。 */
    const val MAX_EVENTS_PER_BATCH = 2000

    /**
     * 1バッチの最大バイト数の目安。
     * 大きすぎるバッチは Workers の CPU 時間とボディ上限に触れる。
     */
    const val MAX_EVENTS_PER_BATCH_CONSERVATIVE = 500

    /**
     * `batchId` を組み立てるときの項目区切り。
     *
     * NUL を使うのは、**表示名にどんな記号が入っても衝突しない**ため。
     * `:` や `|` にすると、表示名にその文字が含まれたときに
     * 別々の内容が同じ文字列に潰れてしまう。
     */
    private const val SEPARATOR = "\u0000"

    /** 送信対象にする入力区間。Room の `AppUsageEvent` から詰め替える。 */
    data class SourceInterval(
        val packageName: String,
        val appLabel: String,
        val startTime: Long,
        /** null = まだ使用中。**未確定の区間は送らない**（サーバーは endTime を必須とする）。 */
        val endTime: Long?,
        val closeReason: String?,
        /**
         * 収集時に確定したローカル日付（"YYYY-MM-DD"）。
         *
         * 空の場合は [zone] から計算する（後方互換・テスト用）。
         * Room から詰め替えるときは**必ず保存値を渡すこと**。
         */
        val localDate: String = "",
        /**
         * 収集時のタイムゾーンID（例 "Asia/Tokyo"）。
         *
         * 日境界の計算に使う。空の場合は [zone] を使う。
         */
        val zoneId: String = ""
    )

    /**
     * 区間をローカル日付ごとに分割する。
     *
     * 例: 23:30–翌00:30 の区間は
     *  「前日の 23:30–24:00」と「翌日の 00:00–00:30」の2件になる。
     *
     * これを守らないと、サーバー側の日次集計が端末のタイムゾーンとずれる。
     */
    fun splitByLocalDate(
        intervals: List<SourceInterval>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<SyncEvent> {
        val out = mutableListOf<SyncEvent>()

        for (iv in intervals) {
            val end = iv.endTime ?: continue          // 未確定は送らない
            if (end <= iv.startTime) continue          // 0秒・負の長さは送らない
            if (iv.packageName.isBlank()) continue

            // 収集時に確定したタイムゾーンを優先する。
            // 送信時点のゾーンで計算し直すと、渡航後に過去データを送ったときに
            // 日付が変わり、DB の localDate とサーバーの集計が食い違う。
            val effectiveZone = iv.zoneId
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: zone

            // 収集時に確定した日付を起点にする。
            // 空（後方互換・古い行）ならゾーンから求める。
            var date: LocalDate = iv.localDate
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: Instant.ofEpochMilli(iv.startTime).atZone(effectiveZone).toLocalDate()

            var cursor = iv.startTime
            while (cursor < end) {
                val nextMidnight = date.plusDays(1).atStartOfDay(effectiveZone)
                    .toInstant().toEpochMilli()
                val segmentEnd = minOf(end, nextMidnight)
                if (segmentEnd <= cursor) break   // 念のための無限ループ防止

                out += SyncEvent(
                    localDate = date.toString(),
                    packageName = iv.packageName,
                    appLabel = iv.appLabel,
                    startTime = cursor,
                    endTime = segmentEnd,
                    closeReason = iv.closeReason
                )

                cursor = segmentEnd
                date = date.plusDays(1)
            }
        }
        return out
    }

    /**
     * 内容から決定的な `batchId` を作る。
     *
     * **ランダム UUID にしてはいけない。** 再送のたびに変わると
     * サーバー側の冪等性チェック（`ingest_batch` の PK）をすり抜けて
     * 二重計上する。同じ内容なら必ず同じ ID になる必要がある。
     *
     * **逆に、内容が変わったら ID も変わらなければならない。**
     * サーバーは同じ `batchId` のバッチを**丸ごと読み飛ばす**ため
     * （`server/src/db.ts` の `ingestBatch`）、ID に含め忘れた項目を
     * 変えて再送しても、サーバーには一切反映されない。
     *
     * 実際にこれで事故が起きた: `appLabel` が ID に含まれていなかったため、
     * パッケージ名から表示名に変えて送り直しても
     * **サーバーの表示がパッケージ名のまま直らなかった**。
     *
     * 以前は `packageName@startTime`（サーバー側の一意キーと同じ）だけを
     * 含めていた。しかし `endTime` / `closeReason` は後から確定する値であり、
     * `appLabel` も変わりうる。**キーだけでは「同じバッチ」を表せない。**
     *
     * 順序に依存しないよう、区間の表現をソートしてから混ぜる。
     */
    fun batchIdFor(deviceId: String, events: List<SyncEvent>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(deviceId.toByteArray(Charsets.UTF_8))

        events
            .map { event ->
                // 内容を構成する項目を**すべて**並べる。
                // 区切りは NUL（表示名にどんな記号が入っても衝突しない）。
                listOf(
                    event.localDate,
                    event.packageName,
                    event.startTime.toString(),
                    event.endTime.toString(),
                    event.closeReason ?: "",
                    event.appLabel
                ).joinToString(SEPARATOR)
            }
            .sorted()
            .forEach {
                md.update(0) // 区切り（連結による衝突を避ける）
                md.update(it.toByteArray(Charsets.UTF_8))
            }

        // 先頭 16 バイト = 32桁 hex。衝突確率は実用上問題ない。
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * 送信バッチに分割する。
     *
     * 同じ内容からは常に同じ `batchId` が得られるため、
     * 何度実行しても安全（サーバー側で重複と判定される）。
     */
    fun buildBatches(
        deviceId: String,
        events: List<SyncEvent>,
        maxPerBatch: Int = MAX_EVENTS_PER_BATCH_CONSERVATIVE
    ): List<SyncBatch> {
        if (events.isEmpty()) return emptyList()

        return events
            .sortedWith(compareBy({ it.startTime }, { it.packageName }))
            .chunked(maxPerBatch)
            .map { chunk -> SyncBatch(batchId = batchIdFor(deviceId, chunk), events = chunk) }
    }

    // ---- 日付ヘルパ（DailyAggregator と同じ規則。挙動を揃える） ----

    fun toLocalDate(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    fun startOfNextDay(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}
