package com.boardgamenation.tracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The status is the one field on a game that two other things read as a claim about
 * money: the collection totals add up the games it says are on the shelf, and the
 * `status` column is filtered on by name. Both of those break quietly rather than
 * loudly -- a total that is too high still renders -- so what each status means is
 * asserted here rather than left to the screens that consume it.
 */
class GameStatusTest {

    @Test
    fun `a game played on somebody else's copy is not part of the collection`() {
        assertFalse(GameStatus.PLAYED_NOT_OWNED.countsTowardCollection)
    }

    @Test
    fun `only the statuses with a copy on the shelf count toward the collection`() {
        assertEquals(
            listOf(GameStatus.OWNED, GameStatus.LENT_OUT),
            GameStatus.entries.filter { it.countsTowardCollection }
        )
    }

    @Test
    fun `a stored status comes back as itself`() {
        GameStatus.entries.forEach { status ->
            assertEquals(status, GameStatus.fromStorage(status.name))
        }
    }

    /**
     * A CSV row with no status, or one written by a version of the app that knew a name
     * this one does not, has to land somewhere. Owned is the assumption the rest of the
     * app already makes about a game with no status recorded.
     */
    @Test
    fun `an unreadable status falls back to owned`() {
        assertEquals(GameStatus.OWNED, GameStatus.fromStorage(null))
        assertEquals(GameStatus.OWNED, GameStatus.fromStorage(""))
        assertEquals(GameStatus.OWNED, GameStatus.fromStorage("BORROWED"))
    }
}
