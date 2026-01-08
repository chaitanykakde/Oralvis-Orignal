package com.oralvis.oralviscamera.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
    entities = [MediaRecord::class, MediaRecordV2::class, Session::class, Patient::class],
    version = 14, // Added MediaRecordV2 for production-grade media management
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mediaDaoV2(): MediaDaoV2
    abstract fun sessionDao(): SessionDao
    abstract fun patientDao(): PatientDao

    companion object {
        @Volatile
        private var INSTANCE: MediaDatabase? = null

        // Migration from v13 to v14: Add media_v2 table and migrate data
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create new media_v2 table
                database.execSQL("""
                    CREATE TABLE media_v2 (
                        mediaId TEXT PRIMARY KEY NOT NULL,
                        patientId INTEGER NOT NULL,
                        sessionId TEXT,
                        state TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        filePath TEXT,
                        fileSizeBytes INTEGER,
                        checksum TEXT,
                        mediaType TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        captureTime INTEGER NOT NULL,
                        cloudFileName TEXT,
                        s3Url TEXT,
                        uploadedAt INTEGER,
                        dentalArch TEXT,
                        sequenceNumber INTEGER,
                        guidedSessionId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)

                // Create indexes
                database.execSQL("CREATE INDEX idx_media_v2_patient ON media_v2(patientId)")
                database.execSQL("CREATE INDEX idx_media_v2_session ON media_v2(sessionId)")
                database.execSQL("CREATE INDEX idx_media_v2_state ON media_v2(state)")
                database.execSQL("CREATE INDEX idx_media_v2_cloud_file ON media_v2(cloudFileName)")

                // Migrate existing data
                database.execSQL("""
                    INSERT INTO media_v2 (
                        mediaId, patientId, sessionId, state, fileName, filePath,
                        mediaType, mode, captureTime, cloudFileName, s3Url,
                        dentalArch, sequenceNumber, guidedSessionId,
                        createdAt, updatedAt
                    )
                    SELECT
                        LOWER(HEX(RANDOMBLOB(4))) || '-' || LOWER(HEX(RANDOMBLOB(2))) || '-4' ||
                        SUBSTR(LOWER(HEX(RANDOMBLOB(2))), 2) || '-a' ||
                        SUBSTR(LOWER(HEX(RANDOMBLOB(2))), 2) || '-' ||
                        LOWER(HEX(RANDOMBLOB(6))) as mediaId,
                        CASE
                            WHEN s.patientId IS NOT NULL THEN s.patientId
                            WHEN m.patientId IS NOT NULL THEN m.patientId
                            ELSE 0 -- Fallback, should not happen
                        END as patientId,
                        m.sessionId,
                        CASE
                            WHEN m.isSynced = 1 THEN 'SYNCED'
                            WHEN m.isFromCloud = 1 THEN 'DOWNLOADED'
                            ELSE 'DB_COMMITTED'
                        END as state,
                        m.fileName, m.filePath, m.mediaType, m.mode,
                        strftime('%s', m.captureTime) * 1000,
                        m.cloudFileName, m.s3Url,
                        m.dentalArch, m.sequenceNumber, m.guidedSessionId,
                        strftime('%s', 'now') * 1000,
                        strftime('%s', 'now') * 1000
                    FROM media m
                    LEFT JOIN sessions s ON m.sessionId = s.sessionId
                """)

                // Note: Old media table is preserved for rollback capability
                // It will be dropped in a future migration after validation
            }
        }

        fun getDatabase(context: Context): MediaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "media_database"
                )
                .addMigrations(MIGRATION_13_14)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): MediaDatabase {
            return getDatabase(context)
        }
    }
}
