package com.fbint.collector.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fbint.collector.data.local.entity.QueuedFileEntity
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.local.entity.SurveyEntity

@Database(
    entities = [SurveyEntity::class, QueuedResponseEntity::class, QueuedFileEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun responseQueueDao(): ResponseQueueDao
    abstract fun queuedFileDao(): QueuedFileDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_responses ADD COLUMN autoStampsJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_responses ADD COLUMN serverBaseUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE queued_responses ADD COLUMN allowedHiddenFieldsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE queued_files ADD COLUMN serverBaseUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE queued_files ADD COLUMN uploadingAt INTEGER DEFAULT NULL")
            }
        }

        // Real migration (not destructive) so devices with offline-queued responses don't
        // lose them on upgrade. v4 adds queued_responses.sendingAt for client-side dedup.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_responses ADD COLUMN sendingAt INTEGER DEFAULT NULL")
            }
        }
    }
}
