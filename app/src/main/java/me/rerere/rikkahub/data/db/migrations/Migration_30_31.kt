package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_30_31"

/**
 * 新增 translation_history 翻译历史表。
 *
 * 建表语句和索引与 3.1.0 里 Room 导出的 translation_history 定义逐字一致，只建表，不回填数据。
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (translation history)")
        DatabaseMigrationTracker.onMigrationStart(30, 31)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, " +
                    "`source_text` TEXT NOT NULL, `translated_text` TEXT NOT NULL, " +
                    "`target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, " +
                    "`create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` " +
                    "ON `translation_history` (`create_at`)"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 30 to 31 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
