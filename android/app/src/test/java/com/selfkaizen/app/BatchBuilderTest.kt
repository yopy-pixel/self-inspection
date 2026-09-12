package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.sync.BatchBuilder
import com.selfkaizen.app.sync.BatchBuilder.SourceInterval
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * BatchBuilder（Room → 送信バッチ）の検証。
 *
 * サーバーとの契約で最も間違えやすい部分:
 *  - **日付境界の分割**（守らないと端末のタイムゾーンとずれる）
 *  - **batchId の決定性**（守らないと再送で二重計上する）
 */
class BatchBuilderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun src(
        pkg: String,
        start: Long,
        end: Long?,
        reason: String? = "PAUSED"
    ) = SourceInterval(pkg, pkg, start, end, reason)

    // ---------------- 日付境界の分割 ----------------

    @Test
    fun `日付をまたぐ区間は2件に分割される`() {
        // 09-14 23:30 〜 09-15 00:30
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 23, 30), at("2026-09-15", 0, 30))),
            zone
        )

        assertThat(events).hasSize(2)
        assertThat(events[0].localDate).isEqualTo("2026-09-14")
        assertThat(events[1].localDate).isEqualTo("2026-09-15")
        // 前日分は 23:30〜24:00 の30分
        assertThat(events[0].endTime - events[0].startTime).isEqualTo(30 * 60_000L)
        // 翌日分は 00:00〜00:30 の30分
        assertThat(events[1].endTime - events[1].startTime).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `分割しても合計時間が変わらない`() {
        val start = at("2026-09-14", 23, 30)
        val end = at("2026-09-15", 0, 30)

        val events = BatchBuilder.splitByLocalDate(listOf(src("com.a", start, end)), zone)
        val total = events.sumOf { it.endTime - it.startTime }

        assertThat(total).isEqualTo(end - start)
    }

    @Test
    fun `複数日にまたがる区間も各日に分割される`() {
        // 09-14 22:00 〜 09-16 02:00（28時間）
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 22), at("2026-09-16", 2))),
            zone
        )

        assertThat(events.map { it.localDate })
            .containsExactly("2026-09-14", "2026-09-15", "2026-09-16")
            .inOrder()
    }

    @Test
    fun `各セグメントの localDate は開始時刻の日付と一致する`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 23, 59), at("2026-09-15", 0, 1))),
            zone
        )

        // 契約: localDate は「その区間が属する端末ローカルの日」であること
        for (e in events) {
            assertThat(e.localDate).isEqualTo(BatchBuilder.toLocalDate(e.startTime, zone))
        }
    }

    @Test
    fun `同一日内の区間は分割されない`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )

        assertThat(events).hasSize(1)
        assertThat(events[0].localDate).isEqualTo("2026-09-14")
    }

    // ---------------- 送らないもの ----------------

    @Test
    fun `未確定の区間は送らない`() {
        // endTime == null（まだ使用中）。サーバーは endTime を必須とする。
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), null)),
            zone
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `0秒と負の長さの区間は送らない`() {
        val t = at("2026-09-14", 10)
        val events = BatchBuilder.splitByLocalDate(
            listOf(
                src("com.a", t, t),              // 0秒
                src("com.b", t, t - 1000)        // 負
            ),
            zone
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `パッケージ名が空の区間は送らない`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )

        assertThat(events).isEmpty()
    }

    // ---------------- batchId の決定性（冪等性の要） ----------------

    @Test
    fun `同じ内容からは同じ batchId になる`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )

        val id1 = BatchBuilder.batchIdFor("device-1", events)
        val id2 = BatchBuilder.batchIdFor("device-1", events)

        assertThat(id1).isEqualTo(id2)
    }

    @Test
    fun `内容が違えば batchId も違う`() {
        val a = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )
        val b = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 12), at("2026-09-14", 13))),
            zone
        )

        assertThat(BatchBuilder.batchIdFor("d", a))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", b))
    }

    @Test
    fun `端末が違えば batchId も違う`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )

        assertThat(BatchBuilder.batchIdFor("device-1", events))
            .isNotEqualTo(BatchBuilder.batchIdFor("device-2", events))
    }

    @Test
    fun `区間の順序が違っても batchId は同じ`() {
        // 順序に依存すると、同じデータを別順で送ったときに二重計上する
        val e1 = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )
        val e2 = BatchBuilder.splitByLocalDate(
            listOf(src("com.b", at("2026-09-14", 12), at("2026-09-14", 13))),
            zone
        )

        val forward = BatchBuilder.batchIdFor("d", e1 + e2)
        val reversed = BatchBuilder.batchIdFor("d", e2 + e1)

        assertThat(forward).isEqualTo(reversed)
    }

    @Test
    fun `batchId は32桁の16進数`() {
        val events = BatchBuilder.splitByLocalDate(
            listOf(src("com.a", at("2026-09-14", 10), at("2026-09-14", 11))),
            zone
        )
        val id = BatchBuilder.batchIdFor("d", events)

        assertThat(id).hasLength(32)
        assertThat(id).matches("[0-9a-f]{32}")
    }

    // ---------------- batchId は「内容の変化」を捉える ----------------
    //
    // サーバーは同じ batchId のバッチを**丸ごと読み飛ばす**。
    // したがって、内容を変えて送り直したいときは ID も変わる必要がある。
    // ここが抜けると「直したのに反映されない」という形で表に出る
    // （実際に appLabel で起きた）。

    /** 1件だけのバッチを作る。 */
    private fun single(pkg: String = "com.a") = BatchBuilder.splitByLocalDate(
        listOf(src(pkg, at("2026-09-14", 10), at("2026-09-14", 11))),
        zone
    )

    @Test
    fun `表示名が変われば batchId も変わる`() {
        // **これが今回の修正の本体。**
        // 変わらないと、表示名を直して送り直してもサーバーが読み飛ばし、
        // パッケージ名のまま永久に残る。
        val before = single().map { it.copy(appLabel = "com.brave.browser") }
        val after = single().map { it.copy(appLabel = "Brave") }

        assertThat(BatchBuilder.batchIdFor("d", before))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", after))
    }

    @Test
    fun `終了時刻が変われば batchId も変わる`() {
        // endTime は後から確定する（未クローズ区間が閉じる）。
        val before = single()
        val after = before.map { it.copy(endTime = it.endTime + 60_000L) }

        assertThat(BatchBuilder.batchIdFor("d", before))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", after))
    }

    @Test
    fun `閉じ方が変われば batchId も変わる`() {
        val before = single()
        val after = before.map { it.copy(closeReason = "STALE_SWEEP") }

        assertThat(BatchBuilder.batchIdFor("d", before))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", after))
    }

    @Test
    fun `日付が変われば batchId も変わる`() {
        val before = single()
        val after = before.map { it.copy(localDate = "2026-09-15") }

        assertThat(BatchBuilder.batchIdFor("d", before))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", after))
    }

    @Test
    fun `区切り文字が値に含まれても衝突しない`() {
        // ":" などの区切りだと、項目の切れ目がずれた別内容が
        // 同じ文字列に潰れてしまう。NUL 区切りにしている理由。
        val a = single().map { it.copy(closeReason = "a", appLabel = "b:c") }
        val b = single().map { it.copy(closeReason = "a:b", appLabel = "c") }

        assertThat(BatchBuilder.batchIdFor("d", a))
            .isNotEqualTo(BatchBuilder.batchIdFor("d", b))
    }

    @Test
    fun `表示名が同じなら batchId は変わらない`() {
        // 内容を捉えつつ、無関係な再送で ID が変わってはならない
        // （変わると毎回すべてのバッチが「新規」になり無駄に取り込まれる）。
        val a = single().map { it.copy(appLabel = "Brave") }
        val b = single().map { it.copy(appLabel = "Brave") }

        assertThat(BatchBuilder.batchIdFor("d", a))
            .isEqualTo(BatchBuilder.batchIdFor("d", b))
    }

    // ---------------- バッチ分割 ----------------

    @Test
    fun `空の入力からはバッチを作らない`() {
        assertThat(BatchBuilder.buildBatches("d", emptyList())).isEmpty()
    }

    @Test
    fun `上限を超えると複数バッチに分かれる`() {
        val events = (0 until 10).map {
            com.selfkaizen.app.sync.SyncEvent(
                localDate = "2026-09-14",
                packageName = "com.app$it",
                appLabel = "app$it",
                startTime = 1000L * it,
                endTime = 1000L * it + 500,
                closeReason = "PAUSED"
            )
        }

        val batches = BatchBuilder.buildBatches("d", events, maxPerBatch = 4)

        assertThat(batches).hasSize(3) // 4 + 4 + 2
        assertThat(batches.map { it.events.size }).containsExactly(4, 4, 2).inOrder()
    }

    @Test
    fun `各バッチの batchId は互いに異なる`() {
        val events = (0 until 6).map {
            com.selfkaizen.app.sync.SyncEvent(
                localDate = "2026-09-14",
                packageName = "com.app$it",
                appLabel = "app$it",
                startTime = 1000L * it,
                endTime = 1000L * it + 500,
                closeReason = "PAUSED"
            )
        }

        val ids = BatchBuilder.buildBatches("d", events, maxPerBatch = 3).map { it.batchId }

        assertThat(ids).hasSize(2)
        assertThat(ids.toSet()).hasSize(2)
    }

    @Test
    fun `バッチを再構築しても batchId が変わらない（再送の安全性）`() {
        val events = (0 until 6).map {
            com.selfkaizen.app.sync.SyncEvent(
                localDate = "2026-09-14",
                packageName = "com.app$it",
                appLabel = "app$it",
                startTime = 1000L * it,
                endTime = 1000L * it + 500,
                closeReason = "PAUSED"
            )
        }

        val first = BatchBuilder.buildBatches("d", events, maxPerBatch = 3).map { it.batchId }
        val second = BatchBuilder.buildBatches("d", events, maxPerBatch = 3).map { it.batchId }

        assertThat(first).isEqualTo(second)
    }
}
