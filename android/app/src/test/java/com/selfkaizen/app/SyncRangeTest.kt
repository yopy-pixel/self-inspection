package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.sync.SyncRange
import org.junit.Test

/**
 * 送信範囲の決定の検証。
 *
 * **ここを間違えると、送り直したいものが送られない。**
 * 実際に「サーバーの画面がパッケージ名のまま直らない」という形で
 * 表に出た（`appLabel` の意味を変えたのにカーソルが進んでいて、
 * 過去の行が二度と送られなかった）。
 *
 * 純粋なロジックなので境界をテストで固定する。
 */
class SyncRangeTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    // ---------------- 通常のカーソル動作 ----------------

    @Test
    fun `初回は初回同期の範囲から送る`() {
        val from = SyncRange.startFrom(cursor = null, storedVersion = null, now = now)
        assertThat(from).isEqualTo(now - SyncRange.FIRST_SYNC_WINDOW_MILLIS)
    }

    @Test
    fun `版が同じならカーソルから重ねて送る`() {
        val cursor = now - 3 * hour
        val from = SyncRange.startFrom(
            cursor = cursor,
            storedVersion = SyncRange.PAYLOAD_VERSION,
            now = now
        )
        // 後から確定した endTime を送り直せるよう、少し重ねる。
        assertThat(from).isEqualTo(cursor - SyncRange.OVERLAP_MILLIS)
    }

    @Test
    fun `カーソルが重なり幅より小さくても負にならない`() {
        val from = SyncRange.startFrom(
            cursor = 1000L,
            storedVersion = SyncRange.PAYLOAD_VERSION,
            now = now
        )
        assertThat(from).isEqualTo(0L)
    }

    // ---------------- 版が上がったときの送り直し ----------------

    @Test
    fun `版が上がったらカーソルを無視して広く送り直す`() {
        // これが今回の修正の本体。カーソルが進んでいても送り直す。
        val from = SyncRange.startFrom(
            cursor = now - 3 * hour,
            storedVersion = 1,
            now = now
        )
        assertThat(from).isEqualTo(now - SyncRange.RESEND_WINDOW_MILLIS)
    }

    @Test
    fun `版が未記録でも送り直す`() {
        // 初版以前の端末は版を持っていない。0 として扱う。
        val from = SyncRange.startFrom(cursor = now - hour, storedVersion = null, now = now)
        assertThat(from).isEqualTo(now - SyncRange.RESEND_WINDOW_MILLIS)
    }

    @Test
    fun `版が0でも送り直す`() {
        val from = SyncRange.startFrom(cursor = now - hour, storedVersion = 0, now = now)
        assertThat(from).isEqualTo(now - SyncRange.RESEND_WINDOW_MILLIS)
    }

    @Test
    fun `送り直しの範囲は初回同期より広い`() {
        // 過去に送った行を直すのが目的なので、初回同期の範囲では足りない。
        assertThat(SyncRange.RESEND_WINDOW_MILLIS)
            .isGreaterThan(SyncRange.FIRST_SYNC_WINDOW_MILLIS)
    }

    @Test
    fun `送り直しでも過去に飛びすぎない`() {
        // 長期利用でも送信量が跳ね上がらないよう上限を設けている。
        val from = SyncRange.startFrom(cursor = null, storedVersion = null, now = now)
        assertThat(from).isAtLeast(now - SyncRange.RESEND_WINDOW_MILLIS)
    }

    @Test
    fun `端末の時計が極端に小さくても負にならない`() {
        val from = SyncRange.startFrom(cursor = null, storedVersion = 1, now = 1000L)
        assertThat(from).isEqualTo(0L)
    }

    // ---------------- 版の後退 ----------------

    @Test
    fun `版が未来でも通常のカーソル動作に戻る`() {
        // 旧バージョンのアプリに戻した場合など。毎回の送り直しを起こさない。
        val cursor = now - 2 * hour
        val from = SyncRange.startFrom(
            cursor = cursor,
            storedVersion = SyncRange.PAYLOAD_VERSION + 1,
            now = now
        )
        assertThat(from).isEqualTo(cursor - SyncRange.OVERLAP_MILLIS)
    }

    // ---------------- 記録する版 ----------------

    @Test
    fun `成功時に記録する版は現在の版`() {
        assertThat(SyncRange.versionAfterSuccess()).isEqualTo(SyncRange.PAYLOAD_VERSION)
    }

    @Test
    fun `版は2以上である`() {
        // v2 で appLabel の意味を変えた。下げると送り直しが起こらなくなる。
        assertThat(SyncRange.PAYLOAD_VERSION).isAtLeast(2)
    }
}
