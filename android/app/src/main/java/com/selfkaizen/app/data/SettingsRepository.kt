package com.selfkaizen.app.data

import com.selfkaizen.app.rules.RuleSettings

/**
 * ルール設定（上限・接近閾値）の永続化。
 *
 * **保存先に `collection_state`（キー・バリュー表）を使う理由:**
 * Room のスキーマを変えると `fallbackToDestructiveMigration()` により
 * **使用履歴が全消去される**。設定はスカラー2〜3個なので、
 * 既存のキー・バリュー表で足りる。スキーマ変更を避ける方が価値が高い。
 *
 * 同期設定（トークンを含む）はここではなく
 * [com.selfkaizen.app.sync.SyncSettingsStore] に置く。
 * **トークンは暗号化が必要**なため、平文の Room に入れてはいけない
 * （`docs/PRIVACY.md` §3.2）。
 */
class SettingsRepository(private val dao: UsageDao) {

    /** 保存された設定を読む。未保存・壊れた値は既定値にフォールバックする。 */
    suspend fun load(): RuleSettings = SettingsMapping.fromMap(
        mapOf(
            SettingsMapping.KEY_LIMIT_MINUTES to dao.getState(SettingsMapping.KEY_LIMIT_MINUTES),
            SettingsMapping.KEY_APPROACHING_MINUTES to
                dao.getState(SettingsMapping.KEY_APPROACHING_MINUTES),
            SettingsMapping.KEY_LIMIT_ENABLED to dao.getState(SettingsMapping.KEY_LIMIT_ENABLED)
        )
    )

    /** 設定を保存する。 */
    suspend fun save(settings: RuleSettings) {
        SettingsMapping.toMap(settings).forEach { (key, value) ->
            dao.putState(CollectionState(key, value))
        }
    }
}

/**
 * 設定値 ↔ 保存形式の変換。
 *
 * **Android / Room に依存しない純粋なロジック**にしてある。
 * 壊れた保存値で例外を投げないことが重要なので、境界をテストで固定する。
 */
internal object SettingsMapping {

    const val KEY_LIMIT_MINUTES = "settings_limit_minutes"
    const val KEY_APPROACHING_MINUTES = "settings_approaching_minutes"
    const val KEY_LIMIT_ENABLED = "settings_limit_enabled"

    /**
     * 保存値から設定を復元する。
     *
     * **壊れた値でも例外を投げない。** `RuleSettings` は `init` で検証するため、
     * 範囲外の値だと生成に失敗する。保存値は端末の状態次第で壊れうるので、
     * その場合は既定値に戻す（アプリが起動しなくなる方が問題）。
     */
    fun fromMap(raw: Map<String, String?>): RuleSettings {
        val defaults = RuleSettings()

        val limit = raw[KEY_LIMIT_MINUTES]?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: defaults.dailyLimitMinutes

        val approaching = raw[KEY_APPROACHING_MINUTES]?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: defaults.approachingThresholdMinutes

        val enabled = raw[KEY_LIMIT_ENABLED]?.toBooleanStrictOrNull()
            ?: defaults.dailyLimitEnabled

        return runCatching {
            RuleSettings(
                dailyLimitMinutes = limit,
                approachingThresholdMinutes = approaching,
                dailyLimitEnabled = enabled
            )
        }.getOrDefault(defaults)
    }

    /** 設定を保存形式にする。 */
    fun toMap(settings: RuleSettings): Map<String, String> = mapOf(
        KEY_LIMIT_MINUTES to settings.dailyLimitMinutes.toString(),
        KEY_APPROACHING_MINUTES to settings.approachingThresholdMinutes.toString(),
        KEY_LIMIT_ENABLED to settings.dailyLimitEnabled.toString()
    )
}
