package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Money spent on a game that is not the game itself: sleeves, an insert, a playmat, the
 * shipping on a preorder.
 *
 * Rows rather than columns, because the list is open-ended and personal. Somebody who
 * sleeves everything and somebody who buys one insert a year would need different
 * columns, and neither should need a migration to record what they actually spent.
 *
 * There is no currency here. An accessory is bought in the same money as the game it
 * belongs to, and the game already carries the code; a second one would only ever let
 * a total be summed across currencies that cannot be added up.
 */
@Entity(
    tableName = "game_costs",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["game_id"])]
)
data class GameCostEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: Long,

    /** Free text, as typed: "Sleeves", "Broken Token insert", "Shipping". */
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "amount") val amount: Double,

    /** Keeps the rows in the order they were entered rather than by id alone. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0
)

/**
 * What each game has cost in total, so no query has to remember that the price column
 * is only half the answer.
 *
 * [GameCostingView.totalCost] stays null for a game with no price and no accessories,
 * which is what every costing query already means by "unpriced": those games are
 * excluded rather than counted as free. A game with accessories but no price does have
 * a cost, and gets one.
 *
 * A `const` rather than a literal in the annotation because a migration has to create
 * this view by hand on an upgraded database, and Room then checks the text it finds
 * against the text it expects. One constant means the two cannot drift apart over a
 * space.
 */
const val GAME_COSTING_SQL = "SELECT g.id AS game_id, g.price AS price, " +
    "COALESCE(a.total, 0) AS accessories_total, " +
    "CASE WHEN g.price IS NULL AND COALESCE(a.total, 0) = 0 THEN NULL " +
    "ELSE COALESCE(g.price, 0) + COALESCE(a.total, 0) END AS total_cost " +
    "FROM games g LEFT JOIN " +
    "(SELECT game_id, SUM(amount) AS total FROM game_costs GROUP BY game_id) a " +
    "ON a.game_id = g.id"

@DatabaseView(viewName = GameCostingView.NAME, value = GAME_COSTING_SQL)
data class GameCostingView(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "price") val price: Double?,
    @ColumnInfo(name = "accessories_total") val accessoriesTotal: Double,
    @ColumnInfo(name = "total_cost") val totalCost: Double?
) {
    companion object {
        const val NAME = "game_costing"
    }
}
