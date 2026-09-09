package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the player profile lists under "personal best".
 *
 * The whole point of the figure is that best is not a synonym for biggest: a game scored
 * like golf has its record at the bottom of the range, and reporting the largest number
 * there would hand somebody their worst round as an achievement. Everything else in here
 * is about which plays are allowed to set a record.
 */
@RunWith(RobolectricTestRunner::class)
class PersonalBestTest {

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

    /** One play of [gameId], scoring [mine] against Ben's [theirs]. */
    private suspend fun play(
        gameId: Long,
        mine: Double?,
        theirs: Double? = null,
        isDraft: Boolean = false,
        endCondition: SessionEndCondition? = SessionEndCondition.STANDARD
    ) {
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
    }

    private suspend fun rows() = repository.personalBestByGame(me).first()

    @Test
    fun `the best at a high-score game is the highest score`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0)
        play(wingspan, mine = 92.0)
        play(wingspan, mine = 84.0)

        val row = rows().single()
        assertEquals("Wingspan", row.title)
        assertEquals(92.0, row.bestScore, 0.001)
        assertEquals(3, row.plays)
        assertTrue(row.highScoreWins)
    }

    @Test
    fun `the best at a golf-scored game is the lowest score`() = runTest {
        val golf = game("Cribbage Golf", highScoreWins = false)
        play(golf, mine = 71.0)
        play(golf, mine = 62.0)
        play(golf, mine = 84.0)

        val row = rows().single()
        assertEquals(62.0, row.bestScore, 0.001)
        assertEquals(false, row.highScoreWins)
    }

    @Test
    fun `a negative score can be the best one`() = runTest {
        // Golf scoring goes below zero in plenty of games, and MIN has to follow it
        // rather than stopping at the smallest positive number on the sheet.
        val golf = game("Skull King", highScoreWins = false)
        play(golf, mine = 10.0)
        play(golf, mine = -30.0)

        assertEquals(-30.0, rows().single().bestScore, 0.001)
    }

    @Test
    fun `the best is the player's own, not the table's`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 71.0, theirs = 110.0)

        assertEquals(71.0, rows().single().bestScore, 0.001)
        assertEquals(110.0, repository.personalBestByGame(ben).first().single().bestScore, 0.001)
    }

    @Test
    fun `a game nobody scored has no best`() = runTest {
        val hive = game("Hive")
        play(hive, mine = null, theirs = null)

        assertEquals(emptyList<String>(), rows().map { it.title })
    }

    @Test
    fun `a draft is not a play yet`() = runTest {
        val wingspan = game("Wingspan")
        play(wingspan, mine = 92.0, isDraft = true)

        assertEquals(emptyList<String>(), rows().map { it.title })
    }

    @Test
    fun `a play a rule stopped early sets no record`() = runTest {
        // Military supremacy ends 7 Wonders Duel before anybody counts victory points,
        // so the number against that play is a partial count, not a result.
        val duel = game("7 Wonders Duel")
        play(duel, mine = 62.0)
        play(duel, mine = 90.0, endCondition = SessionEndCondition.SPECIFIC)

        assertEquals(62.0, rows().single().bestScore, 0.001)
    }

    @Test
    fun `an abandoned play sets no record`() = runTest {
        val duel = game("7 Wonders Duel")
        play(duel, mine = 62.0)
        play(duel, mine = 90.0, endCondition = SessionEndCondition.ABANDONED)

        assertEquals(62.0, rows().single().bestScore, 0.001)
    }

    @Test
    fun `a play recorded before end conditions existed still counts`() = runTest {
        // The column is null only on rows written before every play was asked how it
        // ended. Those are ordinary finished plays and their scores are real.
        val wingspan = game("Wingspan")
        play(wingspan, mine = 92.0, endCondition = null)

        assertEquals(92.0, rows().single().bestScore, 0.001)
    }

    @Test
    fun `the most played game leads the list`() = runTest {
        val wingspan = game("Wingspan")
        val azul = game("Azul")
        play(wingspan, mine = 92.0)
        repeat(3) { play(azul, mine = 60.0 + it) }

        assertEquals(listOf("Azul", "Wingspan"), rows().map { it.title })
    }

    @Test
    fun `an equal play count falls back to the title`() = runTest {
        play(game("Zoo Ball"), mine = 12.0)
        play(game("Ark Nova"), mine = 130.0)

        assertEquals(listOf("Ark Nova", "Zoo Ball"), rows().map { it.title })
    }
}
