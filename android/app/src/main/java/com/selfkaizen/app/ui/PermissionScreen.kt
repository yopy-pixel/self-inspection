package com.selfkaizen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfkaizen.app.R
import com.selfkaizen.app.ui.theme.LocalKaizenColors

/**
 * 権限導線。
 *
 * 「使用状況へのアクセス」はランタイム権限ダイアログでは取得できず、
 * 設定画面へ誘導する必要がある（`AppOpsManager` で判定）。
 */
@Composable
fun PermissionScreen(onOpenSettings: () -> Unit) {
    val c = LocalKaizenColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.permission_denied),
            fontSize = 17.sp,
            color = c.ink
        )
        Text(
            text = stringResource(R.string.permission_body),
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
            color = c.ink2
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.permission_action))
        }
    }
}
