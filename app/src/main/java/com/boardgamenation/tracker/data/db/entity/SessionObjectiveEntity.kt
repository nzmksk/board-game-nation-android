package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * What one objective cost the table: the hints it took to crack it, and the goes it took
 * to get it right.
 *
 * This is the record an investigative or escape-room game actually leaves behind. Unlock!,
 * Exit, Chronicles of Crime and Sherlock Holmes Consulting Detective are all but always
 * winnable and very rarely lost, so a win/loss says almost nothing about how the evening
 * went -- a case solved clean and a case solved on the fourth hint read identically. What
 * separates them is per objective, which is why this is a table of its own and not two
 * columns on the play: a case is a handful of puzzles and each has its own story.
 *
 * Free text for the objective, for the reason the configuration is free text. Every game
 * names its puzzles differently -- a room, a chapter, a lead, a lock, a numbered card --
 * and the user's own wording is the whole point. Unlike a configuration it is not offered
 * back as a chip on the next play: a puzzle belongs to one case and the next case has
 * different ones, so a suggestion here would only ever be somebody else's answer.
 *
 * The objective is half the primary key, so one play cannot carry the same one twice.
 * Ordering lives in [sortOrder] instead, which keeps the puzzles reading in the order
 * they were worked through rather than in whatever order they read back.
 */
@Entity(
    tableName = "session_objectives",
    primaryKeys = ["session_id", "objective"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionObjectiveEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,

    /** What the table was trying to do: "The locked safe", "Chapter 2", "Lead 41". */
    @ColumnInfo(name = "objective") val objective: String,

    /**
     * Hints taken before it fell. Zero is the answer worth recording as much as any
     * other -- an objective solved with nothing but the table's own reasoning is the
     * whole reason the column exists.
     */
    @ColumnInfo(name = "hints_used", defaultValue = "0") val hintsUsed: Int = 0,

    /**
     * Goes it took, counting the one that worked.
     *
     * Defaults to one rather than to none, because writing the objective down at all
     * says the table had a go at it. Zero attempts would describe a puzzle nobody
     * touched, and a puzzle nobody touched is one nobody would think to type in.
     */
    @ColumnInfo(name = "attempts", defaultValue = "1") val attempts: Int = 1,

    /** Keeps the list in the order it was entered rather than in alphabetical order. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0
)
