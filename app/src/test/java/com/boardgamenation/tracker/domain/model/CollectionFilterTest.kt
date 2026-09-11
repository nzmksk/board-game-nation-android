package com.boardgamenation.tracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status chips are an OR, and one of them is not an alternative to the other four.
 * A filter holding a contradiction still renders a list, so what the toggle may and may
 * not leave behind is asserted here rather than left to the screen that draws the chips.
 */
class CollectionFilterTest {

    private val shelfStatuses =
        GameStatus.entries.filter { it != GameStatus.PLAYED_NOT_OWNED }

    @Test
    fun `a status chip lights on the first tap and goes out on the second`() {
        val lit = CollectionFilter().withStatusToggled(GameStatus.OWNED)
        assertEquals(setOf(GameStatus.OWNED), lit.statuses)
        assertEquals(emptySet<GameStatus>(), lit.withStatusToggled(GameStatus.OWNED).statuses)
    }

    @Test
    fun `the shelf statuses still combine with each other`() {
        val filter = CollectionFilter()
            .withStatusToggled(GameStatus.OWNED)
            .withStatusToggled(GameStatus.LENT_OUT)
        assertEquals(setOf(GameStatus.OWNED, GameStatus.LENT_OUT), filter.statuses)
    }

    /** The report: Owned was tapped over it and the borrowed games stayed in the list. */
    @Test
    fun `lighting any shelf status puts played-not-owned out`() {
        shelfStatuses.forEach { status ->
            val filter = CollectionFilter(statuses = setOf(GameStatus.PLAYED_NOT_OWNED))
                .withStatusToggled(status)
            assertEquals(setOf(status), filter.statuses)
        }
    }

    @Test
    fun `lighting played-not-owned puts every shelf status out`() {
        val filter = CollectionFilter(statuses = shelfStatuses.toSet())
            .withStatusToggled(GameStatus.PLAYED_NOT_OWNED)
        assertEquals(setOf(GameStatus.PLAYED_NOT_OWNED), filter.statuses)
    }

    /**
     * Turning a chip off cannot produce the contradiction, so it is left to take away
     * only what was tapped -- clearing Owned out of a pair leaves the other half lit.
     */
    @Test
    fun `turning a chip off leaves the rest of the selection alone`() {
        val filter = CollectionFilter(statuses = setOf(GameStatus.OWNED, GameStatus.SOLD))
            .withStatusToggled(GameStatus.OWNED)
        assertEquals(setOf(GameStatus.SOLD), filter.statuses)
    }

    /** Chips are the only thing this touches; a search or a sort rides through it. */
    @Test
    fun `toggling a status carries the rest of the filter across`() {
        val filter = CollectionFilter(
            search = "Catan",
            statuses = setOf(GameStatus.PLAYED_NOT_OWNED),
            playerCount = 4,
            sort = CollectionSort.RATING,
            ascending = false
        ).withStatusToggled(GameStatus.OWNED)

        assertEquals("Catan", filter.search)
        assertEquals(4, filter.playerCount)
        assertEquals(CollectionSort.RATING, filter.sort)
        assertEquals(false, filter.ascending)
    }
}
