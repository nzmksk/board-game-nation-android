package com.boardgamenation.tracker.ui.gamedetail

import com.boardgamenation.tracker.data.db.entity.GameEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the two expansion sections the game detail screen draws.
 *
 * Both are headings over a list that is often empty, and an empty section that can never
 * fill up is furniture. The rules are read off the state rather than decided inside the
 * composable so that they can be checked here.
 */
class ExpansionSectionsTest {

    /** Whether anything expands a base game is a fact worth stating either way. */
    @Test
    fun `a base game keeps the expansions section with nothing in it`() {
        val state = stateOf(isExpansion = false)

        assertTrue(state.showsExpansions)
        assertFalse(state.showsBaseGames)
    }

    @Test
    fun `a base game with expansions shows both of the lists it has`() {
        val state = stateOf(isExpansion = false, expansions = listOf(game(2, "Seafarers")))

        assertTrue(state.showsExpansions)
        assertFalse(state.showsBaseGames)
    }

    /**
     * The section would say "no expansions recorded" under every expansion in the
     * collection, directly below the one that just said what this expansion goes with.
     */
    @Test
    fun `an expansion with nothing on top of it drops the expansions section`() {
        val state = stateOf(isExpansion = true, baseGames = listOf(game(1, "Catan")))

        assertFalse(state.showsExpansions)
        assertTrue(state.showsBaseGames)
    }

    /** Catan: Seafarers has Legends of the Sea Robbers on it, so it has both sections. */
    @Test
    fun `an expansion that is itself expanded keeps the section`() {
        val state = stateOf(
            isExpansion = true,
            baseGames = listOf(game(1, "Catan")),
            expansions = listOf(game(3, "Legends of the Sea Robbers"))
        )

        assertTrue(state.showsExpansions)
        assertTrue(state.showsBaseGames)
    }

    /** An expansion nobody has linked yet is asked what it expands, so the fix is visible. */
    @Test
    fun `an unlinked expansion is still asked what it expands`() {
        assertTrue(stateOf(isExpansion = true).showsBaseGames)
    }

    /**
     * A game not flagged as an expansion but linked to one anyway -- which a BGG import can
     * produce -- says so rather than hiding the link the collection holds.
     */
    @Test
    fun `a game linked to a base game says so whatever its flag`() {
        val state = stateOf(isExpansion = false, baseGames = listOf(game(1, "Catan")))

        assertTrue(state.showsBaseGames)
    }

    /** Before the game has loaded there is nothing to ask about either relationship. */
    @Test
    fun `a state with no game yet asks for neither section`() {
        assertFalse(GameDetailUiState().showsBaseGames)
    }

    private fun stateOf(isExpansion: Boolean, baseGames: List<GameEntity> = emptyList(), expansions: List<GameEntity> = emptyList()) =
        GameDetailUiState(
            game = game(2, "Catan: Seafarers", isExpansion = isExpansion),
            baseGames = baseGames,
            expansions = expansions
        )

    private fun game(id: Long, title: String, isExpansion: Boolean = false) = GameEntity(
        id = id,
        title = title,
        dateAdded = "2026-01-01",
        isExpansion = isExpansion,
        createdAt = 0,
        updatedAt = 0
    )
}
