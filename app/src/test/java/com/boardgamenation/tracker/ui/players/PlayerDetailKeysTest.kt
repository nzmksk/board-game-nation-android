package com.boardgamenation.tracker.ui.players

import com.boardgamenation.tracker.data.db.projection.PersonalBestRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The keys the profile hands its one lazy list.
 *
 * The personal bests are keyed by game and the plays below them by session, and the two
 * numbers are drawn from different tables, so sooner or later a player's best at game 17
 * sits a few rows above their play 17. The list holds one set of keys for all of it and
 * throws when a key arrives twice, which took the screen down mid-scroll rather than on
 * open, since a key only clashes once both rows are measured together.
 */
class PlayerDetailKeysTest {

    @Test
    fun `a game and a session that share an id do not share a key`() {
        assertNotEquals(personalBestKey(17), sessionKey(17))
    }

    @Test
    fun `a profile whose best and whose latest play carry the same id keys every row apart`() {
        // Hafiz's own profile, as it stood when this crashed: 7 Wonders is game 17 and
        // his newest play is session 17.
        val bests = listOf(bestAt(gameId = 17), bestAt(gameId = 45), bestAt(gameId = 8))
        val sessions = listOf(playOf(id = 17), playOf(id = 16), playOf(id = 45))

        val keys = bests.map { personalBestKey(it.gameId) } + sessions.map { sessionKey(it.id) }

        assertEquals(keys.size, keys.toSet().size)
    }

    private fun bestAt(gameId: Long) = PersonalBestRow(
        gameId = gameId,
        title = "Game $gameId",
        bestScore = 47.0,
        highScoreWins = true,
        plays = 2
    )

    private fun playOf(id: Long) = SessionListItem(
        id = id,
        gameId = 1,
        gameTitle = "Game",
        thumbnailPath = null,
        playedOn = "2026-09-12",
        durationMinutes = 49,
        playerCount = 2,
        location = null,
        isCooperative = false,
        coopWon = false,
        mode = null,
        isIncomplete = false,
        isTeachingGame = false,
        isInvalid = false,
        endReason = null,
        winningTeam = null,
        winnerNames = null,
        firstPlayerName = null,
        objectiveCount = 0,
        hintsUsed = 0
    )
}
