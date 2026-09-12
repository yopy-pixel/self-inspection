package com.selfkaizen.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * マイグレーション。
 *
 * ## v4 → v5: `localDate` / `zoneId` の追加
 *
 * 区間を「どの端末ローカル日に属するか」を**収集時点で確定**して保存する。
 * これがないと、端末のタイムゾーンが変わったときに
 * 過去のイベントが別の日へ再配分される
 * （実測: 東京 2026-09-13 08:00 が America/New_York では 2026-09-12 になる）。
 *
 * **既存行のバックフィルについて:**
 * `localDate` は移行時点のローカル日付で最善努力の補完を行う。
 * 元のタイムゾーンは既に失われているため、これ以上は復元できない。
 * `zoneId` は SQL では求められないので空のままにし、
 * 集計側でシステム既定へフォールバックする。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE app_usage_event ADD COLUMN localDate TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE app_usage_event ADD COLUMN zoneId TEXT NOT NULL DEFAULT ''"
        )

        // 既存行を、移行時点のローカル日付で補完する（最善努力）。
        db.execSQL(
            """
            UPDATE app_usage_event
            SET localDate = strftime('%Y-%m-%d', startTime / 1000, 'unixepoch', 'localtime')
            WHERE localDate = ''
            """.trimIndent()
        )
    }
}

/** 登録する全マイグレーション。 */
val ALL_MIGRATIONS = arrayOf(MIGRATION_4_5)
