package com.boardgamenation.tracker.data.csv

import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.entity.SessionModeEntity
import com.boardgamenation.tracker.data.db.entity.SessionObjectiveEntity
import com.boardgamenation.tracker.data.db.projection.TableCountSummary
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The acceptance criterion the whole backup story rests on: a full export, then a wipe,
 * then a full import must reproduce the database — identical row counts across every
 * table, identical computed statistics, identical achievement unlocks.
 *
 * The export and import are exercised through their in-memory entry points rather than
 * through the Storage Access Framework, so this is the same code the Settings screen
 * runs with the file plumbing removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CsvRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: CsvExporter
    private lateinit var importer: CsvImporter
    private lateinit var maintenance: DataMaintenanceRepository

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        val dispatcher = UnconfinedTestDispatcher()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        maintenance = DataMaintenanceRepository(
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
        exporter = CsvExporter(
            context = context,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            clock = DatabaseTestFixture.clock,
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

    /** A collection with something in every table, so nothing is untested by omission. */
    private suspend fun populate() {
        val catan = db.gameDao().insert(
            DatabaseTestFixture.game("Catan", bggId = 13, price = 120.0)
        )
        val seafarers = db.gameDao().insert(
            DatabaseTestFixture.game(
                "Catan: Seafarers",
                bggId = 325,
                isExpansion = true,
                baseGameId = catan
            )
        )
        val wingspan = db.gameDao().insert(
            DatabaseTestFixture.game("Wingspan, Oceania", price = 90.0)
        )
        db.gameDao().insert(
            DatabaseTestFixture.game("Wanted \"badly\"", status = GameStatus.WISHLIST)
        )

        db.gameDao().replaceCosts(
            catan,
            listOf(
                GameCostEntity(gameId = catan, label = "Sleeves", amount = 35.5),
                // A comma and a quote, for the same reason the game titles carry them.
                GameCostEntity(gameId = catan, label = "Insert, \"deluxe\"", amount = 90.0)
            )
        )

        val trading = db.tagDao().upsertByName("Trading", TagKind.MECHANIC)
        val economic = db.tagDao().upsertByName("Economic", TagKind.CATEGORY)
        db.tagDao().insertLinks(
            listOf(
                GameTagCrossRef(catan, trading),
                GameTagCrossRef(catan, economic),
                GameTagCrossRef(wingspan, economic)
            )
        )

        val me = db.playerDao().insert(DatabaseTestFixture.player("Muhammad", isSelf = true))
        val ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
        val aina = db.playerDao().insert(DatabaseTestFixture.player("Aina"))

        listOf("2026-01-05", "2026-01-12", "2026-02-03").forEachIndexed { index, date ->
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId = catan,
                    playedOn = date,
                    durationMinutes = 75 + index * 10,
                    playerCount = 3
                )
            )
            db.sessionDao().insertParticipants(
                listOf(
                    DatabaseTestFixture.participant(sessionId, me, 9.0 + index, index == 0, 1, 2, seat = 1),
                    DatabaseTestFixture.participant(sessionId, ben, 7.0, index != 0, 2, 1, seat = 3),
                    // Left out of the turn order but not out of the seating: the two
                    // columns are independent, and the archive has to keep them that way.
                    DatabaseTestFixture.participant(sessionId, aina, 5.0, false, 3, seat = 2)
                )
            )
            if (index == 2) {
                db.sessionDao().insertExpansions(
                    listOf(SessionExpansionEntity(sessionId = sessionId, gameId = seafarers))
                )
            }
        }

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(wingspan, "2026-02-10", durationMinutes = 55)
        )
        db.sessionDao().insertSession(
            DatabaseTestFixture.session(
                gameId = catan,
                playedOn = "2026-02-14",
                durationMinutes = 45,
                isCooperative = true
            ).copy(mode = "5 epidemics + mutation")
        ).also { sessionId ->
            db.sessionDao().insertModes(
                listOf(
                    SessionModeEntity(sessionId, "5 epidemics", sortOrder = 0),
                    SessionModeEntity(sessionId, "mutation", sortOrder = 1)
                )
            )
        }

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(
                gameId = wingspan,
                playedOn = "2026-02-20",
                durationMinutes = 70,
                isCooperative = true
            )
        ).also { sessionId ->
            db.sessionDao().insertObjectives(
                listOf(
                    // A puzzle cracked unaided, which is the figure most worth not losing.
                    SessionObjectiveEntity(sessionId, "The anchor", sortOrder = 0),
                    SessionObjectiveEntity(
                        sessionId = sessionId,
                        // A comma and a quote, for the reason the game titles carry them.
                        objective = "The captain, \"allegedly\"",
                        hintsUsed = 2,
                        attempts = 3,
                        sortOrder = 1
                    )
                )
            )
        }

        val rubricId = db.rubricDao().insertRubric(
            RubricEntity(name = "Strategy", description = "Decisions, mostly")
        )
        val depth = db.rubricDao().insertCriterion(
            RubricCriterionEntity(rubricId = rubricId, name = "Depth", weight = 1.5, sortOrder = 0)
        )
        val replay = db.rubricDao().insertCriterion(
            RubricCriterionEntity(rubricId = rubricId, name = "Replayability", sortOrder = 1)
        )
        val ratingId = db.rubricDao().insertRating(
            GameRatingEntity(
                gameId = catan,
                rubricId = rubricId,
                ratedOn = "2026-02-15",
                computedScore = 7.4,
                notes = "Holds up"
            )
        )
        db.rubricDao().insertScores(
            listOf(
                GameRatingScoreEntity(gameRatingId = ratingId, criterionId = depth, score = 7.0),
                GameRatingScoreEntity(gameRatingId = ratingId, criterionId = replay, score = 8.0)
            )
        )

        db.achievementDao().insertDefinitions(
            listOf(
                AchievementEntity(
                    code = "first_play",
                    name = "First Blood",
                    description = "Log your first play.",
                    icon = "Casino",
                    category = "Milestones",
                    ruleJson = """{"type":"COUNT_THRESHOLD","metric":"TOTAL_PLAYS","target":1}"""
                )
            )
        )
        val achievementId = db.achievementDao().findByCode("first_play")!!.id
        db.achievementDao().insertUnlock(
            AchievementUnlockEntity(
                achievementId = achievementId,
                unlockedAt = DatabaseTestFixture.NOW,
                progressValue = 4.0
            )
        )
    }

    /** The figures the acceptance criterion calls "computed statistics". */
    private suspend fun statistics(): Map<String, Any?> = mapOf(
        "totalPlays" to db.statsDao().observeTotalPlays().first(),
        "totalMinutes" to db.statsDao().observeTotalMinutes().first(),
        "distinctGames" to db.statsDao().observeDistinctGamesPlayed().first(),
        "collectionValue" to db.statsDao().observeCollectionValue().first(),
        "hIndex" to db.statsDao().observeHIndex().first(),
        "ownedBase" to db.statsDao().observeOwnedBaseGameCount().first(),
        "ownedExpansions" to db.statsDao().observeOwnedExpansionCount().first(),
        "unratedOwned" to db.statsDao().observeUnratedOwnedCount().first(),
        "costPerPlay" to db.statsDao().observeOverallCostPerPlay().first(),
        "standings" to db.statsDao().observeStandings(null).first()
            .map { listOf(it.playerName, it.plays, it.wins) },
        "mostPlayed" to db.statsDao().observeMostPlayed(10).first()
            .map { it.label to it.value }
    )

    private suspend fun unlockCodes(): List<String> {
        val definitions = db.achievementDao().getAllDefinitions().associateBy { it.id }
        return db.achievementDao().getAllUnlocks()
            .mapNotNull { definitions[it.achievementId]?.code }
            .sorted()
    }

    @Test
    fun `export then wipe then import reproduces the database`() = runTest {
        populate()

        val before: TableCountSummary = maintenance.tableCounts()
        val statsBefore = statistics()
        val unlocksBefore = unlockCodes()
        val files = exporter.buildFiles()

        maintenance.wipeUserData()
        assertEquals(0, maintenance.tableCounts().total)

        val result = importer.import(files, ImportMode.REPLACE)
        assertTrue("import reported errors: ${result.errors}", result.errors.isEmpty())

        assertEquals(before, maintenance.tableCounts())
        assertEquals(statsBefore, statistics())
        assertEquals(unlocksBefore, unlockCodes())
    }

    @Test
    fun `replace mode restores primary keys verbatim`() = runTest {
        populate()
        val gamesBefore = db.gameDao().getAllGames().map { it.id to it.title }.sortedBy { it.first }
        val files = exporter.buildFiles()

        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val gamesAfter = db.gameDao().getAllGames().map { it.id to it.title }.sortedBy { it.first }
        assertEquals(gamesBefore, gamesAfter)
    }

    @Test
    fun `the turn order survives the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val ben = db.playerDao().getAll().first { it.name == "Ben" }
        val aina = db.playerDao().getAll().first { it.name == "Aina" }
        val rows = db.sessionDao().getAllSessionPlayers()

        assertEquals(3, rows.count { it.playerId == ben.id && it.turnOrder == 1 })
        assertTrue(
            "a player left out of the order comes back left out of it",
            rows.filter { it.playerId == aina.id }.all { it.turnOrder == null }
        )
    }

    /**
     * What a case cost is the whole record of an investigative play, and it lives nowhere
     * else: unlike the configuration, no column on the session summarises it, so losing
     * this file would hand back an evening with its only interesting figures gone.
     */
    @Test
    fun `what each objective cost survives the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val objectives = db.sessionDao().getAllSessionObjectives().sortedBy { it.sortOrder }

        assertEquals(listOf("The anchor", "The captain, \"allegedly\""), objectives.map { it.objective })
        assertEquals(listOf(0, 2), objectives.map { it.hintsUsed })
        assertEquals(listOf(1, 3), objectives.map { it.attempts })
    }

    /**
     * An archive taken before the file existed has no hint count anywhere to recover, so
     * the play restores with no objectives rather than with invented ones -- and it still
     * restores, which is what matters.
     */
    @Test
    fun `an archive with no objectives file still imports`() = runTest {
        populate()
        val files = exporter.buildFiles() - CsvSchema.SESSION_OBJECTIVES

        maintenance.wipeUserData()
        val result = importer.import(files, ImportMode.REPLACE)

        assertTrue("import reported errors: ${result.errors}", result.errors.isEmpty())
        assertEquals(0, db.sessionDao().countObjectives())
        assertNotNull(db.gameDao().getAllGames().firstOrNull { it.title == "Catan" })
    }

    @Test
    fun `the seating survives the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val me = db.playerDao().getAll().first { it.name == "Muhammad" }
        val ben = db.playerDao().getAll().first { it.name == "Ben" }
        val aina = db.playerDao().getAll().first { it.name == "Aina" }
        val rows = db.sessionDao().getAllSessionPlayers().filter { it.seat != null }

        assertEquals(9, rows.size)
        assertEquals(3, rows.count { it.playerId == me.id && it.seat == 1 })
        assertEquals(3, rows.count { it.playerId == aina.id && it.seat == 2 })
        assertEquals(3, rows.count { it.playerId == ben.id && it.seat == 3 })
        assertTrue(
            "a seat is not read off the turn order on the way back in",
            rows.filter { it.playerId == aina.id }.all { it.turnOrder == null }
        )
    }

    @Test
    fun `accessory costs survive the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val catan = db.gameDao().getGameByTitle("Catan")!!
        val costs = db.gameDao().getCosts(catan.id)

        assertEquals(listOf("Sleeves", "Insert, \"deluxe\""), costs.map { it.label })
        assertEquals(35.5, costs.first().amount, 0.001)
        // Two priced games at 120 and 90, plus the 125.50 spent on Catan afterwards:
        // the archive has to bring back the accessories as well as the boxes.
        assertEquals(335.5, db.statsDao().observeCollectionValue().first(), 0.001)
    }

    @Test
    fun `expansion links survive the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val expansion = db.gameDao().getGameByTitle("Catan: Seafarers")!!
        val base = db.gameDao().getGameByTitle("Catan")!!
        assertEquals(base.id, expansion.baseGameId)
    }

    @Test
    fun `awkward text survives quoting and parsing`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        // A comma in one title and an embedded quote in another: the two cases most
        // likely to corrupt a naive CSV.
        assertNotNull(db.gameDao().getGameByTitle("Wingspan, Oceania"))
        assertNotNull(db.gameDao().getGameByTitle("Wanted \"badly\""))
    }

    @Test
    fun `the configuration a co-op was played at survives the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()

        importer.import(files, ImportMode.REPLACE)

        val modes = db.sessionDao().getAllSessions().mapNotNull { it.mode }
        assertEquals(listOf("5 epidemics + mutation"), modes)
    }

    /** The set behind that line comes back whole, and in the order it was named. */
    @Test
    fun `every configuration on a play survives the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()

        importer.import(files, ImportMode.REPLACE)

        val sessionId = db.sessionDao().getAllSessions().single { it.mode != null }.id
        assertEquals(
            listOf("5 epidemics", "mutation"),
            db.sessionDao().getModes(sessionId).map { it.mode }
        )
    }

    /**
     * An archive exported before the set existed carries the whole answer in the
     * `sessions.csv` column and nothing else. Restoring it must leave a play that can be
     * opened and saved without silently dropping the configuration it was logged with.
     */
    @Test
    fun `an archive with no session_modes file still restores the configuration`() = runTest {
        populate()
        val files = exporter.buildFiles() - CsvSchema.SESSION_MODES
        maintenance.wipeUserData()

        importer.import(files, ImportMode.REPLACE)

        val sessionId = db.sessionDao().getAllSessions().single { it.mode != null }.id
        assertEquals(
            listOf("5 epidemics + mutation"),
            db.sessionDao().getModes(sessionId).map { it.mode }
        )
    }

    @Test
    fun `the side a player was on survives the round trip`() = runTest {
        populate()
        val sessionId = db.sessionDao().getAllSessions().first().id
        val participants = db.sessionDao().getParticipants(sessionId)
        db.sessionDao().clearParticipants(sessionId)
        db.sessionDao().insertParticipants(
            participants.mapIndexed { index, participant ->
                DatabaseTestFixture.participant(
                    sessionId = sessionId,
                    playerId = participant.playerId,
                    score = participant.score,
                    isWinner = index == 0,
                    placement = participant.placement ?: 1
                ).copy(team = if (index == 0) "Liberals" else "Fascists")
            }
        )
        val files = exporter.buildFiles()
        maintenance.wipeUserData()

        importer.import(files, ImportMode.REPLACE)

        val restored = db.sessionDao().getParticipants(sessionId)
        assertEquals(
            listOf("Liberals"),
            restored.filter { it.isWinner }.map { it.team }
        )
        assertTrue("every side comes back", restored.all { !it.team.isNullOrBlank() })
    }

    /**
     * A `.db` backup carries the flag because it carries the column; the CSV archive is a
     * schema of its own and would drop it silently, handing the user back an evening that
     * had been excluded from their statistics and now counts again.
     */
    @Test
    fun `a play flagged as invalid comes back flagged`() = runTest {
        populate()
        val flagged = db.sessionDao().getAllSessions().first()
        db.sessionDao().updateSession(flagged.copy(isInvalid = true))
        val files = exporter.buildFiles()
        maintenance.wipeUserData()

        importer.import(files, ImportMode.REPLACE)

        val restored = db.sessionDao().getAllSessions()
        assertEquals(listOf(flagged.playedOn), restored.filter { it.isInvalid }.map { it.playedOn })
    }

    /** An archive exported before the column existed had nothing flagged in it. */
    @Test
    fun `an archive with no is_invalid column imports as nothing flagged`() = runTest {
        populate()
        val expected = db.sessionDao().count()
        val files = exporter.buildFiles().toMutableMap()
        files[CsvSchema.SESSIONS] = files.getValue(CsvSchema.SESSIONS).withoutColumn("is_invalid")
        maintenance.wipeUserData()

        val result = importer.import(files, ImportMode.REPLACE)

        assertTrue(result.errors.isEmpty())
        assertEquals(expected, db.sessionDao().count())
        assertTrue(db.sessionDao().getAllSessions().none { it.isInvalid })
    }

    /**
     * Rewrites a file without one column, the way an older exporter never wrote it.
     * Re-serialised from the parsed rows rather than cut out of the text, so a value
     * carrying a comma is not quietly halved on the way through.
     */
    private fun String.withoutColumn(column: String): String {
        val table = CsvParser.parse(this)
        val kept = table.headers.filterNot { it == column }
        return Csv.BOM + (listOf(Csv.row(kept)) + table.rows.map { row -> Csv.row(kept.map(row::string)) })
            .joinToString(Csv.CRLF, postfix = Csv.CRLF)
    }

    @Test
    fun `every export file carries a byte order mark and CRLF endings`() = runTest {
        populate()
        val games = exporter.buildFiles().getValue(CsvSchema.GAMES)
        assertTrue(games.startsWith(Csv.BOM))
        assertTrue(games.contains(Csv.CRLF))
    }

    @Test
    fun `the manifest records the schema version`() = runTest {
        populate()
        val manifest = CsvParser.parse(exporter.buildFiles().getValue(CsvSchema.MANIFEST))
        val version = manifest.rows.first { it.string("key") == "schema_version" }.string("value")
        assertEquals(AppDatabase.VERSION.toString(), version)
    }

    /** Importing the same export twice must not double the collection. */
    @Test
    fun `merge mode matches on natural keys instead of duplicating`() = runTest {
        populate()
        val files = exporter.buildFiles()
        val before = maintenance.tableCounts()

        importer.import(files, ImportMode.MERGE)

        val after = maintenance.tableCounts()
        assertEquals(before.games, after.games)
        assertEquals(before.players, after.players)
        assertEquals(before.tags, after.tags)
        assertEquals(before.sessions, after.sessions)
    }

    @Test
    fun `the preview counts rows without writing anything`() = runTest {
        populate()
        val files = exporter.buildFiles()
        val before = maintenance.tableCounts()

        val preview = importer.preview(files, ImportMode.MERGE)

        assertTrue(preview.canProceed)
        assertTrue(preview.headerProblems.isEmpty())
        assertEquals(before, maintenance.tableCounts())

        val games = preview.summaries.first { it.fileName == CsvSchema.GAMES }
        assertEquals(before.games, games.totalRows)
        // Everything already exists, so a merge would update rather than insert.
        assertEquals(before.games, games.updatedRows)
    }

    @Test
    fun `a file missing a required column is rejected before the database is touched`() = runTest {
        populate()
        val before = maintenance.tableCounts()
        val broken = exporter.buildFiles().toMutableMap()
        broken[CsvSchema.GAMES] = "id,bgg_id" + Csv.CRLF + "1,13" + Csv.CRLF

        val preview = importer.preview(broken, ImportMode.REPLACE)
        assertTrue(preview.headerProblems.isNotEmpty())

        val failure = runCatching { importer.import(broken, ImportMode.REPLACE) }
        assertTrue(failure.isFailure)
        // The wipe must not have happened.
        assertEquals(before, maintenance.tableCounts())
    }

    /** One bad row is reported with its line number; the rest of the file still lands. */
    @Test
    fun `a malformed row is skipped and reported rather than aborting the import`() = runTest {
        populate()
        val files = exporter.buildFiles().toMutableMap()
        val games = files.getValue(CsvSchema.GAMES)
        files[CsvSchema.GAMES] = games.replace(",2026-01-01,120,", ",2026-01-01,not-a-price,")

        maintenance.wipeUserData()
        val result = importer.import(files, ImportMode.REPLACE)

        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.first().line > 0)
        // Three of the four games still arrived.
        assertEquals(3, db.gameDao().count())
    }
}
