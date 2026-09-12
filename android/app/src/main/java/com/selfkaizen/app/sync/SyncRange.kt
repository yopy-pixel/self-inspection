package com.selfkaizen.app.sync

/**
 * 送信をどこから始めるかの決定。
 *
 * **Android に依存しない純粋なロジック**にしてある。
 * 「どの範囲を送るか」を間違えると、送り直したいものが送られない
 * （＝サーバーの表示が直らない）ので、境界をテストで固定する。
 */
object SyncRange {

    /** 前回カーソルから遡る量（後から確定した値を送り直すため）。 */
    const val OVERLAP_MILLIS = 24 * 60 * 60 * 1000L

    /** 初回同期で遡る量。 */
    const val FIRST_SYNC_WINDOW_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /**
     * 送信形式を変えたときに送り直す範囲。
     *
     * 全期間ではなく上限を設ける。長期利用でも送信量が跳ね上がらないようにするため。
     */
    const val RESEND_WINDOW_MILLIS = 30 * 24 * 60 * 60 * 1000L

    /**
     * 現在の送信形式の版。
     *
     * **送る中身の意味を変えたら上げること。** 版が上がると、
     * カーソルを無視して [RESEND_WINDOW_MILLIS] 分を1回だけ送り直す。
     *
     *  - v1: 初版
     *  - v2: `appLabel` にパッケージ名ではなく**表示名**を入れるようにした。
     *        サーバーには `PackageManager` が無く表示名を解決できないため。
     *        サーバーの集計は書き込み時に再計算されるので、**送り直せば直る**
     *        （`db.ts` の `daily_summary` を参照）。
     *  - v3: `batchId` が内容（`appLabel` など）を含むようになった
     *        （`BatchBuilder.batchIdFor`）。
     *
     * **v2 をもう一度上げている理由。** v2 の送り直しは `batchId` が
     * 内容を含んでいなかったため、**サーバーに読み飛ばされて効かなかった**
     * （同じ ID ＝ 重複として捨てられる）。それでも版だけは記録されるので、
     * そのままでは二度と送り直されない。v3 で確実にやり直す。
     */
    const val PAYLOAD_VERSION = 3

    /**
     * 送信を始める位置を決める。
     *
     * 優先順:
     *  1. 送信形式の版が上がっていれば、カーソルを無視して広く送り直す
     *  2. カーソルがあれば、そこから少し重ねて送る
     *  3. どちらも無ければ初回同期の範囲
     *
     * @param cursor 前回送信した最新の `startTime`。null = 未送信
     * @param storedVersion 前回送信時に記録した版。null = 未記録（＝初版以前）
     * @return 送信開始時刻（epoch millis）
     */
    fun startFrom(
        cursor: Long?,
        storedVersion: Int?,
        now: Long,
        currentVersion: Int = PAYLOAD_VERSION
    ): Long = when {
        // **形式が変わったら送り直す。** サーバーは UPSERT なので
        // 何度送っても二重計上しない。ここを省くと、修正前に送った行の
        // 表示が永久に直らない（実際に起きた）。
        //
        // ただし**未送信（カーソル無し）なら送り直す対象が無い**ので、
        // 初回と同じ範囲でよい。区別しないと新規インストールでも
        // 30日分を走査することになり、意図が読めなくなる。
        cursor != null && (storedVersion ?: 0) < currentVersion ->
            (now - RESEND_WINDOW_MILLIS).coerceAtLeast(0L)

        cursor != null -> (cursor - OVERLAP_MILLIS).coerceAtLeast(0L)

        // 端末の時計がリセット直後などで極端に小さい場合でも負にしない
        // （負の時刻を渡すと DB の範囲指定が意図しない結果になる）。
        else -> (now - FIRST_SYNC_WINDOW_MILLIS).coerceAtLeast(0L)
    }

    /**
     * 送信が完全に成功したときに記録する版。
     *
     * 失敗した場合は記録しない（次回また送り直す必要があるため）。
     */
    fun versionAfterSuccess(): Int = PAYLOAD_VERSION
}
