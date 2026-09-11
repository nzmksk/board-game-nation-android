package com.boardgamenation.tracker.data.dev

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.domain.achievement.AchievementEvaluator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The generated collection behind the Settings screen's "generate sample data".
 *
 * It is developer-facing, but it is the only way most of the app's charts are ever seen
 * with data in them, so it running to completion is worth a test: it wires nearly every
 * DAO together and a single throw anywhere in it leaves the install empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DevFixturesTest {

    private lateinit var db: AppDatabase
    private lateinit var fixtures: DevFixtures

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        val dispatcher = UnconfinedTestDispatcher()
        val context = ApplicationProvider.getApplicationContext<Context>()

        fixtures = DevFixtures(
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            rubricRepository = RubricRepository(db.rubricDao(), DatabaseTestFixture.clock),
            achievementRepository = AchievementRepository(
                context = context,
                achievementDao = db.achievementDao(),
                statsDao = db.achievementStatsDao(),
                evaluator = AchievementEvaluator(
                    achievementDao = db.achievementDao(),
                    statsDao = db.achievementStatsDao(),
                    clock = DatabaseTestFixture.clock
                ),
                io = dispatcher
            ),
            clock = DatabaseTestFixture.clock,
            io = dispatcher
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `generates games, players, plays and ratings`() = runTest {
        fixtures.generate()

        assertTrue(db.gameDao().count() > 0)
        assertTrue(db.playerDao().count() > 0)
        assertTrue(db.sessionDao().count() > 0)
        assertTrue(db.sessionDao().countParticipants() > 0)
        assertTrue(db.rubricDao().countRatings() > 0)
    }

    /**
     * Most of the catalogue has no rule that can stop the game early, and those games
     * are the bulk of the plays. Drawing a reason for one of them used to ask an empty
     * list for a random element, which threw before the first play was written.
     */
    @Test
    fun `plays of a game with no early ending carry no reason`() = runTest {
        fixtures.generate()

        val plays = db.sessionDao().observeSessions(null, null, null, null).first()
        val ordinary = plays.filter { it.gameTitle == "Catan" || it.gameTitle == "Azul" }
        assertTrue(ordinary.isNotEmpty())
        ordinary.forEach { assertNull(it.endReason) }
    }
}
