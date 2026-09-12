package com.selfkaizen.app

import com.google.common.truth.Truth.assertThat
import com.selfkaizen.app.data.SettingsMapping
import com.selfkaizen.app.rules.RuleSettings
import com.selfkaizen.app.ui.SettingsViewModel
import com.selfkaizen.app.ui.hoursInputToMinutes
import com.selfkaizen.app.ui.minutesToHoursInput
import org.junit.Test

/**
 * 設定の保存形式と入力変換の検証。
 *
 * 重視する点:
 *  - **壊れた保存値でアプリが落ちないこと**（既定値に戻す）
 *  - 入力の境界（空文字・巨大な値・負の値）
 */
class SettingsMappingTest {

    // ---------------- 保存 ↔ 復元 ----------------

    @Test
    fun `保存して復元すると同じ値になる`() {
        val original = RuleSettings(
            dailyLimitMinutes = 180,
            approachingThresholdMinutes = 15,
            dailyLimitEnabled = false
        )

        val restored = SettingsMapping.fromMap(SettingsMapping.toMap(original))

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `未保存なら既定値になる`() {
        val restored = SettingsMapping.fromMap(emptyMap())

        assertThat(restored).isEqualTo(RuleSettings())
    }

    @Test
    fun `壊れた値は既定値にフォールバックする`() {
        // 数値でない / 範囲外 / 不明な真偽値
        val restored = SettingsMapping.fromMap(
            mapOf(
                SettingsMapping.KEY_LIMIT_MINUTES to "abc",
                SettingsMapping.KEY_APPROACHING_MINUTES to "-5",
                SettingsMapping.KEY_LIMIT_ENABLED to "maybe"
            )
        )

        assertThat(restored).isEqualTo(RuleSettings())
    }

    @Test
    fun `一部だけ壊れていても他は読める`() {
        val restored = SettingsMapping.fromMap(
            mapOf(
                SettingsMapping.KEY_LIMIT_MINUTES to "120",
                SettingsMapping.KEY_APPROACHING_MINUTES to "not-a-number",
                SettingsMapping.KEY_LIMIT_ENABLED to "true"
            )
        )

        assertThat(restored.dailyLimitMinutes).isEqualTo(120)
        assertThat(restored.approachingThresholdMinutes)
            .isEqualTo(RuleSettings().approachingThresholdMinutes)
        assertThat(restored.dailyLimitEnabled).isTrue()
    }

    @Test
    fun `上限0は保存できるが有効時は設定画面が弾く`() {
        // 0 は「常に超過」になるため、保存自体は許すが UI で検証する。
        val zero = RuleSettings(dailyLimitMinutes = 0)
        val restored = SettingsMapping.fromMap(SettingsMapping.toMap(zero))

        assertThat(restored.dailyLimitMinutes).isEqualTo(0)
    }

    // ---------------- 入力変換 ----------------

    @Test
    fun `分から時間入力への変換は切り捨てる`() {
        assertThat(minutesToHoursInput(240)).isEqualTo("4")
        assertThat(minutesToHoursInput(0)).isEqualTo("0")
        assertThat(minutesToHoursInput(90)).isEqualTo("1")   // 端数は切り捨て
    }

    @Test
    fun `時間入力から分への変換`() {
        assertThat(hoursInputToMinutes("4")).isEqualTo(240)
        assertThat(hoursInputToMinutes("")).isEqualTo(0)      // 空文字で落ちない
        assertThat(hoursInputToMinutes("abc")).isEqualTo(0)   // 数字以外で落ちない
        assertThat(hoursInputToMinutes("-3")).isEqualTo(0)    // 負は0に丸める
    }

    // ---------------- 認証情報の貼り付け ----------------

    @Test
    fun `サーバーが返すJSONから deviceId と token を取り出す`() {
        val json = """{"deviceId":"abc-123","token":"deadbeef","label":"my phone"}"""

        assertThat(SettingsViewModel.extractJsonString(json, "deviceId")).isEqualTo("abc-123")
        assertThat(SettingsViewModel.extractJsonString(json, "token")).isEqualTo("deadbeef")
    }

    @Test
    fun `空白入りのJSONでも取り出せる`() {
        val json = """{ "deviceId" : "abc" , "token" : "def" }"""

        assertThat(SettingsViewModel.extractJsonString(json, "deviceId")).isEqualTo("abc")
        assertThat(SettingsViewModel.extractJsonString(json, "token")).isEqualTo("def")
    }

    @Test
    fun `存在しないキーは null`() {
        assertThat(SettingsViewModel.extractJsonString("""{"a":"b"}""", "token")).isNull()
    }

    @Test
    fun `空文字の値は null として扱う`() {
        assertThat(SettingsViewModel.extractJsonString("""{"token":""}""", "token")).isNull()
    }
}
