package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which players a single play handed a new record to, which is what the shared card
 * puts a label on.
 *
 * The figure the profile shows and this one answer different questions off the same
 * rows: one is "what is the best they have done", the other "did this evening beat it".
 * The cases worth pinning down are the ones where the second is not simply the first --
 * a debut, a score that only equals the record, and a record held by a play logged after
 * the one being asked about.
 */
@RunWith(RobolectricTestRunner::class)
class PersonalBestSetInTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private var me = 0L
    private var ben = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun game(title: String, highScoreWins: Boolean = true): Long =
        db.gameDao().insert(DatabaseTestFixture.game(title).copy(highScoreWins = highScoreWins))

    /** One play of [gameId], scoring [mine] against Ben's [theirs]. Returns its id. */
    private suspend fun play(
        gameId: Long,
        mine: Double?,
        theirs: Double? = null,
        isDraft: Boolean = false,
        endCondition: SessionEndCondition? = SessionEndCondition.STANDARD
    ): Long {
        val sessionId = db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, playedOn = "2026-02-01", isDraft = isDraft)
                .copy(endCondition = endCondition)
        )
        db.sessionDao().insertParticipants(
            listOf(
                DatabaseTestFixture.participant(sessionId, me, score = mine),
                DatabaseTestFixture.participant(sessionId, ben, score = theirs)
            )
        )
        return sessionId
    }

    @Test
    fun `beating every earlier score sets a record`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0)
        play(wingspan, mine = 84.0)
        val best = play(wingspan, mine = 92.0)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
    }

    @Test
    fun `a score short of the record sets nothing`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 92.0)
        val ordinary = play(wingspan, mine = 84.0)

        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(ordinary))
    }

    @Test
    fun `equalling the record does not set one`() = runTest {
        // A best has to be beaten to be new. Announcing a personal best beside a number
        // somebody has already made would be overstating the evening.
        val wingspan = game("Wingspan")
        play(wingspan, mine = 92.0)
        val repeat = play(wingspan, mine = 92.0)

        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(repeat))
    }

    @Test
    fun `a first scored play is not a record`() = runTest {
        // There was nothing to beat, and the card already marks a first play as one.
        val wingspan = game("Wingspan")
        val debut = play(wingspan, mine = 92.0)

        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(debut))
    }

    @Test
    fun `the lowest score sets the record at a golf-scored game`() = runTest {
        val golf = game("Cribbage Golf", highScoreWins = false)
        play(golf, mine = 71.0)
        val best = play(golf, mine = 62.0)
        val worse = play(golf, mine = 84.0)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(worse))
    }

    @Test
    fun `everyone at the table is judged against their own history`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0, theirs = 110.0)
        val session = play(wingspan, mine = 92.0, theirs = 88.0)

        // Hafiz beat his own 71 with a score Ben would not have been proud of; Ben's 88
        // is a long way short of the 110 he already had.
        assertEquals(setOf(me), repository.personalBestsSetIn(session))
    }

    @Test
    fun `a record only another game holds does not block one`() = runTest {
        val wingspan = game("Wingspan")
        val azul = game("Azul")
        play(azul, mine = 130.0)
        play(wingspan, mine = 71.0)
        val best = play(wingspan, mine = 92.0)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
    }

    @Test
    fun `a draft neither sets a record nor stands in the way of one`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0)
        play(wingspan, mine = 120.0, isDraft = true)
        val best = play(wingspan, mine = 92.0)
        val draft = play(wingspan, mine = 140.0, isDraft = true)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(draft))
    }

    @Test
    fun `a play a rule stopped early sets no record`() = runTest {
        // Military supremacy ends 7 Wonders Duel before anybody counts victory points,
        // so the number against that play is a partial count, not a result.
        val duel = game("7 Wonders Duel")
        play(duel, mine = 62.0)
        val cut = play(duel, mine = 90.0, endCondition = SessionEndCondition.SPECIFIC)

        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(cut))
    }

    @Test
    fun `a play a rule stopped early is not a record to beat either`() = runTest {
        val duel = game("7 Wonders Duel")
        play(duel, mine = 62.0)
        play(duel, mine = 90.0, endCondition = SessionEndCondition.ABANDONED)
        val best = play(duel, mine = 70.0)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
    }

    @Test
    fun `a play recorded before end conditions existed still counts`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0, endCondition = null)
        val best = play(wingspan, mine = 92.0, endCondition = null)

        assertEquals(setOf(me), repository.personalBestsSetIn(best))
    }

    @Test
    fun `an unscored row sets nothing`() = runTest {
        val hive = game("Hive")
        play(hive, mine = null, theirs = null)
        val session = play(hive, mine = null, theirs = null)

        assertEquals(emptySet<Long>(), repository.personalBestsSetIn(session))
    }
}
