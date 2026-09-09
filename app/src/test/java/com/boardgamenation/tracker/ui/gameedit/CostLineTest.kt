package com.boardgamenation.tracker.ui.gameedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the form keeps when the save button is pressed.
 *
 * The add button puts an empty row on screen, so the state on the way to the database
 * always has to survive rows nobody filled in.
 */
class CostLineTest {

    @Test
    fun `a filled line becomes a cost`() {
        val state = stateWith(CostLine("Sleeves", "35.50"))

        val costs = state.costEntities()

        assertEquals(1, costs.size)
        assertEquals("Sleeves", costs[0].label)
        assertEquals(35.5, costs[0].amount, 0.001)
    }

    @Test
    fun `an untouched line left by the add button is dropped`() {
        assertTrue(stateWith(CostLine()).costEntities().isEmpty())
    }

    /** A number against no label reads back as an unexplained charge, so it is not kept. */
    @Test
    fun `an amount with no label is dropped`() {
        assertTrue(stateWith(CostLine(label = "  ", amount = "12")).costEntities().isEmpty())
    }

    /** A named accessory whose price is not to hand is still worth listing. */
    @Test
    fun `a label with no amount is kept at zero`() {
        val costs = stateWith(CostLine(label = "Insert", amount = "")).costEntities()

        assertEquals(1, costs.size)
        assertEquals(0.0, costs[0].amount, 0.001)
    }

    @Test
    fun `labels are trimmed and the order on the form is the order stored`() {
        val costs = stateWith(
            CostLine(" Sleeves ", "10"),
            CostLine(),
            CostLine("Insert", "120")
        ).costEntities()

        assertEquals(listOf("Sleeves", "Insert"), costs.map { it.label })
    }

    private fun stateWith(vararg lines: CostLine) = GameEditState(title = "Wingspan", otherCosts = lines.toList())
}
