package com.selfkaizen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.selfkaizen.app.R
import com.selfkaizen.app.ui.theme.LocalKaizenColors
import com.selfkaizen.app.ui.theme.SectionLabelStyle
import java.time.ZoneId

/**
 * 設定画面。
 *
 * `design/SPEC.md` の配色・方針に従う（モノクロ＋超過時のみ赤）。
 * 文字列は `strings.xml` に英語で定義する（ハードコードしない）。
 *
 * 同期設定はトークンを含むため、`EncryptedSharedPreferences` に保存される
 * （`docs/PRIVACY.md` §3.2）。この画面はトークンを表示するが、
 * **ログには出さない**。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLimitMinutes: (Int) -> Unit,
    onApproachingMinutes: (Int) -> Unit,
    onLimitEnabled: (Boolean) -> Unit,
    onSyncEnabled: (Boolean) -> Unit,
    onEndpoint: (String) -> Unit,
    onDeviceId: (String) -> Unit,
    onToken: (String) -> Unit,
    onPasteCredentials: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSyncNow: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalKaizenColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ---- ヘッダー ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = c.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            // 閉じるだけ（保存しない）。保存は最下部の Save ボタン。
            // 「保存せず閉じる」と「保存する」を同じラベルにすると、
            // 押した結果が予測できなくなる（実際に Done が2つあった）。
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.settings_close), color = c.ink, fontSize = 22.sp)
            }
        }

        // ---- 上限 ----
        SettingsCard {
            SectionLabel(stringResource(R.string.settings_section_limit))

            ToggleRow(
                label = stringResource(R.string.settings_limit_enabled),
                checked = state.limitEnabled,
                onCheckedChange = onLimitEnabled
            )

            if (state.limitEnabled) {
                NumberField(
                    label = stringResource(R.string.settings_limit_hours),
                    value = minutesToHoursInput(state.limitMinutes),
                    onValueChange = { onLimitMinutes(hoursInputToMinutes(it)) }
                )
                NumberField(
                    label = stringResource(R.string.settings_approach_minutes),
                    value = state.approachingMinutes.toString(),
                    onValueChange = { onApproachingMinutes(it.toIntOrNull() ?: 0) }
                )
            }
        }

        // ---- 同期 ----
        SettingsCard {
            SectionLabel(stringResource(R.string.settings_section_sync))

            ToggleRow(
                label = stringResource(R.string.settings_sync_enabled),
                checked = state.syncEnabled,
                onCheckedChange = onSyncEnabled
            )

            Text(
                text = stringResource(R.string.settings_sync_hint),
                color = c.ink2,
                fontSize = 11.5.sp
            )
            Text(
                text = stringResource(R.string.settings_privacy),
                color = c.ink3,
                fontSize = 11.sp
            )

            if (state.syncEnabled) {
                var showToken by remember { mutableStateOf(false) }

                TextField(
                    label = stringResource(R.string.settings_endpoint),
                    value = state.endpoint,
                    onValueChange = onEndpoint
                )

                // サーバーが返した JSON をまとめて貼れるようにする（入力ミス防止）。
                PasteField(onPaste = onPasteCredentials)

                TextField(
                    label = stringResource(R.string.settings_device_id),
                    value = state.deviceId,
                    onValueChange = onDeviceId
                )
                TextField(
                    label = stringResource(R.string.settings_token),
                    value = state.token,
                    onValueChange = onToken,
                    visualTransformation = if (showToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        // ラベルは状態ではなく「押すとどうなるか」を示す。
                        // 旧: 表示中 "•••" / 伏せ字 "abc"（意味が伝わらない）
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(
                                text = stringResource(
                                    if (showToken) R.string.settings_token_hide
                                    else R.string.settings_token_show
                                ),
                                color = c.ink2,
                                fontSize = 11.sp
                            )
                        }
                    }
                )

                // ---- 接続テスト ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTestConnection,
                        enabled = state.connection !is ConnectionTestState.Testing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.ink,
                            contentColor = c.bg
                        )
                    ) {
                        Text(
                            text = if (state.connection is ConnectionTestState.Testing) {
                                stringResource(R.string.settings_testing)
                            } else {
                                stringResource(R.string.settings_test)
                            },
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = onSyncNow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.surface,
                            contentColor = c.ink
                        ),
                        modifier = Modifier.border(1.dp, c.line, RoundedCornerShape(4.dp))
                    ) {
                        Text(stringResource(R.string.settings_sync_now), fontSize = 12.sp)
                    }
                }

                ConnectionStatus(state)
                LastSyncLine(state)
            }
        }

        // ---- 保存 ----
        if (state.savedAt != null) {
            Text(
                text = stringResource(R.string.settings_saved),
                color = c.ink2,
                fontSize = 11.5.sp
            )
        }
        if (state.error != null) {
            Text(text = state.error, color = c.danger, fontSize = 11.5.sp)
        }

        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_save), fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------------
// 部品
// ---------------------------------------------------------------------------

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val c = LocalKaizenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(10.dp))
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = SectionLabelStyle,
        color = LocalKaizenColors.current.ink3
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalKaizenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = c.ink, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = LocalKaizenColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.5.sp) },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(4)) },
        label = { Text(label, fontSize = 11.5.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 認証情報の貼り付け欄。
 *
 * サーバーが返す JSON をそのまま貼れるようにして、転記ミスを減らす。
 * 貼り付けた内容は [onPaste] が解釈する（この欄自体は保存しない）。
 */
@Composable
private fun PasteField(onPaste: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val c = LocalKaizenColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.settings_paste), fontSize = 11.5.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                onPaste(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)
        ) {
            Text("+", fontSize = 14.sp)
        }
    }
}

@Composable
private fun ConnectionStatus(state: SettingsUiState) {
    val c = LocalKaizenColors.current
    val (text, color) = when (val conn = state.connection) {
        is ConnectionTestState.Idle -> return
        is ConnectionTestState.Testing ->
            stringResource(R.string.settings_testing) to c.ink2

        is ConnectionTestState.Ok ->
            stringResource(R.string.settings_test_ok) to c.ink

        is ConnectionTestState.Unauthorized ->
            stringResource(R.string.settings_test_unauthorized, conn.status) to c.danger

        is ConnectionTestState.Failed ->
            stringResource(R.string.settings_test_failed, conn.message) to c.danger
    }
    Text(text = text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun LastSyncLine(state: SettingsUiState) {
    val c = LocalKaizenColors.current
    val zone = ZoneId.systemDefault()
    // 「2m ago」ではなく絶対時刻を出す。秒が無いと、実際に走ったのかを
    // 確かめられないため（この画面の目的は同期の成否の確認）。
    val text = state.lastSyncAt?.let {
        stringResource(R.string.settings_last_sync, DurationFormat.timestamp(it, zone))
    } ?: stringResource(R.string.settings_never_synced)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = text, color = c.ink2, fontSize = 11.sp)
        state.lastSyncResult?.let {
            Text(text = it, color = c.ink3, fontSize = 10.5.sp)
        }
    }
}

// ---- 入力変換 ----

/** 分 → 時間入力の文字列（240 → "4"）。割り切れない端数は分として扱えないため切り捨てる。 */
internal fun minutesToHoursInput(minutes: Int): String = (minutes / 60).toString()

internal fun hoursInputToMinutes(input: String): Int =
    (input.toIntOrNull() ?: 0).coerceAtLeast(0) * 60
