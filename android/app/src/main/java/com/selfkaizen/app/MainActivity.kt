package com.selfkaizen.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfkaizen.app.collector.CollectionScheduler
import com.selfkaizen.app.notify.NotifyScheduler
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

    /**
     * 通知許可（POST_NOTIFICATIONS）の要求。
     *
     * API 33+ で必要。**未許可でも他の機能は全て動く**ので、
     * 拒否されたら黙って進む（通知だけが出なくなる）。
     * 2回拒否すると OS がダイアログを出さなくなるため、
     * 起動ごとに呼んでも無限に煩わすことはない。
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 許可された場合もされない場合も、やることは変わらない。
            // 実際に出せるかは通知時に UsageNotifier.canPost() で判定する。
            android.util.Log.i("MainActivity", "通知許可: $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAccess = checkUsageAccess()

        // デバッグ用: adb から同期設定を流し込む。
        //
        // MIUI/HyperOS は `adb shell input` を INJECT_EVENTS で拒否するため、
        // 画面を操作せずに設定を入れる経路を用意した（2026-09-14）。
        // debug ビルドでのみ動く（`applySetupFromIntent` 内で判定）。
        applySetupFromIntent(intent)

        // 定期収集を登録する。
        //
        // 必須の配線: これが無いと収集は端末再起動後（BootReceiver 経由）まで
        // 一度も走らない。KEEP 戦略なので毎回呼んでも二重登録にはならない。
        CollectionScheduler.schedulePeriodic(this)

        // 12時間ごとの通知アラームを登録する。
        //
        // アラームは端末再起動で消えるため、BootReceiver でも再登録する。
        // 同じ PendingIntent への再登録は置き換えになるので二重にはならない。
        NotifyScheduler.schedule(this)

        // 通知許可を求める。使用状況アクセスが未許可の段階で求めても
        // 文脈が無いので、許可済みのときだけ聞く。
        requestNotificationPermissionIfNeeded()

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
                        onPairBrowser = settingsVm::pairBrowser,
                        onClearPairCode = settingsVm::clearPairCode,
                        onSave = {
                            settingsVm.save()
                            // 上限を変えたらダッシュボードの判定も変わるため読み直す
                            refreshTick++
                        },
                        onClose = {
                            // 「保存せず閉じる」= 編集中の値を破棄する。
                            // ViewModel は Activity スコープで画面を閉じても生き残るため、
                            // 読み直さないと破棄した値が次に開いたときに残ってしまう。
                            settingsVm.load()
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

        // 使用状況アクセスを許可した直後に通知許可も続けて聞く。
        // ここで聞かないと、次回起動まで通知が出せないままになる。
        if (!wasGranted && hasAccess) {
            requestNotificationPermissionIfNeeded()
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
     * デバッグ用: `adb` から同期設定を流し込む。
     *
     * なぜ必要か: MIUI/HyperOS は `adb shell input` を INJECT_EVENTS 権限の
     * 欠如として拒否するため、実機で設定画面を自動入力できない。
     * 手入力の代わりに Intent の extra で設定を渡せるようにした。
     *
     * ```
     * adb shell am start -n com.selfkaizen.app/.MainActivity \
     *   --es endpoint https://... --es deviceId ... --es token ... --ez enableSync true
     * ```
     *
     * **debug ビルドでのみ動く。** リリースビルドでは何もせず即座に戻る。
     *
     * ただし debug ビルドでは、この Activity は exported なので
     * **他アプリからも同じ extra を送れば設定を書き換えられる**。
     * 検証用の割り切りであり、release では経路ごと消える。
     * トークンは絶対にログへ出さない（`SyncSettingsStore` と同じ方針）。
     */
    private fun applySetupFromIntent(intent: Intent?) {
        // debug ビルド以外では一切受け付けない。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return

        val extras = intent ?: return

        val endpoint = extras.getStringExtra(EXTRA_ENDPOINT)
        val deviceId = extras.getStringExtra(EXTRA_DEVICE_ID)
        val token = extras.getStringExtra(EXTRA_TOKEN)
        if (endpoint.isNullOrBlank() && deviceId.isNullOrBlank() && token.isNullOrBlank()) {
            // 通常の起動（extra なし）。既存設定には触れない。
            return
        }

        val store = SyncSettingsStore(this)
        val current = store.load()

        // 渡された項目だけを上書きする。渡されなかった項目は既存値を保つ。
        val merged = current.copy(
            endpoint = endpoint?.takeIf { it.isNotBlank() } ?: current.endpoint,
            deviceId = deviceId?.takeIf { it.isNotBlank() } ?: current.deviceId,
            token = token?.takeIf { it.isNotBlank() } ?: current.token,
            enabled = if (extras.hasExtra(EXTRA_ENABLE_SYNC)) {
                extras.getBooleanExtra(EXTRA_ENABLE_SYNC, current.enabled)
            } else {
                current.enabled
            }
        )

        // 3項目が揃って初めて意味があるため、欠けている間は保存しない
        // （中途半端な設定で有効化されると、失敗し続けるワーカーが積まれる）。
        if (!merged.isConfigured) {
            android.util.Log.w(
                "MainActivity",
                "adb 設定が不完全なため無視した (endpoint=${merged.endpoint.isNotBlank()}, " +
                    "deviceId=${merged.deviceId.isNotBlank()}, token=${merged.token.isNotBlank()}, " +
                    "enabled=${merged.enabled})"
            )
            return
        }

        store.save(merged)
        // 値そのものは出さない。出したのは「入ったかどうか」だけ。
        android.util.Log.i("MainActivity", "adb から同期設定を保存した (endpoint=${merged.endpoint})")

        // ここでは同期を投げない。この直後の通常の配線
        // （schedulePeriodic / isConfigured を見た syncNow）が
        // 保存済みの設定を読んで実行するため、二重に積む必要がない。
    }

    /**
     * 通知許可を求める（API 33+ のみ）。
     *
     * 使用状況アクセスが未許可のときは求めない。
     * 「何も記録できていない」段階で通知の許可を求めても、
     * ユーザーには何の通知か分からないため。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!hasAccess) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private companion object {
        /** `applySetupFromIntent` が読む extra。debug ビルド専用。 */
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ENABLE_SYNC = "enableSync"
    }
}
