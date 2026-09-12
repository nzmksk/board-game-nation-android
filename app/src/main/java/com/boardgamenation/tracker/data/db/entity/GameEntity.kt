package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode

/**
 * The collection table. Expansions live here too, flagged by [isExpansion], so every
 * query that works on games works on expansions without a second table. What an
 * expansion expands is in [GameExpansionCrossRef].
 *
 * The unique index on [bggId] relies on SQLite treating NULLs as distinct, which gives
 * "unique where not null" without a partial index Room cannot express.
 */
@Entity(
    tableName = "games",
    indices = [
        Index(value = ["bgg_id"], unique = true),
        Index(value = ["title"]),
        Index(value = ["status"])
    ]
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "bgg_id") val bggId: Long? = null,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "year_published") val yearPublished: Int? = null,
    @ColumnInfo(name = "min_players") val minPlayers: Int? = null,
    @ColumnInfo(name = "max_players") val maxPlayers: Int? = null,
    @ColumnInfo(name = "min_playtime_minutes") val minPlaytimeMinutes: Int? = null,
    @ColumnInfo(name = "max_playtime_minutes") val maxPlaytimeMinutes: Int? = null,
    @ColumnInfo(name = "weight") val weight: Double? = null,
    @ColumnInfo(name = "bgg_rating") val bggRating: Double? = null,

    /** Local file path in app-private storage. Never a remote URL. */
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,

    /** ISO-8601 date (YYYY-MM-DD) the game entered the collection. */
    @ColumnInfo(name = "date_added") val dateAdded: String,
    @ColumnInfo(name = "price") val price: Double? = null,
    @ColumnInfo(name = "currency", defaultValue = "MYR") val currency: String = "MYR",
    @ColumnInfo(name = "purchase_note") val purchaseNote: String? = null,

    @ColumnInfo(name = "status") val status: GameStatus = GameStatus.OWNED,
    /** 1 (highest) to 5. Only meaningful while [status] is WISHLIST. */
    @ColumnInfo(name = "wishlist_priority") val wishlistPriority: Int? = null,

    /** Who has the copy, while [status] is LENT_OUT. */
    @ColumnInfo(name = "lent_to") val lentTo: String? = null,
    @ColumnInfo(name = "lent_date") val lentDate: String? = null,

    @ColumnInfo(name = "is_expansion", defaultValue = "0") val isExpansion: Boolean = false,

    /** Remembered so the session form opens in the shape this game needs. */
    @ColumnInfo(name = "scoring_mode", defaultValue = "RANKED_SCORES")
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,

    /** For RANKED_SCORES: false means lowest score wins (golf scoring). */
    @ColumnInfo(name = "high_score_wins", defaultValue = "1") val highScoreWins: Boolean = true,

    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * Which games an expansion expands.
 *
 * A link table rather than a column on [GameEntity], because the answer is a set. Ticket
 * to Ride: France plays on top of Ticket to Ride and of Ticket to Ride: Europe, and a
 * single column had to pick one of them and drop the other.
 *
 * Both ends point at `games`, so an expansion of an expansion is the same row shape as an
 * expansion of a base game: Legends of the Sea Robbers names Catan and Catan: Seafarers
 * and needs nothing more to say so. Nothing walks the graph transitively, so a chain
 * costs no more to read than a single link does.
 *
 * Deleting either end deletes the link and not the other game. That is the same promise
 * the old `base_game_id` made by being nullable: an expansion outlives the base game
 * somebody sold, because the box is still on the shelf.
 */
@Entity(
    tableName = "game_expansions",
    primaryKeys = ["expansion_id", "base_game_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["expansion_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["base_game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["base_game_id"])]
)
data class GameExpansionCrossRef(
    @ColumnInfo(name = "expansion_id") val expansionId: Long,
    @ColumnInfo(name = "base_game_id") val baseGameId: Long
)
