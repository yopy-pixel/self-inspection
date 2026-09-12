package com.selfkaizen.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 同期設定の保存。
 *
 * **トークンは `EncryptedSharedPreferences` に保存する。**
 * 鍵は Android Keystore が管理する（`docs/PRIVACY.md` §3.2）。
 * 平文の `SharedPreferences` は root 化端末やバックアップ経由で読まれるため使わない。
 *
 * **トークンをログに出さないこと。** このクラスは意図的にログを一切出さない。
 */
class SyncSettingsStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(): SyncSettings = SyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
        deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty()
    )

    fun save(settings: SyncSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_DEVICE_ID, settings.deviceId.trim())
            .putString(KEY_TOKEN, settings.token.trim())
            .apply()
    }

    /**
     * トークンと端末IDのみ消す（設定の有効/無効は残す）。
     * 端末を登録し直すときに使う。
     */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_TOKEN)
            .apply()
    }

    /** 同期を無効化する。ローカル動作には影響しない。 */
    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
    }

    private companion object {
        const val FILE_NAME = "selfkaizen_sync_secure"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
    }
}
