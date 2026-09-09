package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import java.time.LocalDate
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
 * The configurations a session was played at.
 *
 * The field was introduced for co-ops, where a win is meaningless without the difficulty
 * it was won at, but the question it answers is not a co-op question: Catan played with
 * Seafarers is a different game from Catan played without it, and a score of 12 means
 * something different on each. So the configuration is kept whatever the scoring mode is.
 *
 * It is a set rather than one answer because a game is usually set up several ways at
 * once. The `sessions.mode` column still holds the one line every list and share card
 * shows, written from the set on every save.
 */
@RunWith(RobolectricTestRunner::class)
class SessionModeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var catan = 0L
    private var pandemic = 0L
    private var me = 0L
    private var opponent = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = DatabaseTestFixture.clock
        )
        catan = db.gameDao().insert(
            DatabaseTestFixture.game("Catan").copy(scoringMode = ScoringMode.RANKED_SCORES)
        )
        pandemic = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE)
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        opponent = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(gameId: Long, vararg modes: String, scoringMode: ScoringMode = ScoringMode.RANKED_SCORES) = SessionForm(
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 90,
        scoringMode = scoringMode,
        modes = modes.toList(),
        participants = listOf(me to 12.0, opponent to 9.0).map { (playerId, score) ->
            ParticipantForm(playerId = playerId, playerName = "p$playerId", score = score)
        }
    )

    @Test
    fun `a competitive play keeps the configuration it was played at`() = runTest {
        val id = repository.save(form(catan, "Seafarers"))

        assertEquals("Seafarers", db.sessionDao().getSession(id)!!.mode)
    }

    @Test
    fun `a co-op play keeps its configuration just as it always did`() = runTest {
        val id = repository.save(
            form(pandemic, "5 epidemics + mutation", scoringMode = ScoringMode.COOPERATIVE)
        )

        assertEquals("5 epidemics + mutation", db.sessionDao().getSession(id)!!.mode)
    }

    /**
     * The point of the whole thing: Heat is raced as a Championship season, with Legends
     * driving and the Weather module on, and those are three answers rather than one
     * long one.
     */
    @Test
    fun `a play can be set up several ways at once`() = runTest {
        val id = repository.save(form(catan, "Championship", "Legends", "Weather"))

        assertEquals(
            listOf("Championship", "Legends", "Weather"),
            db.sessionDao().getModes(id).map { it.mode }
        )
        // And still reads as one line wherever there is only room for one.
        assertEquals("Championship + Legends + Weather", db.sessionDao().getSession(id)!!.mode)
    }

    /** Naming the same configuration twice names it once, however it was capitalised. */
    @Test
    fun `a configuration named twice is stored once`() = runTest {
        val id = repository.save(form(catan, "Seafarers", "seafarers"))

        assertEquals(listOf("Seafarers"), db.sessionDao().getModes(id).map { it.mode })
    }

    /** Editing a play reads the set back rather than the line it was shown on. */
    @Test
    fun `the set survives a round trip through the form`() = runTest {
        val id = repository.save(form(catan, "Championship", "Weather"))

        val reloaded = repository.loadForm(id)!!

        assertEquals(listOf("Championship", "Weather"), reloaded.modes)
    }

    /**
     * Removing one leaves the rest. The save replaces the set outright, so a
     * configuration taken off the form has to be gone from the table as well as from the
     * line -- otherwise it would come straight back on the next load.
     */
    @Test
    fun `dropping one configuration leaves the others alone`() = runTest {
        val id = repository.save(form(catan, "Championship", "Legends", "Weather"))
        val edited = repository.loadForm(id)!!.let { it.copy(modes = it.modes - "Legends") }

        repository.save(edited)

        assertEquals(listOf("Championship", "Weather"), db.sessionDao().getModes(id).map { it.mode })
        assertEquals("Championship + Weather", db.sessionDao().getSession(id)!!.mode)
    }

    /** Nobody typed anything, which must read as absent rather than as an empty chip. */
    @Test
    fun `a blank configuration is stored as nothing at all`() = runTest {
        val id = repository.save(form(catan, "   "))

        assertNull(db.sessionDao().getSession(id)!!.mode)
        assertEquals(0, db.sessionDao().countModes())
    }

    /**
     * Switching a play to competitive scoring used to drop the mode on the way to the
     * database. Editing a session is the same save path, so that silently erased a
     * configuration the user had entered on a game the form still offers it for.
     */
    @Test
    fun `switching to competitive scoring no longer erases the configuration`() = runTest {
        val id = repository.save(
            form(catan, "Cities & Knights", scoringMode = ScoringMode.COOPERATIVE)
        )
        val edited = repository.loadForm(id)!!.copy(scoringMode = ScoringMode.RANKED_SCORES)

        repository.save(edited)

        assertEquals("Cities & Knights", db.sessionDao().getSession(id)!!.mode)
    }

    @Test
    fun `configurations come back as chips for the next play of that game`() = runTest {
        repository.save(form(catan, "Seafarers"))
        repository.save(form(pandemic, "Level 4", scoringMode = ScoringMode.COOPERATIVE))

        assertEquals(listOf("Seafarers"), repository.observeModesFor(catan).first())
        assertEquals(listOf("Level 4"), repository.observeModesFor(pandemic).first())
    }

    /**
     * One chip per module, not one per evening. A combination offered back whole only
     * ever fits a night set up exactly like the one it came from.
     */
    @Test
    fun `each configuration is offered back on its own`() = runTest {
        repository.save(form(catan, "Championship", "Legends", "Weather"))

        assertEquals(
            listOf("Championship", "Legends", "Weather"),
            repository.observeModesFor(catan).first().sorted()
        )
    }
}
