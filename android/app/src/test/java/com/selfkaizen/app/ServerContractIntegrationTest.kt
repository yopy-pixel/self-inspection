package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.sync.BatchBuilder
import com.selfkaizen.app.sync.SyncClient
import com.selfkaizen.app.sync.SyncResult
import com.selfkaizen.app.sync.SyncSettings
import com.selfkaizen.app.sync.UrlConnectionTransport
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * **サーバー ↔ クライアントの実通信テスト。**
 *
 * Android 側のコード（`BatchBuilder` → `SyncClient` → `UrlConnectionTransport`）で
 * 実際に HTTP リクエストを組み立て、動いているサーバーへ送って、
 * 契約どおりに受理されるかを検証する。
 *
 * 実行方法:
 *   1. 別ターミナルで `cd server && node --experimental-strip-types dev-server.ts`
 *   2. `cd android && ./gradlew :app:testDebugUnitTest`
 *
 * **サーバーが起動していない場合はスキップする**（失敗にはしない）。
 * エンドポイントは `SELF_KAIZEN_TEST_ENDPOINT` で変更できる。
 *
 * これは「単体テストが通る」だけでは分からない
 * **サーバーとクライアントの契約のズレ**を検出するためにある。
 */
class ServerContractIntegrationTest {

    private val endpoint: String =
        System.getenv("SELF_KAIZEN_TEST_ENDPOINT") ?: "http://localhost:8787"

    private val adminToken: String =
        System.getenv("SELF_KAIZEN_TEST_ADMIN_TOKEN") ?: "local-admin-token"

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    // 日付は実行時の今日から動的に決める（下記 today / yesterday を参照）。

    private fun serverIsUp(): Boolean = try {
        val c = (URL("$endpoint/healthz").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1000
            readTimeout = 1000
            requestMethod = "GET"
        }
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (_: IOException) {
        false
    }

    /**
     * 日付は**実行時の今日から動的に**決める。
     *
     * 固定日付にしてはいけない: サーバーは `localDate` が未来すぎる/古すぎる
     * イベントを弾く（`MAX_FUTURE_DAYS` / `MAX_PAST_DAYS`）。
     * 固定日付を書くと、実行日によって「未来のデータ」として拒否される。
     * （実際に 2026-09-15 固定で書いて 2026-09-12 実行時に弾かれた）
     */
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 管理者トークンで端末を登録し、その端末の設定を返す。 */
    private fun registerDevice(label: String): SyncSettings {
        val c = (URL("$endpoint/api/v1/devices").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $adminToken")
        }
        c.outputStream.use { it.write("""{"label":"$label"}""".toByteArray()) }

        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()

        val json = JSONObject(body)
        return SyncSettings(
            enabled = true,
            endpoint = endpoint,
            deviceId = json.getString("deviceId"),
            token = json.getString("token")
        )
    }

    private fun getSummary(settings: SyncSettings, from: String, to: String): JSONObject {
        val c = (URL("$endpoint/api/v1/summary?from=$from&to=$to").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(body)
    }

    @Test
    fun `端末登録から取り込み_再送_集計までを実通信で検証する`() {
        assumeTrue("開発サーバーが起動していないためスキップ: $endpoint", serverIsUp())

        // ---- 1. 端末登録（トークン発行） ----
        val settings = registerDevice("integration-test")
        assertThat(settings.deviceId).isNotEmpty()
        assertThat(settings.token.length).isAtLeast(64)

        // ---- 2. Android のコードでバッチを組み立てる ----
        // 日付境界をまたぐ区間を含める（契約上最も壊れやすい箇所）
        val intervals = listOf(
            BatchBuilder.SourceInterval(
                packageName = "com.google.android.youtube",
                appLabel = "YouTube",
                startTime = at(yesterday, 23, 30),
                endTime = at(today, 0, 30),
                closeReason = "PAUSED"
            ),
            BatchBuilder.SourceInterval(
                packageName = "com.android.chrome",
                appLabel = "Chrome",
                startTime = at(today, 10, 0),
                endTime = at(today, 10, 10),
                closeReason = "PAUSED"
            )
        )

        val syncEvents = BatchBuilder.splitByLocalDate(intervals, zone)
        // 23:30-00:30 が2件に分かれ、合計3件になるはず
        assertThat(syncEvents).hasSize(3)

        val batches = BatchBuilder.buildBatches(settings.deviceId, syncEvents)
        assertThat(batches).hasSize(1)

        val client = SyncClient(UrlConnectionTransport())

        // ---- 3. 送信前に基準値を取る ----
        //
        // `/summary` は**全端末を合算**して返す設計（単一ユーザーが全端末を
        // 見るため）。したがって開発サーバーのDBに他のデータが残っていても
        // 正しく検証できるよう、**差分**で確かめる。
        val before = getSummary(settings, yesterday.toString(), today.toString())

        // ---- 4. 送信（新規） → 202 ----
        val first = client.send(settings, batches[0])
        assertThat(first).isInstanceOf(SyncResult.Accepted::class.java)
        assertThat((first as SyncResult.Accepted).inserted).isEqualTo(3)

        // ---- 5. 同じバッチを再送 → 200（重複・二重計上しない） ----
        val second = client.send(settings, batches[0])
        assertThat(second).isEqualTo(SyncResult.Duplicate)

        // ---- 6. 集計を取得して差分を検証 ----
        val after = getSummary(settings, yesterday.toString(), today.toString())

        // 前日分: 23:30〜24:00 の30分
        assertThat(deltaOf(after, before, "dailyTotals", "localDate", yesterday.toString()))
            .isEqualTo(30 * 60_000L)

        // 当日分: 00:00〜00:30 の30分 + Chrome の10分 = 40分
        assertThat(deltaOf(after, before, "dailyTotals", "localDate", today.toString()))
            .isEqualTo(40 * 60_000L)

        // YouTube は日をまたいで分割された合計 = 30分 + 30分 = 60分
        assertThat(
            deltaOf(after, before, "appTotals", "packageName", "com.google.android.youtube")
        ).isEqualTo(60 * 60_000L)
    }

    /** 送信前後の差分を求める（§3 の理由により絶対値では検証しない）。 */
    private fun deltaOf(
        after: JSONObject,
        before: JSONObject,
        arrayName: String,
        keyName: String,
        keyValue: String
    ): Long {
        fun total(json: JSONObject): Long {
            val arr = json.getJSONArray(arrayName)
            return (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .firstOrNull { it.getString(keyName) == keyValue }
                ?.getLong("totalMillis")
                ?: 0L
        }
        return total(after) - total(before)
    }

    @Test
    fun `誤ったトークンは拒否される`() {
        assumeTrue("開発サーバーが起動していないためスキップ: $endpoint", serverIsUp())

        val settings = registerDevice("bad-token-test").copy(token = "invalid-token-value")
        val events = BatchBuilder.splitByLocalDate(
            listOf(
                BatchBuilder.SourceInterval(
                    "com.a", "A", at(today, 10), at(today, 11), "PAUSED"
                )
            ),
            zone
        )
        val batch = BatchBuilder.buildBatches(settings.deviceId, events).first()

        val result = SyncClient(UrlConnectionTransport()).send(settings, batch)

        assertThat(result).isInstanceOf(SyncResult.Rejected::class.java)
        assertThat((result as SyncResult.Rejected).status).isEqualTo(401)
    }
}
