package com.selfkaizen.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfkaizen.app.collector.CollectionScheduler
import com.selfkaizen.app.ui.DashboardScreen
import com.selfkaizen.app.ui.DashboardViewModel
import com.selfkaizen.app.ui.PermissionScreen
import com.selfkaizen.app.ui.theme.SelfKaizenTheme
import com.selfkaizen.app.ui.SettingsScreen
import com.selfkaizen.app.ui.SettingsViewModel
import com.selfkaizen.app.sync.SyncScheduler
import com.selfkaizen.app.sync.SyncSettingsStore

/**
 * アプリの入口。
 *
 *  - 使用状況アクセスが未許可 → 権限導線
 *  - 許可済み → ダッシュボード（`design/SPEC.md`）
 *
 * テーマは **OS の設定に自動追従**する（手動切替は設けない）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 使用状況アクセスの許可状態。
     *
     * 注意: onResume で recreate() を呼ぶ実装は無限ループになる
     * （recreate → onResume → recreate …）。エミュレータ検証で実際に
     * 77回の relaunch が発生したため、状態更新のみに留める。
     */
    private var hasAccess by mutableStateOf(false)

    /**
     * 画面復帰のたびにデータを読み直すためのカウンタ。
     * 収集はバックグラウンドで進むため、戻るたびに最新化する。
     */
    private var refreshTick by mutableIntStateOf(0)

    /** 設定画面を表示中か。**タブは作らず**、この1フラグだけで切り替える（SPEC §6）。 */
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAccess = checkUsageAccess()

        // 定期収集を登録する。
        //
        // 必須の配線: これが無いと収集は端末再起動後（BootReceiver 経由）まで
        // 一度も走らない。KEEP 戦略なので毎回呼んでも二重登録にはならない。
        CollectionScheduler.schedulePeriodic(this)

        // 定期同期も登録する。
        //
        // SyncWorker 自身が「未設定なら何もしない」ため、ここでは設定の有無を
        // 見ずに登録してよい（設定保存時に改めて有効/無効を切り替える）。
        SyncScheduler.schedulePeriodic(this)

        // 許可済みなら、次回実行を待たずに今すぐ1回収集する。
        if (hasAccess) {
            CollectionScheduler.collectNow(this)
        }

        // アプリを開いたついでに1回同期する（`PLAN-android.md` Phase 3）。
        //
        // 同期は**UIのクリティカルパスに置かない**。表示はローカルの Room を
        // 読むため、この同期が終わるのを待つ必要はない（投げっぱなしでよい）。
        // 設定が未入力なら何もしない（無駄に端末を起こさないため、
        // ワーカーを積む前に設定を確認する）。
        if (SyncSettingsStore(this).load().isConfigured) {
            SyncScheduler.syncNow(this)
        }

        setContent {
            SelfKaizenTheme {
                if (!hasAccess) {
                    PermissionScreen(onOpenSettings = { openUsageAccessSettings() })
                    return@SelfKaizenTheme
                }

                // 画面は2つだけ。**タブも階層も作らない**（SPEC §6）。
                // ダッシュボードを主、設定を従とする。
                if (showSettings) {
                    val settingsVm: SettingsViewModel = viewModel()
                    val settingsState by settingsVm.state.collectAsState()

                    SettingsScreen(
                        state = settingsState,
                        onLimitMinutes = settingsVm::setLimitMinutes,
                        onApproachingMinutes = settingsVm::setApproachingMinutes,
                        onLimitEnabled = settingsVm::setLimitEnabled,
                        onSyncEnabled = settingsVm::setSyncEnabled,
                        onEndpoint = settingsVm::setEndpoint,
                        onDeviceId = settingsVm::setDeviceId,
                        onToken = settingsVm::setToken,
                        onPasteCredentials = settingsVm::applyPastedCredentials,
                        onTestConnection = settingsVm::testConnection,
                        onSyncNow = settingsVm::syncNow,
                        onSave = {
                            settingsVm.save()
                            // 上限を変えたらダッシュボードの判定も変わるため読み直す
                            refreshTick++
                        },
                        onClose = {
                            showSettings = false
                            refreshTick++
                        }
                    )
                } else {
                    val vm: DashboardViewModel = viewModel()
                    val state by vm.state.collectAsState()

                    // 起動時・画面復帰時・設定変更時に読み直す
                    LaunchedEffect(refreshTick) { vm.refresh() }

                    DashboardScreen(
                        state = state,
                        onOpenSettings = { showSettings = true },
                        // 7日チャートの棒をタップして、その日の内訳に切り替える。
                        onSelectDate = vm::selectDate
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻った可能性があるため、許可状態を再評価する。
        // recreate() は呼ばない（無限ループになる）。
        val wasGranted = hasAccess
        hasAccess = checkUsageAccess()

        // 未許可 → 許可 に変わった瞬間に、初回収集を走らせる。
        if (!wasGranted && hasAccess) {
            CollectionScheduler.schedulePeriodic(this)
            CollectionScheduler.collectNow(this)
        }

        // 表示中のデータを最新化する
        refreshTick++
    }

    /**
     * 使用状況アクセスの許可状態を判定する。
     *
     * 注意: ロック解除前は正しく判定できない場合がある（Android R 以降の既知の挙動）。
     */
    private fun checkUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 使用状況アクセスの設定画面を開く。
     *
     * 端末によっては該当画面へ直接遷移できないため、
     * resolveActivity で確認し、失敗時は設定トップへフォールバックする。
     */
    private fun openUsageAccessSettings() {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        val intent = if (direct.resolveActivity(packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
