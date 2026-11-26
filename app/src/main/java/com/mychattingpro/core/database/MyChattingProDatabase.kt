package com.mychattingpro.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CallHistoryEntity::class,
        SecurityAlertEntity::class,
        HiddenMessageEntity::class,
        HiddenCallEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class MyChattingProDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun securityAlertDao(): SecurityAlertDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN pinnedAtMs INTEGER")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN archivedAtMs INTEGER")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN mutedUntilMs INTEGER")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN customSoundUri TEXT")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN customSoundLabel TEXT")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN markedUnread INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_conversations ADD COLUMN clearedAtMs INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_call_history (
                        callId TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        callerId TEXT NOT NULL,
                        peerUserId TEXT NOT NULL,
                        peerDisplayName TEXT NOT NULL,
                        peerAvatarMediaId TEXT,
                        callType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        startedAtMs INTEGER NOT NULL,
                        answeredAtMs INTEGER,
                        endedAtMs INTEGER,
                        durationSeconds INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_call_history_startedAtMs ON cached_call_history(startedAtMs)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN replyToMessageId TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN mediaWidth INTEGER")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN mediaHeight INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_conversations_isPrivate_isPinned_updatedAtMs ON cached_conversations(isPrivate, isPinned, updatedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_conversations_peerUserId ON cached_conversations(peerUserId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS security_alerts (
                        id TEXT NOT NULL PRIMARY KEY,
                        alertType TEXT NOT NULL,
                        deviceName TEXT,
                        sessionId TEXT,
                        createdAtMs INTEGER NOT NULL,
                        isSessionActive INTEGER NOT NULL DEFAULT 0,
                        isRead INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_alerts_createdAtMs ON security_alerts(createdAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_call_history_conversationId_startedAtMs ON cached_call_history(conversationId, startedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_call_history_peerUserId_startedAtMs ON cached_call_history(peerUserId, startedAtMs)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hidden_messages (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        hiddenAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hidden_calls (
                        callId TEXT NOT NULL PRIMARY KEY,
                        hiddenAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: MyChattingProDatabase? = null

        fun getInstance(context: Context): MyChattingProDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MyChattingProDatabase::class.java,
                    "my_chatting_pro_cache.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
