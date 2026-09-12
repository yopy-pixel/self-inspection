package com.selfkaizen.app.collector

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * 「使用状況へのアクセス」権限の判定。
 *
 * この権限は通常の実行時権限ダイアログでは取得できず、
 * 「設定 > 使用状況へのアクセス」で手動許可が必要。
 * そのため `checkSelfPermission` ではなく `AppOpsManager` で判定する。
 */
object UsageAccess {

    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
