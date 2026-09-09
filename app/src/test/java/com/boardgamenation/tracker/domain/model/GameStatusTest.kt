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
     * An archive exported while "Preordered" was a status still says it. A preorder is a
     * game somebody wants and does not have, so it reads back as a wishlist entry rather
     * than falling through to the owned default and quietly joining the collection.
     */
    @Test
    fun `a preordered status from an old archive reads back as a wishlist game`() {
        assertEquals(GameStatus.WISHLIST, GameStatus.fromStorage("PREORDERED"))
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
