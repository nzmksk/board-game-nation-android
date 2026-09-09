package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the player profile charts under "average score".
 *
 * The query has always meant to drop the plays a rule stopped early, and for a while it
 * said so by asking for a null `end_condition` -- which was true of an ordinary play back
 * when an early ending was the only ending the column recorded. Once every play was asked
 * how it ended, an ordinary one started saying `STANDARD` and the test excluded the whole
 * table. This is here to keep that from being true again.
 */
@RunWith(RobolectricTestRunner::class)
class AverageScoreByGameTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private var me = 0L
    private var gameId = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Wingspan"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun play(score: Double, endCondition: SessionEndCondition? = SessionEndCondition.STANDARD) {
        val sessionId = db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, playedOn = "2026-02-01").copy(endCondition = endCondition)
        )
        db.sessionDao().insertParticipants(listOf(DatabaseTestFixture.participant(sessionId, me, score = score)))
    }

    private suspend fun rows() = repository.averageScoreByGame(me).first()

    @Test
    fun `a play that ran to the game's own ending is averaged`() = runTest {
        play(70.0)
        play(90.0)

        val row = rows().single()
        assertEquals("Wingspan", row.label)
        assertEquals(80.0, row.value, 0.001)
    }

    @Test
    fun `a play a rule stopped early is not`() = runTest {
        play(70.0)
        play(10.0, endCondition = SessionEndCondition.SPECIFIC)

        assertEquals(70.0, rows().single().value, 0.001)
    }

    @Test
    fun `a play recorded before end conditions existed still counts`() = runTest {
        play(70.0, endCondition = null)

        assertEquals(70.0, rows().single().value, 0.001)
    }
}
