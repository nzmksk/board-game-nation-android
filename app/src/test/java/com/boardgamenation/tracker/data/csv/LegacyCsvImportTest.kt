package com.boardgamenation.tracker.data.csv

import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Importing a CSV archive written by an older version of the app.
 *
 * This is the other half of the backup story, next to `MigrationTest`. A CSV export taken
 * before this change still carries `designers`, `publisher`, `best_player_count` and
 * `base_game_id` columns on games and says nothing at all about how a play ended, and
 * somebody restoring one of those archives should not silently lose a field from every
 * game in their collection -- nor have the import refuse a file over a column that has
 * since gone.
 *
 * The files here are written out by hand rather than produced by the exporter, because
 * the whole point is a shape the current exporter can no longer produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LegacyCsvImportTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: CsvImporter

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        val dispatcher = UnconfinedTestDispatcher()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val maintenance = DataMaintenanceRepository(
            database = db,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            timerDao = db.timerDao(),
            bggCacheDao = db.bggCacheDao(),
            io = dispatcher
        )
        importer = CsvImporter(
            context = context,
            database = db,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            maintenance = maintenance,
            io = dispatcher
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * A version 1 archive: `designers`, `publisher`, `best_player_count` and
     * `base_game_id` present on games, no `sudden_death_possible`, and sessions without
     * `end_condition` or `end_reason`. Tag ids are deliberately high, so a tag created
     * during the import would collide if it were created before these rows were restored.
     */
    private fun legacyFiles(): Map<String, String> = mapOf(
        CsvSchema.GAMES to """
            id,title,designers,publisher,best_player_count,date_added,status,scoring_mode,is_expansion,base_game_id,created_at,updated_at
            1,7 Wonders Duel,"Antoine Bauza, Bruno Cathala",Repos Production,2,2026-01-01,OWNED,RANKED_SCORES,0,,0,0
            2,Cyclades,Bruno Cathala,"Matagot, SAS",3-4,2026-01-02,OWNED,RANKED_SCORES,0,,0,0
            3,Prototype,,,,2026-01-03,OWNED,RANKED_SCORES,0,,0,0
            4,7 Wonders Duel: Pantheon,Antoine Bauza,Repos Production,,2026-01-04,OWNED,RANKED_SCORES,1,1,0,0
        """.trimIndent(),
        CsvSchema.TAGS to """
            id,name,kind
            40,Card Drafting,MECHANIC
            41,Ancient,CATEGORY
        """.trimIndent(),
        CsvSchema.GAME_TAGS to """
            game_id,tag_id
            1,40
            1,41
        """.trimIndent(),
        CsvSchema.PLAYERS to """
            id,name,is_self
            1,Hafiz,1
        """.trimIndent(),
        CsvSchema.SESSIONS to """
            id,game_id,played_on,duration_minutes,player_count,created_at,updated_at
            1,1,2026-01-10,30,2,0,0
        """.trimIndent(),
        CsvSchema.SESSION_PLAYERS to """
            id,session_id,player_id,score,placement,is_winner
            1,1,1,42,1,1
        """.trimIndent()
    )

    @Test
    fun `a legacy archive imports without complaint`() = runTest {
        val result = importer.import(legacyFiles(), ImportMode.REPLACE)

        assertTrue("unexpected errors: ${result.errors}", result.errors.isEmpty())
        assertEquals(4, db.gameDao().getAllGames().size)
        assertEquals(1, db.sessionDao().count())
    }

    /**
     * Expansion links used to be a `base_game_id` column, which held one answer where the
     * question has a set of them. The value becomes the one-element set it always meant,
     * rather than the expansion coming back attached to nothing.
     */
    @Test
    fun `the old base game column is rescued into an expansion link`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertEquals(
            listOf("7 Wonders Duel"),
            db.gameDao().getBaseGamesOf(4).map { it.title }
        )
        assertEquals(
            listOf("7 Wonders Duel: Pantheon"),
            db.gameDao().getExpansionsOf(1).map { it.title }
        )
        // A base game with an empty cell gets no link invented for it.
        assertEquals(1, db.gameDao().countExpansionLinks())
    }

    @Test
    fun `the old designers column is rescued into DESIGNER tags`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        val designers = db.tagDao().getAll().filter { it.kind == TagKind.DESIGNER }
        assertEquals(
            listOf("Antoine Bauza", "Bruno Cathala"),
            designers.map { it.name }.sorted()
        )

        assertEquals(
            listOf("Antoine Bauza", "Bruno Cathala"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind == TagKind.DESIGNER }
                .map { it.name }
                .sorted()
        )
        // A game with an empty designers cell gets nothing rather than a blank tag.
        assertTrue(db.tagDao().observeForGame(3).first().isEmpty())
    }

    /**
     * Replace mode restores tag ids from the file verbatim. Creating the rescued tags
     * before that happened would hand out ids 1 and 2, which is fine here but would
     * collide the moment an archive used low tag ids -- so both rescues run after
     * `game_tags`.
     */
    @Test
    fun `rescued names do not disturb the tag ids the archive restored`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertEquals("Card Drafting", db.tagDao().getAll().single { it.id == 40L }.name)
        assertEquals("Ancient", db.tagDao().getAll().single { it.id == 41L }.name)

        val rescuedIds = db.tagDao().getAll()
            .filter { it.kind == TagKind.DESIGNER || it.kind == TagKind.PUBLISHER }
            .map { it.id }
        assertTrue(
            "rescued ids $rescuedIds should not overwrite restored ids",
            rescuedIds.none { it == 40L || it == 41L }
        )
        // The mechanic and category links from the archive are still intact.
        assertEquals(
            listOf("Ancient", "Card Drafting"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind.shownAsTag }
                .map { it.name }
                .sorted()
        )
    }

    @Test
    fun `the old publisher column is rescued into a PUBLISHER tag`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertEquals(
            listOf("Repos Production"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind == TagKind.PUBLISHER }
                .map { it.name }
        )
        // A game with an empty publisher cell gets nothing rather than a blank tag.
        assertTrue(db.tagDao().observeForGame(3).first().isEmpty())
    }

    /**
     * The column was single-valued, so a comma in it belongs to the name. The designers
     * column beside it really was a joined list, and is still split.
     */
    @Test
    fun `a comma in a legacy publisher is not a separator`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertEquals(
            listOf("Matagot, SAS"),
            db.tagDao().observeForGame(2).first()
                .filter { it.kind == TagKind.PUBLISHER }
                .map { it.name }
        )
    }

    /**
     * The other two old columns are rescued into tags. This one is not: the field it fed
     * is gone, and there is nowhere for the value to land. What matters is that the
     * archive still imports -- the column is simply not read, the way any column the
     * schema does not name is not read.
     */
    @Test
    fun `the old best player count column is ignored rather than refused`() = runTest {
        val result = importer.import(legacyFiles(), ImportMode.REPLACE)

        assertTrue("unexpected errors: ${result.errors}", result.errors.isEmpty())
        assertEquals("7 Wonders Duel", db.gameDao().getGame(1)!!.title)
        // The columns either side of it in the file still landed on the right fields.
        assertEquals("2026-01-02", db.gameDao().getGame(2)!!.dateAdded)
        assertEquals(
            listOf("Repos Production"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind == TagKind.PUBLISHER }
                .map { it.name }
        )
    }

    @Test
    fun `columns the old archive never had fall back to their defaults`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        val session = db.sessionDao().getSession(1)!!
        assertEquals(
            "an archive with neither column describes a play that ran to the end",
            SessionEndCondition.STANDARD,
            session.endCondition
        )
        assertNull(session.endReason)
        assertNull("no mode column in the archive", session.mode)
        assertTrue(
            "no team column in the archive",
            db.sessionDao().getParticipants(1).all { it.team == null }
        )
    }
}
