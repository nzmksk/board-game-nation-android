package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.db.query.GameQueryBuilder
import com.boardgamenation.tracker.domain.model.CollectionFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a play flagged as invalid is worth: nothing, to every statistic in the app.
 *
 * The table set the game up wrongly, or played a rule wrongly, and found out afterwards.
 * That is not an abandoned play -- an abandoned play is a real play of the real game that
 * stopped early, and it still says how long the game runs. A play of the wrong game says
 * nothing about the game, so it is dropped whole, exactly as a draft is.
 *
 * Each test therefore logs the same evening twice, flags one of the two, and asserts the
 * figure reads as though only one play happened. The row itself stays in the log; that
 * half is [SessionDaoTest].
 */
@RunWith(RobolectricTestRunner::class)
class InvalidPlayTest {

    private lateinit var db: AppDatabase
    private var me = 0L
    private var ben = 0L
    private var wingspan = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
        wingspan = db.gameDao().insert(DatabaseTestFixture.game("Wingspan"))
    }

    @After
    fun tearDown() = db.close()

    /**
     * One evening: the device owner wins with [myScore], Ben loses with [theirScore].
     * The two plays every test logs differ only in [isInvalid], so any figure that comes
     * back doubled is a query that has not been told to leave the flagged one out.
     */
    private suspend fun play(
        isInvalid: Boolean,
        playedOn: String = "2026-02-01",
        durationMinutes: Int = 60,
        myScore: Double = 80.0,
        theirScore: Double = 70.0
    ): Long {
        val sessionId = db.sessionDao().insertSession(
            DatabaseTestFixture.session(
                wingspan,
                playedOn = playedOn,
                durationMinutes = durationMinutes,
                isInvalid = isInvalid
            )
        )
        db.sessionDao().insertParticipants(
            listOf(
                DatabaseTestFixture.participant(sessionId, me, score = myScore, isWinner = true, turnOrder = 1),
                DatabaseTestFixture.participant(sessionId, ben, score = theirScore, turnOrder = 2)
            )
        )
        return sessionId
    }

    @Test
    fun `the play totals count only the play that counted`() = runTest {
        play(isInvalid = false, durationMinutes = 60)
        play(isInvalid = true, durationMinutes = 200)

        val stats = db.statsDao()
        assertEquals(1, stats.observeTotalPlays().first())
        assertEquals(60, stats.observeTotalMinutes().first())
        assertEquals(listOf(1.0), stats.observeMostPlayed(limit = 10).first().map { it.value })
    }

    /** A game whose only evening was played wrongly has still never been played. */
    @Test
    fun `a game with nothing but invalid plays is unplayed`() = runTest {
        play(isInvalid = true)

        assertEquals(0, db.statsDao().observeDistinctGamesPlayed().first())
        assertEquals(listOf("Wingspan"), db.statsDao().observeUnplayedGames().first().map { it.label })
    }

    @Test
    fun `the game detail aggregates leave it out`() = runTest {
        play(isInvalid = false, playedOn = "2026-02-01", durationMinutes = 60)
        play(isInvalid = true, playedOn = "2026-03-01", durationMinutes = 200)

        val aggregates = db.gameDao().observeAggregates(wingspan).first()

        assertEquals(1, aggregates.playCount)
        assertEquals(60, aggregates.totalMinutes)
        assertEquals(60.0, aggregates.avgMinutes!!, 0.001)
        // The date of the wrong evening is not when this game was last played.
        assertEquals("2026-02-01", aggregates.lastPlayed)
        assertEquals(1, aggregates.wins)
    }

    /** The collection list counts plays and remembers a last-played date the same way. */
    @Test
    fun `the collection list counts only valid plays`() = runTest {
        play(isInvalid = false, playedOn = "2026-02-01")
        play(isInvalid = true, playedOn = "2026-03-01")

        val row = db.gameDao().observeCollection(GameQueryBuilder.build(CollectionFilter())).first().single()

        assertEquals(1, row.playCount)
        assertEquals("2026-02-01", row.lastPlayed)
    }

    @Test
    fun `standings and win rates leave it out`() = runTest {
        play(isInvalid = false)
        play(isInvalid = true)

        val standing = db.statsDao().observeStandings(gameId = null).first().single { it.playerId == me }
        assertEquals(1, standing.plays)
        assertEquals(1, standing.wins)

        val winRate = db.statsDao().observeWinRateByGame(me).first().single()
        assertEquals(1, winRate.plays)
    }

    /**
     * A score made under the wrong rules is not a record, which is the case the issue
     * behind this flag was really about: a run of misplayed points sitting at the top of
     * a profile forever.
     */
    @Test
    fun `a score from an invalid play is neither an average nor a personal best`() = runTest {
        play(isInvalid = false, myScore = 80.0)
        play(isInvalid = true, myScore = 400.0)

        assertEquals(80.0, db.statsDao().observeAverageScoreByGame(me, limit = 10).first().single().value, 0.001)
        assertEquals(80.0, db.statsDao().observePersonalBestByGame(me).first().single().bestScore, 0.001)
    }

    /** Nor does it set one: the card for that evening claims no record. */
    @Test
    fun `an invalid play sets no personal best`() = runTest {
        play(isInvalid = false, myScore = 80.0)
        val flagged = play(isInvalid = true, myScore = 400.0)

        assertEquals(emptyList<Long>(), db.statsDao().personalBestsSetIn(flagged))
    }

    /** And it cannot be beaten either, so a later honest play still sets a record. */
    @Test
    fun `a record held by an invalid play does not block a later one`() = runTest {
        play(isInvalid = false, myScore = 80.0)
        play(isInvalid = true, myScore = 400.0)
        val honest = play(isInvalid = false, myScore = 90.0)

        assertEquals(listOf(me), db.statsDao().personalBestsSetIn(honest))
    }

    @Test
    fun `the achievement figures leave it out`() = runTest {
        play(isInvalid = false, playedOn = "2026-02-01", durationMinutes = 60)
        play(isInvalid = true, playedOn = "2026-03-01", durationMinutes = 600)

        val achievements = db.achievementStatsDao()
        assertEquals(1, achievements.totalPlays())
        assertEquals(1.0, achievements.totalHours(), 0.001)
        assertEquals(60, achievements.maxSessionDurationMinutes())
        assertEquals(listOf("2026-02-01"), achievements.playDates())
        assertEquals(listOf(true), achievements.selfResultsInOrder())
    }

    /** The duration the form prefills is an average, so it obeys the same rule. */
    @Test
    fun `the prefilled duration ignores it`() = runTest {
        play(isInvalid = false, durationMinutes = 60)
        play(isInvalid = true, durationMinutes = 600)

        assertEquals(60.0, db.sessionDao().averageDurationFor(wingspan)!!, 0.001)

        db.sessionDao().deleteSession(1)
        assertNull("only invalid plays left, so there is no average", db.sessionDao().averageDurationFor(wingspan))
    }

    /** The player list's own play and win counts are statistics too. */
    @Test
    fun `the player list counts only valid plays`() = runTest {
        play(isInvalid = false)
        play(isInvalid = true)

        val row = db.playerDao().observeWithCounts().first().single { it.player.id == me }

        assertEquals(1, row.plays)
        assertEquals(1, row.wins)
    }

    /**
     * The flag is the whole difference. Two identical evenings, one flagged, must not
     * agree on a single figure -- otherwise this file is testing nothing.
     */
    @Test
    fun `an unflagged play is counted normally`() = runTest {
        play(isInvalid = false)
        play(isInvalid = false)

        assertEquals(2, db.statsDao().observeTotalPlays().first())
        assertTrue(db.statsDao().observeUnplayedGames().first().isEmpty())
    }
}
