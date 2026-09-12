package com.selfkaizen.app.data

import android.content.Context
import androidx.room.Room

/**
 * データベースのシングルトン。
 *
 * 個人用ツールなので DI フレームワークは使わず、
 * シンプルな遅延初期化に留める。
 */
object DatabaseProvider {

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "selfkaizen.db"
        )
            // スキーマ変更時に破壊的移行を許容する。
            // 個人用ツールであり、生イベントは再収集可能なため。
            // ただし queryEvents の保持期間が数日である点に注意:
            // 破壊的移行をすると数日分しか復元できない。
            .addMigrations(*ALL_MIGRATIONS)
            // 未定義のバージョン差は破壊的に移行する。
            // 生イベントは再収集可能だが、queryEvents の保持期間が
            // 数日である点に注意（数日分しか復元できない）。
            .fallbackToDestructiveMigration()
            .build()
}
