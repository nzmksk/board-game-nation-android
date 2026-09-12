package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.GameExpansionCrossRef
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.projection.FactionRecord
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.data.db.projection.GameListItem
import com.boardgamenation.tracker.domain.model.GameStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    /**
     * The collection list. The shape of the filter varies far too much for a static
     * query, so [com.boardgamenation.tracker.data.db.query.GameQueryBuilder] assembles
     * the SQL and Room still re-runs it whenever any observed table changes.
     */
    @RawQuery(
        observedEntities = [
            GameEntity::class,
            GameCostEntity::class,
            SessionEntity::class,
            GameRatingEntity::class,
            GameTagCrossRef::class
        ]
    )
    fun observeCollection(query: SupportSQLiteQuery): Flow<List<GameListItem>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observeGame(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getGame(id: Long): GameEntity?

    @Query("SELECT * FROM games WHERE bgg_id = :bggId LIMIT 1")
    suspend fun getGameByBggId(bggId: Long): GameEntity?

    @Query("SELECT * FROM games WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun getGameByTitle(title: String): GameEntity?

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    suspend fun getAllGames(): List<GameEntity>

    /**
     * Base games only, for pickers where an expansion makes no sense as the subject.
     *
     * Every base game, whatever its status. A play is logged against a game, so a picker
     * that asked for a copy on the shelf was a picker that could not record the night
     * spent on somebody else's -- the very thing [GameStatus.PLAYED_NOT_OWNED] exists to
     * say. It cut the other way too: selling a game took its own past plays out of reach,
     * because the session form finds the game it is editing in this list and found
     * nothing, so an old play reopened claiming no game was chosen.
     *
     * A wishlist entry stays on offer as well. Playing a game before buying it is how
     * most of them get onto the list, and the play is what the form is there to record.
     */
    @Query(
        """
        SELECT * FROM games
        WHERE is_expansion = 0
        ORDER BY title COLLATE NOCASE
        """
    )
    fun observeBaseGames(): Flow<List<GameEntity>>

    /**
     * The expansions that name this game, whether it is a base game or an expansion
     * itself. Direct links only: the chain from Catan to Seafarers to Legends of the Sea
     * Robbers is read one step at a time, and an expansion that plays on top of two
     * games in that chain says so by linking to both.
     */
    @Query(
        """
        SELECT g.* FROM games g
        JOIN game_expansions ge ON ge.expansion_id = g.id
        WHERE ge.base_game_id = :baseGameId
        ORDER BY g.title COLLATE NOCASE
        """
    )
    fun observeExpansionsOf(baseGameId: Long): Flow<List<GameEntity>>

    @Query(
        """
        SELECT g.* FROM games g
        JOIN game_expansions ge ON ge.expansion_id = g.id
        WHERE ge.base_game_id = :baseGameId
        ORDER BY g.title COLLATE NOCASE
        """
    )
    suspend fun getExpansionsOf(baseGameId: Long): List<GameEntity>

    /** The other direction: what this expansion expands. */
    @Query(
        """
        SELECT g.* FROM games g
        JOIN game_expansions ge ON ge.base_game_id = g.id
        WHERE ge.expansion_id = :expansionId
        ORDER BY g.title COLLATE NOCASE
        """
    )
    fun observeBaseGamesOf(expansionId: Long): Flow<List<GameEntity>>

    @Query(
        """
        SELECT g.* FROM games g
        JOIN game_expansions ge ON ge.base_game_id = g.id
        WHERE ge.expansion_id = :expansionId
        ORDER BY g.title COLLATE NOCASE
        """
    )
    suspend fun getBaseGamesOf(expansionId: Long): List<GameEntity>

    /**
     * Everything the game detail screen needs in one pass. Incomplete sessions are
     * counted as plays but kept out of the duration averages, because an abandoned game
     * says nothing useful about how long the game takes.
     *
     * A play flagged as invalid is not counted at all, here or in any other statistic:
     * a game set up or played wrongly says nothing useful about anything.
     */
    @Query(
        """
        SELECT
            COUNT(*) AS play_count,
            COALESCE(SUM(s.duration_minutes), 0) AS total_minutes,
            AVG(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS avg_minutes,
            AVG(CASE WHEN s.is_incomplete = 0 AND s.is_teaching_game = 0
                     THEN s.duration_minutes END) AS avg_minutes_non_teaching,
            MIN(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS shortest_minutes,
            MAX(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS longest_minutes,
            MIN(s.played_on) AS first_played,
            MAX(s.played_on) AS last_played,
            COALESCE(SUM(CASE WHEN s.is_cooperative = 1 THEN (s.coop_outcome = 'WIN')
                              ELSE EXISTS (
                                  SELECT 1 FROM session_players sp
                                  JOIN players p ON p.id = sp.player_id
                                  WHERE sp.session_id = s.id AND sp.is_winner = 1 AND p.is_self = 1
                              ) END), 0) AS wins,
            COALESCE(SUM(EXISTS (
                SELECT 1 FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND p.is_self = 1
            )), 0) AS self_plays
        FROM sessions s
        WHERE s.game_id = :gameId AND s.is_draft = 0 AND s.is_invalid = 0
        """
    )
    fun observeAggregates(gameId: Long): Flow<GameAggregates>

    /**
     * Win rate per faction for one game, over every player who has played it.
     *
     * Abandoned plays are excluded, the same way they are excluded from the duration
     * averages: a game nobody finished has no winner, and counting it would drag every
     * faction down as though each had lost.
     *
     * Co-operative plays are counted. A game where the table shares one result still
     * asks a real balance question -- which spirit, which character, which role tends
     * to be at the table when the table wins.
     *
     * Grouped case-insensitively so "Alexandria" and "alexandria" are one faction; the
     * name shown is whichever spelling SQLite picks out of the group, which is stable
     * for a given set of rows.
     */
    @Query(
        """
        SELECT
            sp.faction AS faction,
            COUNT(*) AS plays,
            COALESCE(SUM(sp.is_winner), 0) AS wins
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id
        WHERE s.game_id = :gameId AND s.is_draft = 0 AND s.is_invalid = 0
          AND s.is_incomplete = 0
          AND sp.faction IS NOT NULL AND trim(sp.faction) <> ''
        GROUP BY sp.faction COLLATE NOCASE
        ORDER BY (wins * 1.0 / plays) DESC, plays DESC, faction COLLATE NOCASE
        """
    )
    fun observeFactionRecords(gameId: Long): Flow<List<FactionRecord>>

    @Query(
        """
        SELECT * FROM games
        WHERE status = 'LENT_OUT' AND lent_date IS NOT NULL
        ORDER BY lent_date ASC
        """
    )
    fun observeLentOut(): Flow<List<GameEntity>>

    /**
     * Loans older than [cutoffIsoDate]. The comparison is a plain string compare, which
     * is exactly right for ISO-8601 dates and needs no date functions.
     */
    @Query(
        """
        SELECT * FROM games
        WHERE status = 'LENT_OUT' AND lent_date IS NOT NULL AND lent_date <= :cutoffIsoDate
        ORDER BY lent_date ASC
        """
    )
    suspend fun getLoansOlderThan(cutoffIsoDate: String): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT')")
    fun observeOwnedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: GameEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(games: List<GameEntity>): List<Long>

    @Update
    suspend fun update(game: GameEntity)

    @Delete
    suspend fun delete(game: GameEntity)

    @Query("DELETE FROM games WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE games SET status = :status, updated_at = :now WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: GameStatus, now: Long)

    @Query(
        """
        UPDATE games
        SET lent_to = :person, lent_date = :isoDate,
            status = 'LENT_OUT', updated_at = :now
        WHERE id = :gameId
        """
    )
    suspend fun markLent(gameId: Long, person: String, isoDate: String, now: Long)

    @Query(
        """
        UPDATE games
        SET lent_to = NULL, lent_date = NULL,
            status = 'OWNED', updated_at = :now
        WHERE id = :gameId
        """
    )
    suspend fun markReturned(gameId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM sessions WHERE game_id = :gameId AND is_draft = 0")
    suspend fun sessionCountFor(gameId: Long): Int

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Query("DELETE FROM games")
    suspend fun deleteAll()

    /**
     * Replaces the tag set for a game wholesale. Doing it in one transaction keeps the
     * game from ever being observed mid-swap with half its tags missing.
     */
    @Transaction
    suspend fun replaceTags(gameId: Long, tagIds: List<Long>) {
        clearTags(gameId)
        if (tagIds.isNotEmpty()) {
            insertTagLinks(tagIds.map { GameTagCrossRef(gameId = gameId, tagId = it) })
        }
    }

    @Query("DELETE FROM game_tags WHERE game_id = :gameId")
    suspend fun clearTags(gameId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<GameTagCrossRef>)

    // --- expansion links --------------------------------------------------------------

    /**
     * Replaces the set of games one expansion expands, in one transaction and for the
     * same reason [replaceTags] does: the form edits the whole set at once, and nothing
     * should ever observe the expansion mid-swap attached to nothing.
     */
    @Transaction
    suspend fun replaceBaseGames(expansionId: Long, baseGameIds: List<Long>) {
        clearBaseGames(expansionId)
        val links = baseGameIds.distinct()
            // An expansion of itself is not a thing, and the link would show the game in
            // both halves of its own details.
            .filter { it != expansionId }
            .map { GameExpansionCrossRef(expansionId = expansionId, baseGameId = it) }
        if (links.isNotEmpty()) insertExpansionLinks(links)
    }

    @Query("DELETE FROM game_expansions WHERE expansion_id = :expansionId")
    suspend fun clearBaseGames(expansionId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpansionLinks(links: List<GameExpansionCrossRef>)

    @Query("SELECT * FROM game_expansions")
    suspend fun getAllExpansionLinks(): List<GameExpansionCrossRef>

    @Query("SELECT COUNT(*) FROM game_expansions")
    suspend fun countExpansionLinks(): Int

    // --- accessory costs --------------------------------------------------------------

    @Query("SELECT * FROM game_costs WHERE game_id = :gameId ORDER BY sort_order, id")
    fun observeCosts(gameId: Long): Flow<List<GameCostEntity>>

    @Query("SELECT * FROM game_costs WHERE game_id = :gameId ORDER BY sort_order, id")
    suspend fun getCosts(gameId: Long): List<GameCostEntity>

    @Query("SELECT * FROM game_costs")
    suspend fun getAllCosts(): List<GameCostEntity>

    @Query("SELECT COUNT(*) FROM game_costs")
    suspend fun countCosts(): Int

    /**
     * Replaces a game's cost lines wholesale, the same way [replaceTags] does. The form
     * edits the whole list at once, so diffing row by row would only be a way to get the
     * ordering wrong.
     */
    @Transaction
    suspend fun replaceCosts(gameId: Long, costs: List<GameCostEntity>) {
        clearCosts(gameId)
        if (costs.isNotEmpty()) {
            insertCosts(
                costs.mapIndexed { index, cost ->
                    cost.copy(id = 0, gameId = gameId, sortOrder = index)
                }
            )
        }
    }

    @Query("DELETE FROM game_costs WHERE game_id = :gameId")
    suspend fun clearCosts(gameId: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCosts(costs: List<GameCostEntity>)
}
