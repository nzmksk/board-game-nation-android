package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The configurations one play was set up with: Heat's Championship season, its Legends
 * and its Weather module, all three at once on the same race.
 *
 * Rows rather than the single `sessions.mode` column this grew out of, because a
 * configuration is not one answer. A game is put together out of the modules the table
 * felt like using that evening, and a string holding "Championship + Legends + Weather"
 * can be shown but never asked a question: which plays used Weather, and did the table
 * win more of them.
 *
 * Free text, for the reason `sessions.mode` was free text. Every game names its variants
 * differently and a structured column would have to be reinvented per game; what the
 * user needs is their own wording back, which the form offers as chips from previous
 * plays -- and now offers one module at a time rather than a whole combination that only
 * fits the evening it was typed on.
 *
 * The mode is half the primary key, so a play cannot carry the same one twice. Ordering
 * lives in [sortOrder] instead, which keeps the set reading in the order it was named.
 */
@Entity(
    tableName = "session_modes",
    primaryKeys = ["session_id", "mode"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionModeEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "mode") val mode: String,

    /** Keeps the set in the order it was entered rather than in whatever order it reads back. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0
)

/**
 * Gives a play that has a configuration but no rows for it the one-element set it always
 * meant.
 *
 * Written once and used twice: by the migration that introduced the table, and by an
 * import, which can be handed an archive exported before the table existed and whose
 * `sessions.mode` column is then the only place the answer is. Both are the same
 * question -- a play stored with one configuration was played with exactly one -- and
 * one statement means the two cannot answer it differently.
 *
 * Guarded on the play having no rows rather than on the column, so it can be run over a
 * database that is already half-converted without disturbing a set somebody has since
 * edited.
 */
const val BACKFILL_SESSION_MODES_SQL = "INSERT OR IGNORE INTO session_modes " +
    "(session_id, mode, sort_order) " +
    "SELECT id, trim(mode), 0 FROM sessions " +
    "WHERE mode IS NOT NULL AND trim(mode) <> '' " +
    "AND NOT EXISTS (SELECT 1 FROM session_modes m WHERE m.session_id = sessions.id)"
