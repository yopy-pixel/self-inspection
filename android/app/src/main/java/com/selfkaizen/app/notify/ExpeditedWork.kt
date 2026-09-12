package com.selfkaizen.app.notify

import android.os.Build
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkRequest

/**
 * 通知系の Worker を「即時実行」にする。
 *
 * **Doze 中でも走らせるため。** 通常の Worker は Doze の
 * メンテナンスウィンドウまで待たされ、超過に気づくのが数時間遅れる。
 * アラームは `setAndAllowWhileIdle` で Doze を抜けているのに、
 * そのあとの実行で詰まっては意味が無い。
 *
 * **API 31 未満では何もしない。** 31 未満の即時実行は
 * フォアグラウンドサービスで実装されるため `getForegroundInfo` の
 * 実装が必須になり（未実装だと例外で落ちる）、通知1件のために
 * FGS を出すことになる。`PLAN-android.md` は FGS 非採用としている。
 *
 * **この判断を1箇所に置く。** 通知が増えるたびに写すと、
 * 片方だけ直して API 31 未満で落ちる、という事故が起きる。
 */
internal fun <B : WorkRequest.Builder<B, W>, W : WorkRequest> B.expediteIfSupported(): B {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
    return this
}
