package com.selfkaizen.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.SettingsRepository
import com.selfkaizen.app.rules.RuleSettings
import com.selfkaizen.app.sync.ConnectionCheck
import com.selfkaizen.app.sync.SyncClient
import com.selfkaizen.app.sync.SyncScheduler
import com.selfkaizen.app.sync.SyncSettings
import com.selfkaizen.app.sync.SyncSettingsStore
import com.selfkaizen.app.sync.SyncWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 接続テストの状態。 */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Ok(val checkedAt: Long) : ConnectionTestState
    data class Unauthorized(val status: Int) : ConnectionTestState
    data class Failed(val message: String) : ConnectionTestState
}

/** 設定画面の状態。 */
data class SettingsUiState(
    val loading: Boolean = true,
    // ルール設定（上限・接近閾値）
    val limitMinutes: Int = 240,
    val approachingMinutes: Int = 10,
    val limitEnabled: Boolean = true,
    // 同期設定
    val syncEnabled: Boolean = false,
    val endpoint: String = "",
    val deviceId: String = "",
    val token: String = "",
    // 状態表示
    val connection: ConnectionTestState = ConnectionTestState.Idle,
    val lastSyncAt: Long? = null,
    val savedAt: Long? = null,
    val error: String? = null
) {
    val isConfigured: Boolean
        get() = syncEnabled && endpoint.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank()

    val limitMillis: Long get() = limitMinutes * 60_000L
}

/**
 * 設定画面のロジック。
 *
 * 保存先が2つに分かれている理由:
 *  - **ルール設定**（上限・接近閾値）→ Room のキー・バリュー表
 *  - **同期設定**（トークンを含む）→ `EncryptedSharedPreferences`
 *
 * トークンは機密なので平文の Room には置かない（`docs/PRIVACY.md` §3.2）。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = DatabaseProvider.get(app).usageDao()
    private val ruleRepo = SettingsRepository(dao)
    private val syncStore = SyncSettingsStore(app)

    /** すでに反映済みの同期実行ID（同じ実行で二重に再読込しないため）。 */
    private val seenRunIds = mutableSetOf<UUID>()

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
        observeSyncWork()
    }

    /**
     * 永続化されている値で state を作り直す。
     *
     * **`×`（保存せず閉じる）でもこれを呼ぶこと。** この ViewModel は
     * Activity スコープなので、画面を閉じてもインスタンスは生き続ける。
     * 再読込しないと、破棄したはずの編集値が次に開いたときに残り、
     * そのまま `Save` を押すと破棄したつもりの値が保存されてしまう
     * （実機検証で発生: `×` で閉じたのに上限 24h が残っていた）。
     */
    fun load() {
        viewModelScope.launch {
            val rules = withContext(Dispatchers.IO) { ruleRepo.load() }
            val sync = syncStore.load()
            val lastSyncAt = withContext(Dispatchers.IO) {
                dao.getState(SyncWorker.KEY_LAST_SYNC_AT)?.toLongOrNull()
            }

            _state.value = _state.value.copy(
                loading = false,
                limitMinutes = rules.dailyLimitMinutes,
                approachingMinutes = rules.approachingThresholdMinutes,
                limitEnabled = rules.dailyLimitEnabled,
                syncEnabled = sync.enabled,
                endpoint = sync.endpoint,
                deviceId = sync.deviceId,
                token = sync.token,
                lastSyncAt = lastSyncAt,
                // 読み直した直後は「保存済み」でも「接続確認済み」でもない。
                savedAt = null,
                error = null,
                connection = ConnectionTestState.Idle
            )
        }
    }

    /**
     * 同期の完了を監視して、最終同期の表示を更新する。
     *
     * これが無いと「Sync now」を押しても表示が `never synced` のままになる
     * （同期は非同期に走るため。エミュレータ検証で発生した）。
     */
    private fun observeSyncWork() {
        viewModelScope.launch {
            runCatching {
                WorkManager.getInstance(getApplication())
                    .getWorkInfosForUniqueWorkFlow(SyncWorker.IMMEDIATE_WORK_NAME)
                    .collect { infos ->
                        infos.forEach { info ->
                            if (info.state == WorkInfo.State.SUCCEEDED &&
                                seenRunIds.add(info.id)
                            ) {
                                refreshSyncStatus()
                            }
                        }
                    }
            }
        }
    }

    /** 最終同期の時刻だけを読み直す（設定の入力内容は触らない）。 */
    private fun refreshSyncStatus() {
        viewModelScope.launch {
            val at = withContext(Dispatchers.IO) {
                dao.getState(SyncWorker.KEY_LAST_SYNC_AT)?.toLongOrNull()
            }
            _state.value = _state.value.copy(lastSyncAt = at)
        }
    }

    // ---- 編集 ----

    /**
     * 編集したら「Saved」表示を消す。
     *
     * これをしないと、一度保存したあとに値を書き換えても「Saved」が
     * 残り続け、**未保存の変更があるのに保存済みに見える**。
     * `Save` と `×`（保存せず閉じる）が別操作になったため、
     * どちらの状態かを常に正しく見せる必要がある。
     */
    private fun markDirty(): SettingsUiState {
        // エラー表示は編集で消す（前回の失敗が残ると誤解を招く）。
        return _state.value.copy(savedAt = null, error = null)
    }

    fun setLimitMinutes(minutes: Int) {
        _state.value = markDirty().copy(limitMinutes = minutes.coerceIn(0, MAX_LIMIT_MINUTES))
    }

    fun setApproachingMinutes(minutes: Int) {
        _state.value = markDirty().copy(
            approachingMinutes = minutes.coerceIn(0, MAX_APPROACHING_MINUTES)
        )
    }

    fun setLimitEnabled(enabled: Boolean) {
        _state.value = markDirty().copy(limitEnabled = enabled)
    }

    fun setSyncEnabled(enabled: Boolean) {
        _state.value = markDirty().copy(syncEnabled = enabled)
    }

    fun setEndpoint(value: String) {
        // 末尾の空白・改行は貼り付け時に混入しやすいので落とす。
        _state.value = markDirty()
            .copy(endpoint = value.trim(), connection = ConnectionTestState.Idle)
    }

    fun setDeviceId(value: String) {
        _state.value = markDirty()
            .copy(deviceId = value.trim(), connection = ConnectionTestState.Idle)
    }

    fun setToken(value: String) {
        _state.value = markDirty()
            .copy(token = value.trim(), connection = ConnectionTestState.Idle)
    }

    /** サーバーが返した JSON（deviceId と token）をまとめて貼り付ける。 */
    fun applyPastedCredentials(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return

        val deviceId = extractJsonString(trimmed, "deviceId")
        val token = extractJsonString(trimmed, "token")

        if (deviceId != null || token != null) {
            _state.value = markDirty().copy(
                deviceId = deviceId ?: _state.value.deviceId,
                token = token ?: _state.value.token,
                connection = ConnectionTestState.Idle
            )
        } else {
            // JSON でなければ、1行目を deviceId、2行目を token として扱う。
            val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            _state.value = markDirty().copy(
                deviceId = lines.getOrNull(0) ?: _state.value.deviceId,
                token = lines.getOrNull(1) ?: _state.value.token,
                connection = ConnectionTestState.Idle
            )
        }
    }

    // ---- 保存 ----

    fun save() {
        val s = _state.value

        // 上限0は「常に超過」になってしまうため、有効時は最低1分を要求する。
        if (s.limitEnabled && s.limitMinutes < 1) {
            _state.value = s.copy(error = "limit must be at least 1 minute")
            return
        }

        viewModelScope.launch {
            persist()
            _state.value = _state.value.copy(
                savedAt = System.currentTimeMillis(),
                error = null
            )
        }
    }

    /**
     * いますぐ同期する。
     *
     * **必ず先に保存する。** 保存せずに同期すると、`SyncWorker` は
     * 永続化された設定（＝空）を読むため何も送らない。
     * 「入力したのに同期されない」という分かりにくい状態になる
     * （エミュレータ検証で実際に発生した）。
     *
     * **ここで `savedAt` は立てない。** 保存は同期の前提条件として
     * 黙って行うものであり、押した覚えのない「Saved」を出すと
     * 「何が保存されたのか」が分からなくなる。この操作の結果は
     * 「last sync」の行（絶対時刻＋挿入件数）で示す。
     */
    fun syncNow() {
        val s = _state.value
        if (s.limitEnabled && s.limitMinutes < 1) {
            _state.value = s.copy(error = "limit must be at least 1 minute")
            return
        }

        viewModelScope.launch {
            persist()
            val context = getApplication<Application>()
            if (_state.value.isConfigured) {
                SyncScheduler.schedulePeriodic(context)
                SyncScheduler.syncNow(context)
            }
            _state.value = _state.value.copy(error = null)
        }
    }

    /** ルール設定と同期設定を永続化し、スケジュールを整合させる。 */
    private suspend fun persist() {
        val s = _state.value

        withContext(Dispatchers.IO) {
            ruleRepo.save(
                RuleSettings(
                    dailyLimitMinutes = s.limitMinutes,
                    approachingThresholdMinutes = s.approachingMinutes,
                    dailyLimitEnabled = s.limitEnabled
                )
            )
        }
        syncStore.save(
            SyncSettings(
                enabled = s.syncEnabled,
                endpoint = s.endpoint,
                deviceId = s.deviceId,
                token = s.token
            )
        )

        // 同期の有効/無効に応じてスケジュールを切り替える。
        val context = getApplication<Application>()
        if (s.isConfigured) {
            SyncScheduler.schedulePeriodic(context)
        } else {
            SyncScheduler.cancelPeriodic(context)
        }
    }

    // ---- 接続テスト ----

    fun testConnection() {
        val s = _state.value
        _state.value = s.copy(connection = ConnectionTestState.Testing)

        viewModelScope.launch {
            val settings = SyncSettings(
                enabled = true, // テストは有効/無効に関係なく行う
                endpoint = s.endpoint,
                deviceId = s.deviceId,
                token = s.token
            )

            val result = withContext(Dispatchers.IO) {
                SyncClient().checkConnection(settings)
            }

            _state.value = _state.value.copy(
                connection = when (result) {
                    is ConnectionCheck.Ok ->
                        ConnectionTestState.Ok(System.currentTimeMillis())
                    is ConnectionCheck.Unauthorized ->
                        ConnectionTestState.Unauthorized(result.status)
                    is ConnectionCheck.Failed ->
                        ConnectionTestState.Failed(result.message)
                }
            )
        }
    }

    companion object {
        const val MAX_LIMIT_MINUTES = 24 * 60
        const val MAX_APPROACHING_MINUTES = 24 * 60

        /** 簡易的な JSON 値抽出。`{"deviceId":"...","token":"..."}` を想定。 */
        internal fun extractJsonString(json: String, key: String): String? {
            val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }
}
