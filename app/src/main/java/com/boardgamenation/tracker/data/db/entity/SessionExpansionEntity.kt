package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Which expansions were on the table for a given play. */
@Entity(
    tableName = "session_expansions",
    primaryKeys = ["session_id", "game_id"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["game_id"])]
)
data class SessionExpansionEntity(@ColumnInfo(name = "session_id") val sessionId: Long, @ColumnInfo(name = "game_id") val gameId: Long)

/**
 * Every game that was on the table for a play: the game the play was logged against, and
 * each expansion that went out with it.
 *
 * A play names one game, and that game is the base game; the expansions used are rows in
 * [SessionExpansionEntity]. Read literally, that leaves an expansion with no plays at
 * all, and so with no cost per play, a permanent place on the shelf of shame and a line
 * in the dead weight -- while being carried to more game nights than most of the
 * collection.
 *
 * This view is the one place that says otherwise, so that no query has to remember the
 * second table the way none of them has to remember that `price` is only half the bill.
 *
 * Nothing is filtered here. `is_draft` and `is_invalid` stay the caller's business,
 * exactly as they are when reading `sessions` directly: a draft that already names its
 * expansions is still a draft, and the flags mean the same thing on this row as on the
 * session it came from.
 *
 * `UNION` rather than `UNION ALL`, so a play that somehow lists its own game among its
 * expansions is one play of that game rather than two.
 *
 * A row is tested against it with `IN` rather than with a correlated `EXISTS`. SQLite
 * cannot flatten a compound select into the query around it, and a correlated use would
 * therefore build the whole view again for every row it tested, which at five thousand
 * plays is the quadratic the aggregate queries exist to avoid. Uncorrelated, it is built
 * once and probed.
 *
 * What it answers is "was this box on the table, and how often", which is the question
 * behind every per-game figure. It is deliberately not what the rankings over plays --
 * most played, the h-index, plays by month -- are built from: there an evening is one
 * play of the game it was logged against, and counting it again for each expansion would
 * draw one evening as four bars.
 */
const val SESSION_GAMES_SQL = "SELECT s.id AS session_id, s.game_id AS game_id FROM sessions s " +
    "UNION SELECT se.session_id AS session_id, se.game_id AS game_id FROM session_expansions se"

@DatabaseView(viewName = SessionGamesView.NAME, value = SESSION_GAMES_SQL)
data class SessionGamesView(@ColumnInfo(name = "session_id") val sessionId: Long, @ColumnInfo(name = "game_id") val gameId: Long) {
    companion object {
        const val NAME = "session_games"
    }
}
