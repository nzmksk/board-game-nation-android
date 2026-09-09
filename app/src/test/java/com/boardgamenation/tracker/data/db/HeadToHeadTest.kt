package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the head-to-head list is ordered, and how the nemesis is picked out of it.
 *
 * The record is the sort key, not the sample size: the opponents beaten most often come
 * first, and among equal win counts the one who has won back the least. How many plays
 * the two have shared does not enter into it.
 *
 * Also that a play the two both won is a draw and only a draw. Ties for first are a real
 * outcome in this app, and the counting has to keep them out of both the win and the loss
 * column rather than putting them in both.
 */
@RunWith(RobolectricTestRunner::class)
class HeadToHeadTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private var gameId = 0L
    private var me = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun opponent(name: String) = db.playerDao().insert(DatabaseTestFixture.player(name))

    /**
     * Plays out a record between the owner and one opponent. A [draws] play is one both
     * of them won, which is what a tie for first looks like in the data. [unfinished]
     * plays are shared but have no winner, which is how a pair can share more plays than
     * their record accounts for.
     */
    private suspend fun record(
        opponentId: Long,
        wins: Int,
        losses: Int,
        draws: Int = 0,
        unfinished: Int = 0
    ) {
        repeat(wins + losses + draws + unfinished) { index ->
            val incomplete = index >= wins + losses + draws
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId,
                    playedOn = "2026-02-01",
                    isIncomplete = incomplete
                )
            )
            val selfWon = index < wins || index >= wins + losses
            val opponentWon = index >= wins
            db.sessionDao().insertParticipants(
                listOf(
                    DatabaseTestFixture.participant(
                        sessionId,
                        me,
                        isWinner = !incomplete && selfWon
                    ),
                    DatabaseTestFixture.participant(
                        sessionId,
                        opponentId,
                        isWinner = !incomplete && opponentWon
                    )
                )
            )
        }
    }

    private suspend fun names() = db.statsDao().observeHeadToHead().first().map { it.opponentName }

    @Test
    fun `records rank by wins first, then by fewest losses`() = runTest {
        // Inserted in an order that is not the answer, so passing means the SQL sorted it.
        record(opponent("Alia"), wins = 5, losses = 6)
        record(opponent("Ben"), wins = 10, losses = 13)
        record(opponent("Cara"), wins = 10, losses = 0)
        record(opponent("Danish"), wins = 5, losses = 3)
        record(opponent("Elena"), wins = 10, losses = 5)

        val records = db.statsDao().observeHeadToHead().first()
            .map { "${it.selfWins}-${it.opponentWins}" }
        assertEquals(listOf("10-0", "10-5", "10-13", "5-3", "5-6"), records)
    }

    @Test
    fun `a thin winning record still outranks a thick losing one`() = runTest {
        record(opponent("Regular"), wins = 2, losses = 20)
        record(opponent("Rare"), wins = 3, losses = 0)

        assertEquals(listOf("Rare", "Regular"), names())
    }

    @Test
    fun `identical records fall back to name`() = runTest {
        record(opponent("Zaki"), wins = 4, losses = 2)
        record(opponent("Amir"), wins = 4, losses = 2)

        assertEquals(listOf("Amir", "Zaki"), names())
    }

    @Test
    fun `the nemesis is whoever wins the highest share of the shared plays`() = runTest {
        record(opponent("Mild"), wins = 6, losses = 4)
        record(opponent("Fierce"), wins = 1, losses = 9)

        assertEquals("Fierce", repository.nemesis().first()?.opponentName)
    }

    @Test
    fun `an equal rate is broken by the longer rivalry, not by the list order`() = runTest {
        // Both beat the user half the time. Constant has done it over twelve plays rather
        // than six, but wins fewer, so the ranking puts them second -- taking the first
        // qualifying row would answer Occasional.
        record(opponent("Occasional"), wins = 3, losses = 3)
        record(opponent("Constant"), wins = 1, losses = 6, unfinished = 5)

        assertEquals(listOf("Occasional", "Constant"), names())
        assertEquals("Constant", repository.nemesis().first()?.opponentName)
    }

    @Test
    fun `a play both of them won is a draw and nothing else`() = runTest {
        val rival = opponent("Nadia")
        record(rival, wins = 3, losses = 1, draws = 3)

        val row = db.statsDao().observeHeadToHead().first().single()
        assertEquals(3, row.selfWins)
        assertEquals(1, row.opponentWins)
        assertEquals(3, row.draws)
        assertEquals(7, row.sharedPlays)
    }

    @Test
    fun `drawing repeatedly does not make someone the nemesis`() = runTest {
        // Stalemate ties nearly every play and outright beats the user once; Beater wins
        // half of a shorter record. Counting a draw as a loss would answer Stalemate.
        record(opponent("Stalemate"), wins = 1, losses = 1, draws = 8)
        record(opponent("Beater"), wins = 2, losses = 2)

        assertEquals("Beater", repository.nemesis().first()?.opponentName)
    }

    @Test
    fun `a handful of plays is not yet a rivalry`() = runTest {
        record(opponent("Stranger"), wins = 0, losses = 2)

        assertNull(repository.nemesis().first())
    }
}
