package com.selfkaizen.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * モノクロのデザイントークン。
 *
 * `design/SPEC.md` §2 の値をそのまま持つ。
 * **色相はゼロ**（全色 R=G=B）。赤だけが唯一の有彩色で、
 * **1日の上限を超えたときのみ**使う。
 *
 * Material の `ColorScheme` に載らないトークン（罫線・トラック・危険色）を
 * ここでまとめて持つ。
 */
data class KaizenColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val track: Color,
    /** 上限超過時のみ。コントラストは AA を満たす（SPEC で実測済み）。 */
    val danger: Color
)

/** Light — Apple 風。地 #F5F5F5 / 面 #FFFFFF。 */
private val LightColors = KaizenColors(
    bg = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF000000),
    ink2 = Color(0xFF6E6E6E),
    ink3 = Color(0xFFA1A1A1),
    line = Color(0xFFE4E4E4),
    track = Color(0xFFE9E9E9),
    danger = Color(0xFFC62828)   // 地 #F5F5F5 上で 5.16:1
)

/** Dark — Tesla 風。地 #0A0A0A / 面 #151515。 */
private val DarkColors = KaizenColors(
    bg = Color(0xFF0A0A0A),
    surface = Color(0xFF151515),
    ink = Color(0xFFFFFFFF),
    ink2 = Color(0xFF9A9A9A),
    ink3 = Color(0xFF616161),
    line = Color(0xFF252525),
    track = Color(0xFF242424),
    danger = Color(0xFFFF453A)   // 地 #0A0A0A 上で 5.81:1
)

val LocalKaizenColors = staticCompositionLocalOf { LightColors }

/** 数値は等幅数字（tabular numerals）で桁揺れを防ぐ。 */
val TabularNumberStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * アプリのテーマ。
 *
 * **OS の設定に自動追従する。手動切替は設けない**（SPEC §5）。
 * 設定項目を増やすと、それ自体が「触る対象」になり、
 * アプリを開く理由が設定いじりにすり替わるため。
 */
@Composable
fun SelfKaizenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val kaizen = if (dark) DarkColors else LightColors

    val scheme = if (dark) {
        darkColorScheme(
            background = kaizen.bg,
            surface = kaizen.surface,
            onBackground = kaizen.ink,
            onSurface = kaizen.ink,
            primary = kaizen.ink,
            onPrimary = kaizen.bg
        )
    } else {
        lightColorScheme(
            background = kaizen.bg,
            surface = kaizen.surface,
            onBackground = kaizen.ink,
            onSurface = kaizen.ink,
            primary = kaizen.ink,
            onPrimary = kaizen.bg
        )
    }

    CompositionLocalProvider(LocalKaizenColors provides kaizen) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 画面の見出し（小さな大文字ラベル）。広い字間を空ける。 */
val SectionLabelStyle = TextStyle(
    fontSize = 10.5.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.6.sp
)
