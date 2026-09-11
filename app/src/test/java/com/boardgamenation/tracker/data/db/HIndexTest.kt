package com.boardgamenation.tracker.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The h-index on the Plays tab: the largest N where N games have been played N times.
 *
 * It used to read 0 for most real shelves. The query ranked each game by how many games
 * had reached its own play count, which gives every game tied on a count the rank of the
 * last of them -- so three games played twice each were all ranked third, all failed
 * 2 >= 3, and a shelf that plainly scored 2 reported nothing at all. A collection large
 * enough to be interesting is mostly ties, so the figure was wrong nearly always rather
 * than occasionally.
 *
 * The cases below are therefore mostly tie shapes, plus the ends: nothing played, and one
 * game played to death.
 */
@RunWith(RobolectricTestRunner::class)
class HIndexTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runTest { db = DatabaseTestFixture.database() }

    @After
    fun tearDown() = db.close()

    /**
     * One game on the shelf, played [plays] times. Play counts are the only input the
     * figure has, so the shape of a test is the list of counts it logs and nothing else.
     */
    private suspend fun game(title: String, plays: Int, isDraft: Boolean = false, isInvalid: Boolean = false) {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game(title))
        repeat(plays) {
            db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId,
                    playedOn = "2026-02-01",
                    isDraft = isDraft,
                    isInvalid = isInvalid
                )
            )
        }
    }

    private suspend fun hIndex() = db.statsDao().observeHIndex().first()

    /** The shape that reported 0: every game tied, and the tie is the answer. */
    @Test
    fun `three games played twice each is two`() = runTest {
        game("Wingspan", plays = 2)
        game("Azul", plays = 2)
        game("Cascadia", plays = 2)

        assertEquals(2, hIndex())
    }

    /**
     * A real shelf, from the report behind this: a handful of games played twice with a
     * long tail played once. The tail cannot lift the figure, but it must not sink it.
     */
    @Test
    fun `a tail of single plays does not drag the tie down`() = runTest {
        repeat(3) { game("Played twice $it", plays = 2) }
        repeat(6) { game("Played once $it", plays = 1) }

        assertEquals(2, hIndex())
    }

    @Test
    fun `a ladder of play counts is read at the rung that holds`() = runTest {
        game("Wingspan", plays = 5)
        game("Azul", plays = 4)
        game("Cascadia", plays = 3)
        game("Calico", plays = 2)
        game("Patchwork", plays = 1)

        // Three games reached three plays; only two reached four.
        assertEquals(3, hIndex())
    }

    /** Depth in one game is not breadth: a hundred plays of it still answer 1. */
    @Test
    fun `one game played to death is one`() = runTest {
        game("Wingspan", plays = 100)

        assertEquals(1, hIndex())
    }

    /** And breadth without depth is 1 as well, not 20. */
    @Test
    fun `twenty games played once each is one`() = runTest {
        repeat(20) { game("Game $it", plays = 1) }

        assertEquals(1, hIndex())
    }

    @Test
    fun `a shelf nothing has been played from is zero`() = runTest {
        game("Wingspan", plays = 0)

        assertEquals(0, hIndex())
    }

    /** The two flags every query in StatsDao drops are dropped here too. */
    @Test
    fun `drafts and invalid plays do not count towards it`() = runTest {
        game("Wingspan", plays = 2)
        game("Azul", plays = 2)
        game("Cascadia", plays = 2, isDraft = true)
        game("Calico", plays = 2, isInvalid = true)

        assertEquals(2, hIndex())
    }
}
