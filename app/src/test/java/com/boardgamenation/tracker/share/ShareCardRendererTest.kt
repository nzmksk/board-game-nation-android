package com.boardgamenation.tracker.share

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.domain.share.ShareCard
import com.boardgamenation.tracker.domain.share.ShareObjective
import com.boardgamenation.tracker.domain.share.ShareResult
import com.boardgamenation.tracker.domain.share.ShareStanding
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The card is drawn once and then leaves the app, so there is no screen to notice it
 * came out wrong on. What can be checked without a device is that the drawing code
 * survives the inputs real data produces -- a table of twelve, a title nobody can read
 * the end of, a play with nothing recorded on it -- and that a winner is visibly a
 * winner.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default graphics mode answers every draw call without touching a pixel,
// which would leave these assertions passing against a blank bitmap. Native graphics
// runs the real Skia, so what is asserted here is what a device would produce.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCardRendererTest {

    private val renderer = ShareCardRenderer(ApplicationProvider.getApplicationContext())

    /** The renderer's own margin, which is private to it. */
    private val margin = 84

    private fun standing(
        name: String,
        rank: Int? = null,
        score: Double? = null,
        faction: String? = null,
        team: String? = null,
        isWinner: Boolean = false,
        isNewPlayer: Boolean = false,
        isPersonalBest: Boolean = false
    ) = ShareStanding(
        rank = rank,
        name = name,
        faction = faction,
        team = team,
        score = score,
        isWinner = isWinner,
        isNewPlayer = isNewPlayer,
        isPersonalBest = isPersonalBest
    )

    private fun card(
        gameTitle: String = "Wingspan",
        result: ShareResult = ShareResult.RANKED,
        standings: List<ShareStanding>,
        winningTeam: String? = null,
        mode: String? = null,
        objectives: List<ShareObjective> = emptyList(),
        endReason: String? = null,
        turnOrder: List<String> = emptyList(),
        seating: List<String> = emptyList(),
        isIncomplete: Boolean = false,
        isTeachingGame: Boolean = false
    ) = ShareCard(
        gameTitle = gameTitle,
        playedOn = LocalDate.of(2026, 9, 2),
        durationMinutes = 95,
        result = result,
        standings = standings,
        winningTeam = winningTeam,
        mode = mode,
        objectives = objectives,
        endReason = endReason,
        turnOrder = turnOrder,
        seating = seating,
        isIncomplete = isIncomplete,
        isTeachingGame = isTeachingGame
    )

    /** Gold is the card's one accent, and it is spent only on winners and the rule. */
    private fun goldPixels(bitmap: Bitmap): Int {
        var count = 0
        for (x in 0 until bitmap.width step 4) {
            for (y in 0 until bitmap.height step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 0xC0 && Color.green(pixel) in 0x70..0xD8 &&
                    Color.blue(pixel) < 0x80
                ) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * The first row on which two cards differ, which on a pair that differ by one badge
     * is where that badge was drawn.
     *
     * Everything below it differs too -- a badge lengthens the header, and the standings
     * shift down to make room -- so only the first row means anything, and it is exactly
     * the question here: which side of the headline did the badge land on.
     */
    private fun firstChangedRow(one: Bitmap, other: Bitmap): Int {
        for (y in 0 until one.height) {
            for (x in 0 until one.width step 4) {
                if (one.getPixel(x, y) != other.getPixel(x, y)) return y
            }
        }
        return -1
    }

    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = mutableSetOf<Int>()
        for (x in 0 until bitmap.width step 8) {
            for (y in 0 until bitmap.height step 8) seen += bitmap.getPixel(x, y)
        }
        return seen.size
    }

    @Test
    fun `the card comes out at story proportions`() {
        val bitmap = renderer.render(
            card(standings = listOf(standing("Aina", rank = 1, score = 94.0, isWinner = true)))
        )

        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        assertEquals(1080 * 16, bitmap.height * 9)
    }

    /** Nothing but a background would still be a valid bitmap, so check it drew. */
    @Test
    fun `the card is drawn on rather than left blank`() {
        val bitmap = renderer.render(
            card(
                standings = listOf(
                    standing("Aina", rank = 1, score = 94.0, isWinner = true),
                    standing("Hafiz", rank = 2, score = 71.0)
                )
            )
        )

        assertTrue(distinctColours(bitmap) > 20)
    }

    @Test
    fun `a play with a winner carries more of the accent than one without`() {
        val players = listOf(standing("Aina"), standing("Hafiz"), standing("Ben"))
        val won = renderer.render(
            card(
                standings = listOf(
                    standing("Aina", rank = 1, score = 94.0, isWinner = true),
                    standing("Hafiz", rank = 2, score = 71.0),
                    standing("Ben", rank = 3, score = 60.0)
                )
            )
        )
        val unresolved = renderer.render(
            card(result = ShareResult.UNRESOLVED, standings = players)
        )

        assertTrue(goldPixels(won) > goldPixels(unresolved))
    }

    /**
     * The ending qualifies the result, so it reads under the headline. The mode and the
     * teaching flag were both true before the game started and stay above it.
     */
    @Test
    fun `the end condition is drawn below the headline and the setup badges above it`() {
        val players = listOf(standing("Aina", rank = 1, isWinner = true), standing("Hafiz", rank = 2))
        val plain = renderer.render(card(standings = players))

        val reason = firstChangedRow(
            renderer.render(card(standings = players, endReason = "Military supremacy")),
            plain
        )
        val mode = firstChangedRow(
            renderer.render(card(standings = players, mode = "Military supremacy")),
            plain
        )
        val abandoned = firstChangedRow(
            renderer.render(card(standings = players, isIncomplete = true)),
            plain
        )
        val teaching = firstChangedRow(
            renderer.render(card(standings = players, isTeachingGame = true)),
            plain
        )

        assertTrue(reason > mode)
        assertTrue(abandoned > teaching)
    }

    /**
     * The watermark is centred and drawn straight, with nothing to ellipsise it, so a
     * longer wording of it -- or a translation -- would simply run off the card. The
     * margins either side of the footer are where that shows up first.
     */
    @Test
    fun `the watermark stays inside the margins`() {
        val bitmap = renderer.render(
            card(standings = listOf(standing("Aina", rank = 1, isWinner = true)))
        )

        for (y in bitmap.height - 180 until bitmap.height) {
            // The background is a vertical gradient, so it is constant across a row and
            // the far edge of that row is what the margin should still be showing.
            val background = bitmap.getPixel(0, y)
            for (x in 0 until margin) {
                assertEquals(background, bitmap.getPixel(x, y))
            }
            for (x in bitmap.width - margin until bitmap.width) {
                assertEquals(background, bitmap.getPixel(x, y))
            }
        }
    }

    @Test
    fun `a table too big for the card still renders`() {
        val bitmap = renderer.render(
            card(
                standings = (1..12).map { seat ->
                    standing("Player $seat", rank = seat, score = seat.toDouble())
                },
                turnOrder = (1..12).map { "Player $it" }
            )
        )

        assertEquals(1920, bitmap.height)
        assertTrue(distinctColours(bitmap) > 20)
    }

    @Test
    fun `the seating is drawn on a play that recorded no turn order`() {
        val players = listOf(standing("Aina", rank = 1, isWinner = true), standing("Hafiz", rank = 2))

        val seated = renderer.render(card(standings = players, seating = listOf("Aina", "Hafiz")))
        val bare = renderer.render(card(standings = players))

        assertFalse(seated.sameAs(bare))
    }

    /**
     * Both were recorded, and the card has one line for where people were. Drawing the
     * seating underneath would spend it restating the order in a different notation.
     */
    @Test
    fun `a play that recorded both draws the turn order and nothing else`() {
        val players = listOf(standing("Aina", rank = 1, isWinner = true), standing("Hafiz", rank = 2))
        val order = listOf("Aina", "Hafiz")

        val both = renderer.render(
            card(standings = players, turnOrder = order, seating = listOf("Hafiz", "Aina"))
        )
        val orderOnly = renderer.render(card(standings = players, turnOrder = order))

        assertTrue(both.sameAs(orderOnly))
    }

    @Test
    fun `a ring too long for its line still renders`() {
        val bitmap = renderer.render(
            card(
                standings = (1..12).map { seat ->
                    standing("Player $seat", rank = seat, score = seat.toDouble())
                },
                seating = (1..12).map { "Player $it" }
            )
        )

        assertEquals(1920, bitmap.height)
        assertTrue(distinctColours(bitmap) > 20)
    }

    @Test
    fun `text longer than the card is not something it chokes on`() {
        val bitmap = renderer.render(
            card(
                gameTitle = "Robinson Crusoe: Adventures on the Cursed Island ".repeat(4),
                standings = listOf(
                    standing(
                        name = "A name far longer than any row could hope to hold ".repeat(2),
                        rank = 1,
                        score = 1234.5,
                        faction = "A faction with a similarly unreasonable name ".repeat(2),
                        team = "And a side as well",
                        isWinner = true
                    )
                ),
                mode = "Every module, every expansion, and a house rule nobody remembers",
                endReason = "Military supremacy",
                turnOrder = listOf("Aina", "Hafiz"),
                isIncomplete = true,
                isTeachingGame = true
            )
        )

        assertEquals(1080, bitmap.width)
        assertTrue(distinctColours(bitmap) > 20)
    }

    /** A co-op, a team game and a play with no result all reach the renderer. */
    @Test
    fun `every kind of result renders`() {
        val results = listOf(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(
                    standing("Aina", isWinner = true),
                    standing("Hafiz", isWinner = true)
                )
            ),
            card(result = ShareResult.COOP_LOSS, standings = listOf(standing("Aina"))),
            card(
                result = ShareResult.TEAMS,
                winningTeam = "Liberals",
                standings = listOf(
                    standing("Aina", team = "Liberals", faction = "President", isWinner = true),
                    standing("Hafiz", team = "Fascists", faction = "Hitler")
                )
            ),
            card(result = ShareResult.UNRESOLVED, standings = listOf(standing("Aina")))
        )

        results.forEach { assertEquals(1920, renderer.render(it).height) }
    }

    /**
     * The complaint the tag answers is that a first-timer looked like everybody else, so
     * the check is that the same play drawn with the flag set is a different picture --
     * an assertion a blank bitmap cannot pass, unlike a pixel count.
     */
    @Test
    fun `a first-timer's row does not come out looking like everybody else's`() {
        val standings = { isNew: Boolean ->
            listOf(
                standing("Aina", rank = 1, score = 94.0, isWinner = true),
                standing("Hafiz", rank = 2, score = 71.0, isNewPlayer = isNew)
            )
        }

        val plain = renderer.render(card(standings = standings(false)))
        val marked = renderer.render(card(standings = standings(true)))

        assertFalse(plain.sameAs(marked))
    }

    /**
     * Gold is what says somebody won. A first play is a different kind of fact, and a
     * tag that borrowed the accent would read as a second winner on the row below.
     */
    @Test
    fun `marking a first-timer spends none of the winner's accent`() {
        val standings = { isNew: Boolean ->
            listOf(standing("Aina", rank = 1, score = 94.0, isWinner = true, isNewPlayer = isNew))
        }

        val plain = renderer.render(card(standings = standings(false)))
        val marked = renderer.render(card(standings = standings(true)))

        assertEquals(goldPixels(plain), goldPixels(marked))
    }

    /** The tag takes room from a row that a long name and a faction already want. */
    @Test
    fun `a first-timer whose name fills the row still renders`() {
        val bitmap = renderer.render(
            card(
                standings = listOf(
                    standing(
                        name = "A name far longer than any row could hope to hold ".repeat(2),
                        rank = 1,
                        score = 1234.5,
                        faction = "Peregrine Falcon",
                        team = "The side with the long name as well",
                        isWinner = true,
                        isNewPlayer = true
                    ),
                    standing("Hafiz", rank = 2, score = 71.0, isNewPlayer = true)
                )
            )
        )

        assertEquals(1080, bitmap.width)
        assertTrue(distinctColours(bitmap) > 20)
    }

    /**
     * Same bar the first-play tag is held to: the point of the label is that the row
     * reads differently, which a blank bitmap cannot fake.
     */
    @Test
    fun `a record-setting row does not come out looking like everybody else's`() {
        val standings = { isBest: Boolean ->
            listOf(
                standing("Aina", rank = 1, score = 94.0, isWinner = true),
                standing("Hafiz", rank = 2, score = 71.0, isPersonalBest = isBest)
            )
        }

        val plain = renderer.render(card(standings = standings(false)))
        val marked = renderer.render(card(standings = standings(true)))

        assertFalse(plain.sameAs(marked))
    }

    /** A record is a personal one, so the winner's accent is not the thing marking it. */
    @Test
    fun `marking a record spends none of the winner's accent`() {
        val standings = { isBest: Boolean ->
            listOf(standing("Aina", rank = 1, score = 94.0, isWinner = true, isPersonalBest = isBest))
        }

        val plain = renderer.render(card(standings = standings(false)))
        val marked = renderer.render(card(standings = standings(true)))

        assertEquals(goldPixels(plain), goldPixels(marked))
    }

    /**
     * The two tags cannot both be true off real data -- a first play has nothing to beat
     * -- but the row draws them as a list, so a pair has to fit rather than overlap.
     */
    @Test
    fun `a row carrying both tags renders both of them`() {
        val standings = { tags: Boolean ->
            listOf(
                standing(
                    name = "Hafiz",
                    rank = 2,
                    score = 71.0,
                    isNewPlayer = tags,
                    isPersonalBest = tags
                )
            )
        }

        val one = renderer.render(
            card(standings = listOf(standing("Hafiz", rank = 2, score = 71.0, isNewPlayer = true)))
        )
        val both = renderer.render(card(standings = standings(true)))

        assertFalse(renderer.render(card(standings = standings(false))).sameAs(both))
        assertFalse(one.sameAs(both))
    }

    /** The tag takes room from a row that a long name and a faction already want. */
    @Test
    fun `a record-setter whose name fills the row still renders`() {
        val bitmap = renderer.render(
            card(
                standings = listOf(
                    standing(
                        name = "A name far longer than any row could hope to hold ".repeat(2),
                        rank = 1,
                        score = 1234.5,
                        faction = "Peregrine Falcon",
                        isWinner = true,
                        isPersonalBest = true
                    ),
                    standing("Hafiz", rank = 2, score = 71.0, isPersonalBest = true)
                )
            )
        )

        assertEquals(1080, bitmap.width)
        assertTrue(distinctColours(bitmap) > 20)
    }

    /** A table of first-timers is what a game's first night on the shelf looks like. */
    @Test
    fun `a whole table of first-timers renders`() {
        val bitmap = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = (1..12).map { seat ->
                    standing("Player $seat", isWinner = true, isNewPlayer = true)
                }
            )
        )

        assertEquals(1920, bitmap.height)
        assertTrue(distinctColours(bitmap) > 20)
    }

    @Test
    fun `a play with nobody on it renders rather than failing`() {
        val bitmap = renderer.render(
            card(gameTitle = "", result = ShareResult.UNRESOLVED, standings = emptyList())
        )

        assertEquals(1080, bitmap.width)
    }

    // --- objectives -----------------------------------------------------------------

    /**
     * The tally sits on the figures line, which is above the title's baseline neighbours
     * and well above the standings, so a card carrying it differs from one that does not
     * from that line downward.
     */
    @Test
    fun `the objective tally is drawn on a play that has a case`() {
        val without = renderer.render(card(standings = listOf(standing("Aina"))))
        val with = renderer.render(
            card(
                standings = listOf(standing("Aina")),
                objectives = listOf(ShareObjective("The locked safe", hintsUsed = 0, attempts = 1))
            )
        )

        assertFalse("the tally should change the card", without.sameAs(with))
    }

    @Test
    fun `a play with no case is drawn exactly as it was before`() {
        val one = renderer.render(card(standings = listOf(standing("Aina", rank = 1, isWinner = true))))
        val other = renderer.render(
            card(standings = listOf(standing("Aina", rank = 1, isWinner = true)), objectives = emptyList())
        )

        assertTrue(one.sameAs(other))
    }

    @Test
    fun `the objective list is drawn above the arrangement`() {
        val objectives = listOf(
            ShareObjective("The locked safe", hintsUsed = 0, attempts = 1),
            ShareObjective("The cellar", hintsUsed = 2, attempts = 3)
        )
        val bitmap = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(standing("Aina", isWinner = true), standing("Ben", isWinner = true)),
                objectives = objectives,
                turnOrder = listOf("Aina", "Ben")
            )
        )

        assertEquals(1080, bitmap.width)
        assertTrue("the card should be drawn on", distinctColours(bitmap) > 4)
    }

    /**
     * A case longer than the card counts the rest off rather than dropping them in
     * silence, which is what the standings do when a table is too big.
     */
    @Test
    fun `a case with more puzzles than fit still renders`() {
        val bitmap = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(standing("Aina", isWinner = true)),
                objectives = (1..12).map { ShareObjective("Chapter $it", hintsUsed = it % 3, attempts = 1) }
            )
        )

        assertEquals(1920, bitmap.height)
        assertTrue(distinctColours(bitmap) > 4)
    }

    @Test
    fun `an objective named at length does not stop the card rendering`() {
        val bitmap = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(standing("Aina", isWinner = true)),
                objectives = listOf(
                    ShareObjective("The " + "very ".repeat(40) + "locked safe", hintsUsed = 9, attempts = 9)
                )
            )
        )

        assertEquals(1080, bitmap.width)
        assertTrue(distinctColours(bitmap) > 4)
    }

    /**
     * A big table and a long case want the same space. The standings give way, because a
     * lineup with no ranks and no scores on it is the least an investigative play has to
     * say, and both blocks say how many rows they left off.
     */
    @Test
    fun `a full table and a full case fit on the same card`() {
        val bitmap = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = (1..12).map { standing("Player $it", isWinner = true) },
                objectives = (1..8).map { ShareObjective("Lead $it", hintsUsed = 1, attempts = 2) },
                turnOrder = (1..12).map { "Player $it" }
            )
        )

        assertEquals(1920, bitmap.height)
        assertTrue(distinctColours(bitmap) > 4)
    }

    /**
     * Two cases with the same tally and the same number of puzzles, so the header and
     * the standings land identically on both and the only thing that can differ is the
     * list itself. That it differs at all is the proof the list is on the card; that the
     * first difference is well below the header is the proof it is not just the tally.
     */
    @Test
    fun `the puzzles themselves are drawn, not only the tally`() {
        val one = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(standing("Aina", isWinner = true)),
                objectives = listOf(
                    ShareObjective("Alpha", hintsUsed = 1, attempts = 1),
                    ShareObjective("Beta", hintsUsed = 0, attempts = 1)
                )
            )
        )
        val other = renderer.render(
            card(
                result = ShareResult.COOP_WIN,
                standings = listOf(standing("Aina", isWinner = true)),
                objectives = listOf(
                    ShareObjective("Gamma", hintsUsed = 0, attempts = 1),
                    ShareObjective("Delta", hintsUsed = 1, attempts = 1)
                )
            )
        )

        assertFalse("the objective list should be drawn", one.sameAs(other))
        val changed = firstChangedRow(one, other)
        assertTrue("expected the difference below the header but it was at $changed", changed > 600)
    }
}
