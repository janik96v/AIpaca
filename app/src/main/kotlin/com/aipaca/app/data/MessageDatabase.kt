package com.aipaca.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Room database for indexing chat messages with FTS5 full-text search.
 *
 * Used by [SessionSearchTool] to let the agent search past conversations
 * without any LLM calls — pure SQLite FTS5 with BM25 ranking.
 */
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "aipaca_messages.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // Create FTS5 virtual table for full-text search
                            db.execSQL("""
                                CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                                    content, session_title,
                                    content='messages',
                                    content_rowid='rowid'
                                )
                            """.trimIndent())

                            // Triggers to keep FTS index in sync with messages table
                            db.execSQL("""
                                CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                                    INSERT INTO messages_fts(rowid, content, session_title)
                                    VALUES (new.rowid, new.content, new.session_title);
                                END
                            """.trimIndent())

                            db.execSQL("""
                                CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                                    INSERT INTO messages_fts(messages_fts, rowid, content, session_title)
                                    VALUES ('delete', old.rowid, old.content, old.session_title);
                                END
                            """.trimIndent())

                            db.execSQL("""
                                CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                                    INSERT INTO messages_fts(messages_fts, rowid, content, session_title)
                                    VALUES ('delete', old.rowid, old.content, old.session_title);
                                    INSERT INTO messages_fts(rowid, content, session_title)
                                    VALUES (new.rowid, new.content, new.session_title);
                                END
                            """.trimIndent())
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,           // "user", "assistant", "system"
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "session_title") val sessionTitle: String = ""
)

/**
 * FTS5 search result — a matching message plus its session context.
 */
data class FtsSearchResult(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * FTS5 search with BM25 ranking. Returns matching messages ordered by relevance.
     * Uses @RawQuery because Room can't verify FTS5 virtual tables created in a callback.
     */
    @RawQuery
    suspend fun rawSearch(query: SupportSQLiteQuery): List<FtsSearchResult>

    suspend fun searchFts(query: String, limit: Int = 10): List<FtsSearchResult> {
        val sql = """
            SELECT m.session_id AS sessionId, m.session_title AS sessionTitle,
                   m.id AS messageId, m.role, m.content, m.timestamp
            FROM messages m
            INNER JOIN messages_fts ON messages_fts.rowid = m.rowid
            WHERE messages_fts MATCH ?
            ORDER BY bm25(messages_fts)
            LIMIT ?
        """.trimIndent()
        return rawSearch(SimpleSQLiteQuery(sql, arrayOf(query, limit)))
    }

    /**
     * Get all messages for a session, ordered by timestamp.
     */
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionMessages(sessionId: String): List<MessageEntity>

    /**
     * Count total messages (for migration progress tracking).
     */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}
