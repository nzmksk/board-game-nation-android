package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.query.GameQueryBuilder
import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.CollectionSort
import com.boardgamenation.tracker.domain.model.GameStatus
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
 * What a game costs, once the sleeves and the insert are counted.
 *
 * Every figure in the app that says what something cost reads `game_costing` rather than
 * the price column, so these are the tests that the view says the right thing and that
 * the queries reading it did not quietly drop the games it has nothing extra to add to.
 */
@RunWith(RobolectricTestRunner::class)
class CostingTest {

    private lateinit var db: AppDatabase
    private lateinit var stats: StatsRepository

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        stats = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun game(title: String, price: Double?, status: GameStatus = GameStatus.OWNED, dateAdded: String = "2026-01-01"): Long =
        db.gameDao().insert(
            DatabaseTestFixture.game(title, price = price, status = status, dateAdded = dateAdded)
        )

    private suspend fun spend(gameId: Long, vararg amounts: Pair<String, Double>) = db.gameDao().replaceCosts(
        gameId,
        amounts.map { (label, amount) -> GameCostEntity(gameId = gameId, label = label, amount = amount) }
    )

    private suspend fun play(gameId: Long, count: Int) = repeat(count) {
        db.sessionDao().insertSession(DatabaseTestFixture.session(gameId, playedOn = "2026-02-01"))
    }

    @Test
    fun `the collection is worth its games plus what went into them`() = runTest {
        val wingspan = game("Wingspan", price = 199.0)
        game("Azul", price = 120.0)
        spend(wingspan, "Sleeves" to 35.0, "Insert" to 90.0)

        assertEquals(444.0, stats.collectionValue().first(), 0.001)
    }

    /** A game nobody has spent anything extra on has to come through unchanged. */
    @Test
    fun `a collection with no accessories is worth exactly its prices`() = runTest {
        game("Wingspan", price = 199.0)
        game("Azul", price = 120.0)

        assertEquals(319.0, stats.collectionValue().first(), 0.001)
    }

    /**
     * The view joins rather than filters, so the risk is the opposite of double
     * counting: an inner join done wrong would drop every game with no cost rows.
     */
    @Test
    fun `an unpriced game is left out rather than counted as free`() = runTest {
        game("Wingspan", price = 199.0)
        game("Borrowed copy", price = null)

        assertEquals(199.0, stats.collectionValue().first(), 0.001)
    }

    /** Accessories on a game with no price of its own are still money spent. */
    @Test
    fun `a game priced only by its accessories has a cost`() = runTest {
        val gift = game("A gift", price = null)
        spend(gift, "Sleeves" to 30.0)

        assertEquals(30.0, stats.collectionValue().first(), 0.001)
    }

    @Test
    fun `cost per play divides the total rather than the price`() = runTest {
        val gloomhaven = game("Gloomhaven", price = 450.0)
        spend(gloomhaven, "Insert" to 150.0)
        play(gloomhaven, count = 10)

        val row = stats.bestValue().first().single()

        assertEquals(600.0, row.totalCost, 0.001)
        assertEquals(60.0, row.costPerPlay, 0.001)
    }

    @Test
    fun `the collection list sorts on cost per play including accessories`() = runTest {
        // Equal prices and equal plays: only the accessories can separate them.
        val cheap = game("Cheap to keep", price = 100.0)
        val dear = game("Dear to keep", price = 100.0)
        spend(dear, "Insert" to 200.0)
        play(cheap, count = 10)
        play(dear, count = 10)

        val rows = db.gameDao()
            .observeCollection(GameQueryBuilder.build(CollectionFilter(sort = CollectionSort.COST_PER_PLAY)))
            .first()

        assertEquals(listOf("Cheap to keep", "Dear to keep"), rows.map { it.title })
        assertEquals(30.0, rows.last().costPerPlay!!, 0.001)
    }

    /** A cost line has no date of its own, so it lands in the year the game did. */
    @Test
    fun `accessories are spent in the year the game was added`() = runTest {
        val wingspan = game("Wingspan", price = 199.0, dateAdded = "2025-06-01")
        spend(wingspan, "Sleeves" to 35.0)
        game("Azul", price = 120.0, dateAdded = "2026-02-01")

        val byYear = stats.spendByYear().first().associate { it.label to it.value }

        assertEquals(234.0, byYear.getValue("2025"), 0.001)
        assertEquals(120.0, byYear.getValue("2026"), 0.001)
    }

    @Test
    fun `an unplayed game is dead weight for its total, not its price`() = runTest {
        val root = game("Root", price = 220.0)
        spend(root, "Sleeves" to 40.0)

        val dead = stats.deadWeight().first().single()

        assertEquals("Root", dead.label)
        assertEquals(260.0, dead.value, 0.001)
    }

    @Test
    fun `removing a cost line takes it back out of the total`() = runTest {
        val wingspan = game("Wingspan", price = 199.0)
        spend(wingspan, "Sleeves" to 35.0, "Insert" to 90.0)
        spend(wingspan, "Sleeves" to 35.0)

        assertEquals(234.0, stats.collectionValue().first(), 0.001)
        assertEquals(listOf("Sleeves"), db.gameDao().getCosts(wingspan).map { it.label })
    }

    /** Wishlist entries have never cost anything, whatever has been typed against them. */
    @Test
    fun `a wishlist game stays outside the collection's value`() = runTest {
        val someday = game("Someday", price = 300.0, status = GameStatus.WISHLIST)
        spend(someday, "Sleeves, once I own it" to 40.0)

        assertEquals(0.0, stats.collectionValue().first(), 0.001)
    }

    @Test
    fun `cost lines come back in the order they were entered`() = runTest {
        val wingspan = game("Wingspan", price = 199.0)
        spend(wingspan, "Shipping" to 15.0, "Sleeves" to 35.0, "Insert" to 90.0)

        assertEquals(
            listOf("Shipping", "Sleeves", "Insert"),
            db.gameDao().getCosts(wingspan).map { it.label }
        )
        assertTrue(db.gameDao().getCosts(wingspan).map { it.sortOrder } == listOf(0, 1, 2))
    }

    @Test
    fun `a game with no plays has no cost per play at all`() = runTest {
        val root = game("Root", price = 220.0)
        spend(root, "Sleeves" to 40.0)

        val rows = db.gameDao()
            .observeCollection(GameQueryBuilder.build(CollectionFilter()))
            .first()

        assertNull(rows.single().costPerPlay)
    }
}
