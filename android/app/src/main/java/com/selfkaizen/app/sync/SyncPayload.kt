package com.selfkaizen.app.sync

/**
 * サーバーへ送るデータ。
 *
 * **`docs/PRIVACY.md` §1 の範囲のみを含む。**
 * ウィンドウタイトル・閲覧URL・入力内容・端末識別子は含まない。
 */
data class SyncEvent(
    /** 端末が確定したローカル日付 "YYYY-MM-DD"。 */
    val localDate: String,
    val packageName: String,
    val appLabel: String,
    val startTime: Long,
    val endTime: Long,
    val closeReason: String?
)

/**
 * 送信単位。
 *
 * `batchId` は**内容から決定的に算出**する（[BatchBuilder.batchIdFor]）。
 * ランダムにすると再送のたびに変わり、サーバー側で二重計上が防げない。
 */
data class SyncBatch(
    val batchId: String,
    val events: List<SyncEvent>
)

/** 送信結果。 */
sealed interface SyncResult {
    /** 新規に取り込まれた。 */
    data class Accepted(val inserted: Int) : SyncResult

    /** 既に処理済みだった（再送）。 */
    data object Duplicate : SyncResult

    /** サーバーが受理しなかった。 */
    data class Rejected(val status: Int, val detail: String) : SyncResult

    /** 通信できなかった（再試行すべき）。 */
    data class NetworkError(val message: String) : SyncResult
}

/** 同期の設定。UI から編集できる。 */
data class SyncSettings(
    val enabled: Boolean,
    /** 例 "https://self-kaizen.example.workers.dev"。**https のみ。** */
    val endpoint: String,
    val deviceId: String,
    val token: String
) {
    val isConfigured: Boolean
        get() = enabled && endpoint.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank()
}
