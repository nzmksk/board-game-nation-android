package com.boardgamenation.tracker.ui.gamedetail

import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines the game detail screen prints under the total.
 *
 * The order is the whole point of the breakdown: the price is the line a reader looks
 * for first, and the rest are only findable if they are somewhere predictable.
 */
class CostBreakdownTest {

    @Test
    fun `the price leads and the accessories follow by label`() {
        val state = stateWith(price = 50.0, "Sleeve" to 10.0, "Insert" to 40.0)

        assertEquals(listOf(null, "Insert", "Sleeve"), state.costBreakdown.map { it.label })
        assertEquals(listOf(50.0, 40.0, 10.0), state.costBreakdown.map { it.amount })
    }

    /** Labels are free text, so "insert" and "Insert" have to sort together. */
    @Test
    fun `accessories sort regardless of case`() {
        val state = stateWith(price = null, "sleeve" to 10.0, "Insert" to 40.0)

        assertEquals(listOf("Insert", "sleeve"), state.costBreakdown.map { it.label })
    }

    @Test
    fun `a game with no price starts at its accessories`() {
        val state = stateWith(price = null, "Insert" to 40.0)

        assertEquals(listOf("Insert"), state.costBreakdown.map { it.label })
        assertEquals(40.0, state.totalCost!!, 0.001)
    }

    /** Nothing to break down, and nothing claimed about what the game cost. */
    @Test
    fun `an unpriced game with no accessories has no lines`() {
        assertTrue(stateWith(price = null).costBreakdown.isEmpty())
    }

    private fun stateWith(price: Double?, vararg costs: Pair<String, Double>) = GameDetailUiState(
        game = GameEntity(
            id = 1,
            title = "Wingspan",
            dateAdded = "2026-01-01",
            price = price,
            createdAt = 0,
            updatedAt = 0
        ),
        costs = costs.mapIndexed { index, (label, amount) ->
            GameCostEntity(id = index + 1L, gameId = 1, label = label, amount = amount, sortOrder = index)
        }
    )
}
