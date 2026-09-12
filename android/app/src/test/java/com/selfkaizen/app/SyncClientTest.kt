package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.sync.ConnectionCheck
import com.selfkaizen.app.sync.HttpResult
import com.selfkaizen.app.sync.HttpTransport
import com.selfkaizen.app.sync.PairCodeResult
import com.selfkaizen.app.sync.SyncBatch
import com.selfkaizen.app.sync.SyncClient
import com.selfkaizen.app.sync.SyncEvent
import com.selfkaizen.app.sync.SyncResult
import com.selfkaizen.app.sync.SyncSettings
import org.json.JSONObject
import org.junit.Test
import java.io.IOException

/**
 * SyncClient の検証。
 *
 * 重視する点:
 *  - **平文 HTTP へ送らないこと**（暗号化の必須要件）
 *  - リクエストがサーバー契約に一致すること
 *  - 応答の解釈（202/200/4xx/通信失敗）
 *  - **トークンがログや例外メッセージに漏れないこと**
 */
class SyncClientTest {

    private val token = "SECRET-TOKEN-DO-NOT-LEAK"

    private fun settings(endpoint: String = "https://example.workers.dev") = SyncSettings(
        enabled = true,
        endpoint = endpoint,
        deviceId = "device-1",
        token = token
    )

    private fun event(start: Long = 1000) = SyncEvent(
        localDate = "2026-09-14",
        packageName = "com.a",
        appLabel = "A",
        startTime = start,
        endTime = start + 500,
        closeReason = "PAUSED"
    )

    private class RecordingTransport(
        private val response: HttpResult? = null,
        private val error: IOException? = null
    ) : HttpTransport {
        var lastUrl: String? = null
        var lastToken: String? = null
        var lastBody: String? = null
        var callCount = 0

        override fun postJson(url: String, bearerToken: String, jsonBody: String): HttpResult {
            callCount++
            lastUrl = url
            lastToken = bearerToken
            lastBody = jsonBody
            error?.let { throw it }
            return response ?: HttpResult(202, """{"eventCount":3}""")
        }

        override fun getJson(url: String, bearerToken: String): HttpResult {
            callCount++
            lastUrl = url
            lastToken = bearerToken
            error?.let { throw it }
            // GET の既定は 200（接続テスト成功）
            return response ?: HttpResult(200, """{"from":"x","to":"x"}""")
        }
    }

    // ---------------- 接続テスト ----------------

    @Test
    fun `接続テスト成功は Ok になる`() {
        val t = RecordingTransport(response = HttpResult(200, """{"from":"x","to":"x"}"""))
        val r = SyncClient(t).checkConnection(settings())

        assertThat(r).isEqualTo(ConnectionCheck.Ok)
        // 送信はしない（読み取りのみ）
        assertThat(t.lastBody).isNull()
    }

    @Test
    fun `接続テストは認証つきの読み取りAPIを叩く`() {
        val t = RecordingTransport(response = HttpResult(200, "{}"))
        SyncClient(t).checkConnection(settings())

        assertThat(t.lastUrl).contains("/api/v1/summary")
        assertThat(t.lastToken).isEqualTo(token)
    }

    @Test
    fun `接続テストで 401 は Unauthorized になる`() {
        val t = RecordingTransport(response = HttpResult(401, """{"error":"unauthorized"}"""))
        val r = SyncClient(t).checkConnection(settings())

        assertThat(r).isEqualTo(ConnectionCheck.Unauthorized(401))
    }

    @Test
    fun `接続テストで通信不能は Failed になる`() {
        val t = RecordingTransport(error = IOException("unreachable"))
        val r = SyncClient(t).checkConnection(settings())

        assertThat(r).isInstanceOf(ConnectionCheck.Failed::class.java)
    }

    @Test
    fun `接続テストは平文 http の外部ホストを拒否する`() {
        val t = RecordingTransport()
        val r = SyncClient(t).checkConnection(settings("http://example.com"))

        assertThat(t.callCount).isEqualTo(0)
        assertThat(r).isInstanceOf(ConnectionCheck.Failed::class.java)
    }

    @Test
    fun `接続テストはトークン未入力を拒否する`() {
        val t = RecordingTransport()
        val r = SyncClient(t).checkConnection(settings().copy(token = ""))

        assertThat(t.callCount).isEqualTo(0)
        assertThat(r).isInstanceOf(ConnectionCheck.Failed::class.java)
    }

    @Test
    fun `接続テストは 500 を Failed として扱う`() {
        val t = RecordingTransport(response = HttpResult(500, "oops"))
        val r = SyncClient(t).checkConnection(settings())

        assertThat(r).isInstanceOf(ConnectionCheck.Failed::class.java)
    }

    // ---------------- 暗号化の必須要件 ----------------

    @Test
    fun `https の endpoint は送信できる`() {
        val t = RecordingTransport()
        val result = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(t.callCount).isEqualTo(1)
        assertThat(result).isInstanceOf(SyncResult.Accepted::class.java)
    }

    @Test
    fun `平文 http の外部ホストへは送らない`() {
        val t = RecordingTransport()
        val result = SyncClient(t).send(
            settings("http://example.com"),
            SyncBatch("b", listOf(event()))
        )

        // 通信を試みてはいけない
        assertThat(t.callCount).isEqualTo(0)
        assertThat(result).isInstanceOf(SyncResult.Rejected::class.java)
        assertThat((result as SyncResult.Rejected).detail).contains("https")
    }

    @Test
    fun `開発用に localhost と 10_0_2_2 は例外とする`() {
        val client = SyncClient()

        assertThat(client.isAllowedEndpoint("http://localhost:8787")).isTrue()
        assertThat(client.isAllowedEndpoint("http://127.0.0.1:8787")).isTrue()
        assertThat(client.isAllowedEndpoint("http://10.0.2.2:8787")).isTrue()
        assertThat(client.isAllowedEndpoint("https://example.workers.dev")).isTrue()

        assertThat(client.isAllowedEndpoint("http://example.com")).isFalse()
        assertThat(client.isAllowedEndpoint("ftp://example.com")).isFalse()
        assertThat(client.isAllowedEndpoint("")).isFalse()
    }

    // ---------------- リクエスト構築 ----------------

    @Test
    fun `リクエストが契約に一致する`() {
        val t = RecordingTransport()
        SyncClient(t).send(settings(), SyncBatch("batch-xyz", listOf(event(5000))))

        val json = JSONObject(t.lastBody!!)
        assertThat(json.getString("batchId")).isEqualTo("batch-xyz")
        assertThat(json.getString("deviceId")).isEqualTo("device-1")
        assertThat(json.getInt("schemaVersion")).isEqualTo(1)

        val e = json.getJSONArray("events").getJSONObject(0)
        assertThat(e.getString("localDate")).isEqualTo("2026-09-14")
        assertThat(e.getString("packageName")).isEqualTo("com.a")
        assertThat(e.getString("appLabel")).isEqualTo("A")
        assertThat(e.getLong("startTime")).isEqualTo(5000)
        assertThat(e.getLong("endTime")).isEqualTo(5500)
        assertThat(e.getString("closeReason")).isEqualTo("PAUSED")
    }

    @Test
    fun `closeReason が null のときは送らない`() {
        val t = RecordingTransport()
        val e = event().copy(closeReason = null)
        SyncClient(t).send(settings(), SyncBatch("b", listOf(e)))

        val json = JSONObject(t.lastBody!!)
        val sent = json.getJSONArray("events").getJSONObject(0)
        assertThat(sent.has("closeReason")).isFalse()
    }

    @Test
    fun `URL は ingest パスに組み立てられる`() {
        val t = RecordingTransport()
        // 末尾スラッシュがあっても二重にならないこと
        SyncClient(t).send(settings("https://example.workers.dev/"), SyncBatch("b", listOf(event())))

        assertThat(t.lastUrl).isEqualTo("https://example.workers.dev/api/v1/ingest")
    }

    @Test
    fun `Authorization ヘッダにトークンを載せる`() {
        val t = RecordingTransport()
        SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(t.lastToken).isEqualTo(token)
    }

    // ---------------- 応答の解釈 ----------------

    @Test
    fun `202 は受理として扱う`() {
        val t = RecordingTransport(response = HttpResult(202, """{"eventCount":7}"""))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(r).isInstanceOf(SyncResult.Accepted::class.java)
        assertThat((r as SyncResult.Accepted).inserted).isEqualTo(7)
    }

    @Test
    fun `200 は再送（重複）として扱う`() {
        val t = RecordingTransport(response = HttpResult(200, """{"duplicate":true}"""))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(r).isEqualTo(SyncResult.Duplicate)
    }

    @Test
    fun `401 は拒否として扱う`() {
        val t = RecordingTransport(response = HttpResult(401, """{"error":"unauthorized"}"""))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(r).isInstanceOf(SyncResult.Rejected::class.java)
        assertThat((r as SyncResult.Rejected).status).isEqualTo(401)
    }

    @Test
    fun `400 は拒否として扱う`() {
        val t = RecordingTransport(response = HttpResult(400, """{"error":"invalid batch"}"""))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat((r as SyncResult.Rejected).status).isEqualTo(400)
    }

    @Test
    fun `通信失敗は NetworkError になり例外を投げない`() {
        val t = RecordingTransport(error = IOException("connection refused"))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(r).isInstanceOf(SyncResult.NetworkError::class.java)
    }

    @Test
    fun `202 の本文が壊れていても落ちない`() {
        val t = RecordingTransport(response = HttpResult(202, "not json"))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat(r).isInstanceOf(SyncResult.Accepted::class.java)
        assertThat((r as SyncResult.Accepted).inserted).isEqualTo(0)
    }

    // ---------------- ブラウザのペアリング ----------------

    @Test
    fun `ペアコードを発行できる`() {
        val t = RecordingTransport(
            response = HttpResult(200, """{"code":"ABC12345","expiresAt":1,"expiresInSeconds":180}""")
        )
        val r = SyncClient(t).pairBrowser(settings())

        assertThat(r).isEqualTo(PairCodeResult.Ok("ABC12345", 180L))
        assertThat(t.lastUrl).endsWith("/api/v1/pair")
        // 発行できるのは端末だけなので、端末トークンで認証する
        assertThat(t.lastToken).isEqualTo(token)
    }

    @Test
    fun `ペアコード発行で 401 は Unauthorized になる`() {
        val t = RecordingTransport(response = HttpResult(401, """{"error":"unauthorized"}"""))
        assertThat(SyncClient(t).pairBrowser(settings()))
            .isEqualTo(PairCodeResult.Unauthorized(401))
    }

    @Test
    fun `ペアコードの応答が壊れていたら失敗にする`() {
        val t = RecordingTransport(response = HttpResult(200, """{"nope":true}"""))
        assertThat(SyncClient(t).pairBrowser(settings()))
            .isInstanceOf(PairCodeResult.Failed::class.java)
    }

    @Test
    fun `平文HTTPへはペアコードを要求しない`() {
        val t = RecordingTransport()
        val r = SyncClient(t).pairBrowser(settings(endpoint = "http://example.com"))

        assertThat(r).isInstanceOf(PairCodeResult.Failed::class.java)
        assertThat(t.callCount).isEqualTo(0)
    }

    // ---------------- トークンの漏洩防止 ----------------

    @Test
    fun `通信失敗のメッセージにトークンが含まれない`() {
        val t = RecordingTransport(error = IOException("failed"))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat((r as SyncResult.NetworkError).message).doesNotContain(token)
    }

    @Test
    fun `拒否の detail にトークンが含まれない`() {
        val t = RecordingTransport(response = HttpResult(403, """{"error":"forbidden"}"""))
        val r = SyncClient(t).send(settings(), SyncBatch("b", listOf(event())))

        assertThat((r as SyncResult.Rejected).detail).doesNotContain(token)
    }
}
