package com.selfkaizen.app.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * サーバーへの送信。
 *
 * **平文 HTTP へは送らない**（`docs/PRIVACY.md` §3.1）。
 * サーバー側でも `http:` を拒否するが、**両方で防ぐ**
 * （片方だけだと設定ミスで素通りする）。
 *
 * トークンを**ログに出さない**。例外メッセージにも含めない。
 */
class SyncClient(
    private val transport: HttpTransport = UrlConnectionTransport()
) {

    /**
     * 1バッチを送る。
     *
     * 例外を投げずに [SyncResult] を返す（呼び出し側が再試行を判断しやすくするため）。
     */
    fun send(settings: SyncSettings, batch: SyncBatch): SyncResult {
        // ---- 暗号化の必須要件: https 以外へ送らない ----
        if (!isAllowedEndpoint(settings.endpoint)) {
            return SyncResult.Rejected(
                status = 0,
                detail = "endpoint must be https:// (plaintext http is not allowed)"
            )
        }

        val body = buildRequestBody(settings, batch)

        val response = try {
            transport.postJson(
                url = settings.endpoint.trimEnd('/') + INGEST_PATH,
                bearerToken = settings.token,
                jsonBody = body
            )
        } catch (e: IOException) {
            // ネットワーク到達不可。詳細にトークンは含まれない。
            return SyncResult.NetworkError(e.message ?: "network error")
        }

        return interpret(response)
    }

    /** リクエストボディを組み立てる。`server/README.md` §3 の契約に一致させる。 */
    internal fun buildRequestBody(settings: SyncSettings, batch: SyncBatch): String {
        val events = JSONArray()
        for (e in batch.events) {
            val o = JSONObject()
            o.put("localDate", e.localDate)
            o.put("packageName", e.packageName)
            o.put("appLabel", e.appLabel)
            o.put("startTime", e.startTime)
            o.put("endTime", e.endTime)
            // null を送るとサーバー側の型検証に引っかかるため、null のときは入れない。
            if (e.closeReason != null) o.put("closeReason", e.closeReason)
            events.put(o)
        }

        return JSONObject()
            .put("batchId", batch.batchId)
            .put("deviceId", settings.deviceId)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("events", events)
            .toString()
    }

    /** 応答を解釈する。 */
    internal fun interpret(response: HttpResult): SyncResult = when {
        // 202: 新規取り込み
        response.status == 202 -> {
            val inserted = runCatching {
                JSONObject(response.body).optInt("eventCount", 0)
            }.getOrDefault(0)
            SyncResult.Accepted(inserted)
        }

        // 200: 再送（サーバー側で重複と判定された）
        response.status == 200 -> SyncResult.Duplicate

        // 401/403/400 など: 設定かサーバーの問題。再試行しても直らない。
        else -> SyncResult.Rejected(
            status = response.status,
            detail = response.body.take(300)
        )
    }

    /** endpoint が送信してよい形か。 */
    internal fun isAllowedEndpoint(endpoint: String): Boolean {
        val e = endpoint.trim()
        if (e.isEmpty()) return false
        if (e.startsWith("https://")) return true
        // 開発用の例外のみ。本番の Cloudflare には到達しないホスト。
        if (e.startsWith("http://")) {
            val host = e.removePrefix("http://").substringBefore('/').substringBefore(':')
            return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
        }
        return false
    }

    /**
     * 接続テスト。
     *
     * **送信はせず、読み取り API を叩いて認証まで確認する。**
     * `/healthz` は認証不要なので到達性しか分からない。
     * トークンの正しさまでは検証できないため、認証が必要な `/summary` を使う。
     *
     * テストデータを汚さない（読み取りのみ）という利点もある。
     */
    fun checkConnection(settings: SyncSettings): ConnectionCheck {
        if (!isAllowedEndpoint(settings.endpoint)) {
            return ConnectionCheck.Failed("endpoint must be https://")
        }
        if (settings.token.isBlank()) {
            return ConnectionCheck.Failed("token is empty")
        }

        val today = java.time.LocalDate.now().toString()
        val url = settings.endpoint.trimEnd('/') +
            SUMMARY_PATH + "?from=$today&to=$today"

        val response = try {
            transport.getJson(url, settings.token)
        } catch (e: IOException) {
            return ConnectionCheck.Failed(e.message ?: "network error")
        }

        return when {
            response.status == 200 -> ConnectionCheck.Ok
            response.status == 401 || response.status == 403 ->
                ConnectionCheck.Unauthorized(response.status)
            else -> ConnectionCheck.Failed("HTTP ${response.status}: ${response.body.take(120)}")
        }
    }

    /**
     * 新しいブラウザ用のペアコードを発行する。
     *
     * 新しいブラウザ（PC・別のスマホ）をログインさせるための短命・単回使用の
     * コード。**端末トークンを手入力させない**ための仕組みで、トークンそのものは
     * 画面にも URL にも出さない。
     */
    fun pairBrowser(settings: SyncSettings): PairCodeResult =
        requestPairCode(settings, forDevice = false)

    /**
     * 新しい**端末**用のペアコードを発行する。
     *
     * 別のスマホ（将来は PC エージェント）を追加するときに使う。
     * これがあると、端末を1台増やすのに管理トークンを取り出して
     * `curl` を叩く必要が無くなる。
     *
     * **ブラウザ用と用途を分けている。** 端末コードは書き込み権限
     * （端末トークン）を配るので、閲覧用のコードより影響が強い。
     * サーバー側でも用途を照合するので取り違えは通らない。
     */
    fun pairDevice(settings: SyncSettings): PairCodeResult =
        requestPairCode(settings, forDevice = true)

    private fun requestPairCode(settings: SyncSettings, forDevice: Boolean): PairCodeResult {
        if (!isAllowedEndpoint(settings.endpoint)) {
            return PairCodeResult.Failed("endpoint must be https://")
        }
        if (settings.token.isBlank()) {
            return PairCodeResult.Failed("token is empty")
        }

        val response = try {
            transport.postJson(
                url = settings.endpoint.trimEnd('/') + PAIR_PATH,
                bearerToken = settings.token,
                jsonBody = if (forDevice) """{"for":"device"}""" else "{}"
            )
        } catch (e: IOException) {
            return PairCodeResult.Failed(e.message ?: "network error")
        }

        return when (response.status) {
            200 -> {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                val code = json?.optString("code").orEmpty()
                // 残り秒数を使う（絶対時刻だと端末の時計のずれに弱い）。
                val expiresInSeconds = json?.optLong("expiresInSeconds", 0L) ?: 0L
                if (code.isBlank() || expiresInSeconds <= 0L) {
                    PairCodeResult.Failed("unexpected response")
                } else {
                    PairCodeResult.Ok(code, expiresInSeconds)
                }
            }
            401, 403 -> PairCodeResult.Unauthorized(response.status)
            else -> PairCodeResult.Failed("HTTP ${response.status}")
        }
    }

    /**
     * この端末自身をペアコードで登録する。
     *
     * **認証は不要**（コード自体が資格情報）。新しい端末の設定画面から呼ぶ。
     * 成功すると `deviceId` とトークンが返るので、保存して同期を有効にする。
     */
    fun claimDevice(endpoint: String, code: String, label: String): ClaimResult {
        if (!isAllowedEndpoint(endpoint)) {
            return ClaimResult.Failed("endpoint must be https://")
        }
        if (code.isBlank()) return ClaimResult.Failed("code is empty")

        val body = JSONObject()
            .put("code", code)
            .put("label", label)
            .toString()

        val response = try {
            // 認証ヘッダは付けない（付けるトークンがまだ無い）。
            transport.postJson(endpoint.trimEnd('/') + CLAIM_PATH, "", body)
        } catch (e: IOException) {
            return ClaimResult.Failed(e.message ?: "network error")
        }

        return when (response.status) {
            201 -> {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                val deviceId = json?.optString("deviceId").orEmpty()
                val token = json?.optString("token").orEmpty()
                if (deviceId.isBlank() || token.isBlank()) {
                    ClaimResult.Failed("unexpected response")
                } else {
                    ClaimResult.Ok(deviceId, token)
                }
            }
            401, 403 -> ClaimResult.Rejected(response.status)
            400 -> ClaimResult.Failed("code is required")
            else -> ClaimResult.Failed("HTTP ${response.status}")
        }
    }

    companion object {
        const val INGEST_PATH = "/api/v1/ingest"
        const val SUMMARY_PATH = "/api/v1/summary"
        const val PAIR_PATH = "/api/v1/pair"
        const val CLAIM_PATH = "/api/v1/devices/claim"

        /** サーバー側の `SCHEMA_VERSION` と一致させること。 */
        const val SCHEMA_VERSION = 1
    }
}

/** 接続テストの結果。 */
sealed interface ConnectionCheck {
    /** 到達でき、トークンも有効。 */
    data object Ok : ConnectionCheck

    /** 到達できたがトークンが拒否された。 */
    data class Unauthorized(val status: Int) : ConnectionCheck

    /** 到達できなかった、または想定外の応答。 */
    data class Failed(val message: String) : ConnectionCheck
}

/** ペアコード発行の結果。 */
sealed interface PairCodeResult {
    /** 発行できた。[expiresInSeconds] はサーバーが計算した残り時間。 */
    data class Ok(val code: String, val expiresInSeconds: Long) : PairCodeResult

    data class Unauthorized(val status: Int) : PairCodeResult

    data class Failed(val message: String) : PairCodeResult
}

/** 端末登録（claim）の結果。 */
sealed interface ClaimResult {
    /** 登録できた。この値を保存すれば同期できる。 */
    data class Ok(val deviceId: String, val token: String) : ClaimResult

    /** コードが拒否された（期限切れ・使用済み・用途違い・でたらめ）。 */
    data class Rejected(val status: Int) : ClaimResult

    data class Failed(val message: String) : ClaimResult
}

/** HTTP 応答。 */
data class HttpResult(val status: Int, val body: String)

/**
 * HTTP 送信の抽象。
 *
 * テストで差し替えられるように分離している（実通信なしで
 * リクエスト構築と応答解釈を検証するため）。
 */
interface HttpTransport {
    fun postJson(url: String, bearerToken: String, jsonBody: String): HttpResult

    /** 接続テスト用の読み取り。 */
    fun getJson(url: String, bearerToken: String): HttpResult
}

/** 標準ライブラリのみを使う実装（依存を増やさない）。 */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000
) : HttpTransport {

    override fun postJson(url: String, bearerToken: String, jsonBody: String): HttpResult =
        request("POST", url, bearerToken) { connection ->
            connection.doOutput = true
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        }

    override fun getJson(url: String, bearerToken: String): HttpResult =
        request("GET", url, bearerToken) { }

    private fun request(
        method: String,
        url: String,
        bearerToken: String,
        writeBody: (HttpURLConnection) -> Unit
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            // トークンはヘッダに載せる（URL に含めない。ログに残るため）。
            // **空のときは付けない**（端末登録は未認証で叩くため）。
            if (bearerToken.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (method == "POST") setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        return try {
            writeBody(connection)

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
