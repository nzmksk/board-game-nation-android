package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import com.boardgamenation.tracker.domain.model.SessionObjective
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scoring an investigative or escape-room play.
 *
 * The game is all but always won, so the result is not the record: what the evening
 * actually was is which objectives the table worked through and what each one cost in
 * hints and goes. The table still shares one outcome, exactly as in a co-op, which is why
 * these plays go on writing `is_cooperative` -- the column means the table had one
 * result, not that the box says co-operative on it.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectiveScoringTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var unlock = 0L
    private var pandemic = 0L
    private var me = 0L
    private var partner = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = DatabaseTestFixture.clock
        )
        unlock = db.gameDao().insert(
            DatabaseTestFixture.game("Unlock!").copy(scoringMode = ScoringMode.OBJECTIVE_BASED)
        )
        pandemic = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE)
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        partner = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        gameId: Long = unlock,
        scoringMode: ScoringMode = ScoringMode.OBJECTIVE_BASED,
        outcome: CoopOutcome? = CoopOutcome.WIN,
        vararg objectives: SessionObjective
    ) = SessionForm(
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 60,
        scoringMode = scoringMode,
        coopOutcome = outcome,
        objectives = objectives.toList(),
        participants = listOf(me, partner).map {
            ParticipantForm(playerId = it, playerName = "p$it")
        }
    )

    @Test
    fun `an objective is stored with what it cost`() = runTest {
        val id = repository.save(
            form(objectives = arrayOf(SessionObjective("The safe", hintsUsed = 2, attempts = 3)))
        )

        val stored = db.sessionDao().getObjectives(id).single()

        assertEquals("The safe", stored.objective)
        assertEquals(2, stored.hintsUsed)
        assertEquals(3, stored.attempts)
    }

    /** A case is a handful of puzzles, and they keep the order they were worked through. */
    @Test
    fun `the objectives keep the order they were entered in`() = runTest {
        val id = repository.save(
            form(
                objectives = arrayOf(
                    SessionObjective("The locked door"),
                    SessionObjective("The ship's log", hintsUsed = 1),
                    SessionObjective("The safe", hintsUsed = 3, attempts = 2)
                )
            )
        )

        assertEquals(
            listOf("The locked door", "The ship's log", "The safe"),
            db.sessionDao().getObjectives(id).map { it.objective }
        )
    }

    @Test
    fun `what each objective cost survives a round trip through the form`() = runTest {
        val id = repository.save(
            form(
                objectives = arrayOf(
                    SessionObjective("Lead 41", hintsUsed = 1, attempts = 2),
                    SessionObjective("Lead 62")
                )
            )
        )

        val reloaded = repository.loadForm(id)!!

        assertEquals(ScoringMode.OBJECTIVE_BASED, reloaded.scoringMode)
        assertEquals(
            listOf(
                SessionObjective("Lead 41", hintsUsed = 1, attempts = 2),
                SessionObjective("Lead 62", hintsUsed = 0, attempts = 1)
            ),
            reloaded.objectives
        )
        assertEquals(1, reloaded.totalHints)
    }

    /** Naming the same puzzle twice names it once, however it was capitalised. */
    @Test
    fun `an objective named twice is stored once`() = runTest {
        val id = repository.save(
            form(
                objectives = arrayOf(
                    SessionObjective("The safe", hintsUsed = 1),
                    SessionObjective("the safe", hintsUsed = 4)
                )
            )
        )

        val stored = db.sessionDao().getObjectives(id)

        assertEquals(1, stored.size)
        // The first answer stands: it is the one already on the play when the second was
        // typed, so naming it again adds nothing rather than overwriting what it cost.
        assertEquals(1, stored.single().hintsUsed)
    }

    /** Nobody typed anything, which is not an objective with a blank name. */
    @Test
    fun `a blank objective is stored as nothing at all`() = runTest {
        val id = repository.save(form(objectives = arrayOf(SessionObjective("   ", hintsUsed = 2))))

        assertEquals(0, db.sessionDao().getObjectives(id).size)
    }

    /** The counts are held to what they can mean: no negative hints, and never no go. */
    @Test
    fun `the counts cannot say something impossible`() = runTest {
        val id = repository.save(
            form(objectives = arrayOf(SessionObjective("The safe", hintsUsed = -2, attempts = 0)))
        )

        val stored = db.sessionDao().getObjectives(id).single()

        assertEquals(0, stored.hintsUsed)
        assertEquals(1, stored.attempts)
    }

    /**
     * The table shares one result, so the play is stored the way a co-op is and everybody
     * at the table wins or loses together.
     */
    @Test
    fun `the whole table shares the outcome`() = runTest {
        val id = repository.save(
            form(outcome = CoopOutcome.WIN, objectives = arrayOf(SessionObjective("The safe")))
        )

        val session = db.sessionDao().getSession(id)!!

        assertTrue(session.isCooperative)
        assertEquals(CoopOutcome.WIN, session.coopOutcome)
        assertTrue(db.sessionDao().getParticipants(id).all { it.isWinner })
    }

    /**
     * A case nobody broke into objectives still opens on the mode it was played in. The
     * play stores no mode of its own, and the shared outcome alone cannot tell the two
     * table-outcome modes apart, so the game's own answer decides which of them it was.
     */
    @Test
    fun `a play with no objectives still reads back as an investigative one`() = runTest {
        val id = repository.save(form(objectives = arrayOf()))

        assertEquals(ScoringMode.OBJECTIVE_BASED, repository.loadForm(id)!!.scoringMode)
    }

    /** And a game that is a plain co-op still reads back as one. */
    @Test
    fun `a co-op is unaffected by the new mode`() = runTest {
        val id = repository.save(
            form(gameId = pandemic, scoringMode = ScoringMode.COOPERATIVE, outcome = CoopOutcome.LOSS)
        )

        val reloaded = repository.loadForm(id)!!

        assertEquals(ScoringMode.COOPERATIVE, reloaded.scoringMode)
        assertEquals(0, db.sessionDao().countObjectives())
    }

    /**
     * An objective must not outlive the mode that asked for it. The mode a play reads
     * back under is worked out from its rows, so a leftover objective would pin the play
     * to investigative scoring with no way out: the save would take the new mode and the
     * next load would hand the old one straight back.
     */
    @Test
    fun `switching away from objectives leaves none behind`() = runTest {
        val id = repository.save(
            form(objectives = arrayOf(SessionObjective("The safe", hintsUsed = 2)))
        )
        val edited = repository.loadForm(id)!!.copy(scoringMode = ScoringMode.COOPERATIVE)

        repository.save(edited)

        assertEquals(0, db.sessionDao().countObjectives())
        assertEquals(ScoringMode.COOPERATIVE, repository.loadForm(id)!!.scoringMode)
    }

    /**
     * A play moved to competitive scoring loses the shared outcome as well, so nothing is
     * left behind claiming the table won together.
     */
    @Test
    fun `switching to competitive scoring drops the shared outcome too`() = runTest {
        val id = repository.save(form(objectives = arrayOf(SessionObjective("The safe"))))
        val edited = repository.loadForm(id)!!.copy(scoringMode = ScoringMode.RANKED_SCORES)

        repository.save(edited)

        assertFalse(db.sessionDao().getSession(id)!!.isCooperative)
        assertEquals(0, db.sessionDao().countObjectives())
    }

    /** Removing one leaves the rest, because the save replaces the list outright. */
    @Test
    fun `dropping one objective leaves the others alone`() = runTest {
        val id = repository.save(
            form(
                objectives = arrayOf(
                    SessionObjective("Lead 41"),
                    SessionObjective("Lead 55", hintsUsed = 2),
                    SessionObjective("Lead 62")
                )
            )
        )
        val edited = repository.loadForm(id)!!.let { form ->
            form.copy(objectives = form.objectives.filterNot { it.objective == "Lead 55" })
        }

        repository.save(edited)

        assertEquals(
            listOf("Lead 41", "Lead 62"),
            db.sessionDao().getObjectives(id).map { it.objective }
        )
    }
}
