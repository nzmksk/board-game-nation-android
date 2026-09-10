package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.boardgamenation.tracker.data.db.projection.CostPerPlayRow
import com.boardgamenation.tracker.data.db.projection.DurationVsExpectedRow
import com.boardgamenation.tracker.data.db.projection.FirstPlayerRecord
import com.boardgamenation.tracker.data.db.projection.GameWinRateRow
import com.boardgamenation.tracker.data.db.projection.HeadToHeadRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.data.db.projection.PersonalBestRow
import com.boardgamenation.tracker.data.db.projection.PlayerStandingRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import kotlinx.coroutines.flow.Flow

/**
 * Every statistic is an aggregate query. Nothing here loads a table into memory to
 * count it, which is what keeps the stats screen usable at 5,000 sessions.
 *
 * Deliberately free of window functions: minSdk 26 ships SQLite 3.19, which predates
 * them. The two genuinely sequential metrics (streaks) return a compact distinct-period
 * list that the repository walks, rather than a full table scan in Kotlin.
 *
 * Two flags are filtered out of every query here without exception, and they are the
 * only ones that are. `is_draft` is not a play yet; `is_invalid` is a play of a game
 * the table got wrong, which is a play of no game at all. Everything else -- abandoned,
 * taught, stopped early by a rule -- really happened and is excluded only from the
 * particular figures it would distort.
 */
@Dao
interface StatsDao {

    // --- collection ---------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT') AND is_expansion = 0")
    fun observeOwnedBaseGameCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT') AND is_expansion = 1")
    fun observeOwnedExpansionCount(): Flow<Int>

    /**
     * Wishlist and sold copies are deliberately outside the collection's value.
     *
     * `game_costing` rather than the price column, here and in every query below it:
     * the sleeves and the insert are money that went into the collection and would
     * otherwise be missing from every figure that claims to say what it cost.
     */
    @Query(
        """
        SELECT COALESCE(SUM(c.total_cost), 0) FROM games g
        JOIN game_costing c ON c.game_id = g.id
        WHERE g.status IN ('OWNED', 'LENT_OUT') AND c.total_cost IS NOT NULL
        """
    )
    fun observeCollectionValue(): Flow<Double>

    @Query(
        """
        SELECT t.name AS label, COUNT(DISTINCT gt.game_id) * 1.0 AS value
        FROM tags t
        JOIN game_tags gt ON gt.tag_id = t.id
        JOIN games g ON g.id = gt.game_id
        WHERE t.kind = :kind AND g.status IN ('OWNED', 'LENT_OUT')
        GROUP BY t.id
        ORDER BY value DESC, t.name COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun observeTagDistribution(kind: String, limit: Int): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT
            CASE
                WHEN weight < 1.5 THEN '1.0-1.5'
                WHEN weight < 2.0 THEN '1.5-2.0'
                WHEN weight < 2.5 THEN '2.0-2.5'
                WHEN weight < 3.0 THEN '2.5-3.0'
                WHEN weight < 3.5 THEN '3.0-3.5'
                WHEN weight < 4.0 THEN '3.5-4.0'
                ELSE '4.0+'
            END AS label,
            COUNT(*) * 1.0 AS value
        FROM games
        WHERE weight IS NOT NULL AND status IN ('OWNED', 'LENT_OUT')
        GROUP BY label
        ORDER BY label
        """
    )
    fun observeWeightDistribution(): Flow<List<LabelledValue>>

    /**
     * How many owned games support each head count. The literal series stands in for a
     * numbers table, which SQLite has no built-in equivalent of.
     */
    @Query(
        """
        SELECT n.c AS label, (
            SELECT COUNT(*) FROM games g
            WHERE g.status IN ('OWNED', 'LENT_OUT') AND g.is_expansion = 0
              AND g.min_players IS NOT NULL AND g.max_players IS NOT NULL
              AND g.min_players <= n.c AND g.max_players >= n.c
        ) * 1.0 AS value
        FROM (
            SELECT 1 AS c UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
        ) n
        ORDER BY n.c
        """
    )
    fun observePlayerCountCoverage(): Flow<List<LabelledValue>>

    /** The shelf of shame: owned and never played. */
    @Query(
        """
        SELECT g.title AS label, julianday('now') - julianday(g.date_added) AS value
        FROM games g
        WHERE g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0 AND s.is_invalid = 0)
        ORDER BY g.date_added ASC
        """
    )
    fun observeUnplayedGames(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT COUNT(*) FROM games g
        WHERE g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM game_ratings r WHERE r.game_id = g.id)
        """
    )
    fun observeUnratedOwnedCount(): Flow<Int>

    // --- plays --------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM sessions WHERE is_draft = 0 AND is_invalid = 0")
    fun observeTotalPlays(): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM sessions WHERE is_draft = 0 AND is_invalid = 0")
    fun observeTotalMinutes(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT game_id) FROM sessions WHERE is_draft = 0 AND is_invalid = 0")
    fun observeDistinctGamesPlayed(): Flow<Int>

    @Query(
        """
        SELECT strftime('%Y-%m', played_on) AS label, COUNT(*) * 1.0 AS value
        FROM sessions WHERE is_draft = 0 AND is_invalid = 0
        GROUP BY label ORDER BY label
        """
    )
    fun observePlaysByMonth(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT strftime('%w', played_on) AS label, COUNT(*) * 1.0 AS value
        FROM sessions WHERE is_draft = 0 AND is_invalid = 0
        GROUP BY label ORDER BY label
        """
    )
    fun observePlaysByDayOfWeek(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT g.title AS label, COUNT(*) * 1.0 AS value
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0 AND s.is_invalid = 0
        GROUP BY g.id ORDER BY value DESC, g.title COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun observeMostPlayed(limit: Int): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT
            s.id, s.game_id, g.title AS game_title, g.thumbnail_path,
            s.played_on, s.duration_minutes, s.player_count, s.location,
            s.is_cooperative, (s.coop_outcome = 'WIN') AS coop_won, s.mode,
            s.is_incomplete, s.is_teaching_game, s.is_invalid, s.end_reason,
            (
                SELECT GROUP_CONCAT(p.name, ', ') FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.is_winner = 1
            ) AS winner_names
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0 AND s.is_invalid = 0 AND s.is_incomplete = 0
        ORDER BY CASE WHEN :longest = 1 THEN -s.duration_minutes ELSE s.duration_minutes END
        LIMIT :limit
        """
    )
    fun observeExtremeSessions(longest: Boolean, limit: Int): Flow<List<SessionListItem>>

    /**
     * Actual average duration against the midpoint of BGG's stated range. Only games
     * with a few plays qualify, because one teaching game is not evidence.
     */
    @Query(
        """
        SELECT
            g.id AS game_id, g.title,
            AVG(s.duration_minutes) AS actual_avg,
            (g.min_playtime_minutes + g.max_playtime_minutes) / 2.0 AS stated_avg,
            COUNT(*) AS play_count
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0 AND s.is_invalid = 0 AND s.is_incomplete = 0 AND s.is_teaching_game = 0
          AND g.min_playtime_minutes IS NOT NULL AND g.max_playtime_minutes IS NOT NULL
        GROUP BY g.id
        HAVING COUNT(*) >= :minPlays
        ORDER BY ABS(AVG(s.duration_minutes) -
                     (g.min_playtime_minutes + g.max_playtime_minutes) / 2.0) DESC
        LIMIT :limit
        """
    )
    fun observeDurationVsExpected(minPlays: Int, limit: Int): Flow<List<DurationVsExpectedRow>>

    /** Distinct ISO weeks that saw a play, newest first. Small by construction. */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%W', played_on) AS label, 1.0 AS value
        FROM sessions WHERE is_draft = 0 AND is_invalid = 0
        ORDER BY label DESC
        """
    )
    fun observeWeeksWithPlays(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT DISTINCT played_on AS label, 1.0 AS value
        FROM sessions WHERE is_draft = 0 AND is_invalid = 0
        ORDER BY label DESC
        """
    )
    fun observeDaysWithPlays(): Flow<List<LabelledValue>>

    /**
     * H-index: the largest N where at least N games have been played at least N times.
     * The correlated count is the window-function-free way to rank the play counts.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT s.game_id AS gid, COUNT(*) AS plays
            FROM sessions s WHERE s.is_draft = 0 AND s.is_invalid = 0 GROUP BY s.game_id
        ) t
        WHERE t.plays >= (
            SELECT COUNT(*) FROM (
                SELECT s2.game_id AS gid2, COUNT(*) AS plays2
                FROM sessions s2 WHERE s2.is_draft = 0 AND s2.is_invalid = 0 GROUP BY s2.game_id
            ) t2
            WHERE t2.plays2 >= t.plays
        )
        """
    )
    fun observeHIndex(): Flow<Int>

    /**
     * How often the player who went first won one game, and how often the first seat
     * would have won it if going first meant nothing.
     *
     * The bare rate is not readable on its own: 40% is a rout at a table of five and a
     * losing record at a table of two. The chance figure is the average, per play, of
     * the winners divided by the players, so a mixed pile of two- and five-player plays
     * still gets a baseline the actual rate can be held against.
     *
     * Co-op plays are out, as they are everywhere a win rate is computed -- the table
     * wins or loses together, so who started says nothing. Abandoned plays are out for
     * the reason they are out of the faction records: nobody won them. So are solo
     * plays, where the only player also went first and would push the rate to 100%
     * while measuring nothing, and plays with no winner recorded, which would drag it
     * down the same way.
     *
     * Always one game: a first-player advantage is a property of a game, not of a
     * shelf, so averaging it over a collection mixes games that hand the first seat an
     * edge with games that hand it nothing and reads as neither.
     */
    @Query(
        """
        SELECT
            COUNT(*) AS plays,
            COALESCE(SUM(t.first_won), 0) AS wins,
            AVG(t.chance) * 100.0 AS expected_win_rate
        FROM (
            SELECT
                MAX(sp.turn_order = 1 AND sp.is_winner = 1) AS first_won,
                SUM(sp.is_winner) * 1.0 / COUNT(*) AS chance
            FROM sessions s
            JOIN session_players sp ON sp.session_id = s.id
            WHERE s.is_draft = 0 AND s.is_invalid = 0 AND s.is_incomplete = 0 AND s.is_cooperative = 0
              AND s.game_id = :gameId
            GROUP BY s.id
            HAVING SUM(sp.turn_order = 1) = 1
               AND COUNT(*) > 1
               AND SUM(sp.is_winner) > 0
        ) t
        """
    )
    fun observeFirstPlayerRecord(gameId: Long): Flow<FirstPlayerRecord>

    // --- value --------------------------------------------------------------------

    @Query(
        """
        SELECT
            g.id AS game_id, g.title, c.total_cost, g.currency,
            COUNT(s.id) AS play_count,
            c.total_cost / COUNT(s.id) AS cost_per_play
        FROM games g
        JOIN game_costing c ON c.game_id = g.id
        JOIN sessions s ON s.game_id = g.id AND s.is_draft = 0 AND s.is_invalid = 0
        WHERE c.total_cost IS NOT NULL AND c.total_cost > 0
          AND g.status IN ('OWNED', 'LENT_OUT')
        GROUP BY g.id
        HAVING COUNT(s.id) > 0
        ORDER BY CASE WHEN :cheapestFirst = 1 THEN c.total_cost / COUNT(s.id)
                      ELSE -(c.total_cost / COUNT(s.id)) END
        LIMIT :limit
        """
    )
    fun observeCostPerPlay(cheapestFirst: Boolean, limit: Int): Flow<List<CostPerPlayRow>>

    @Query(
        """
        SELECT
            COALESCE(SUM(c.total_cost), 0) /
            NULLIF((SELECT COUNT(*) FROM sessions s2
                    JOIN games g2 ON g2.id = s2.game_id
                    JOIN game_costing c2 ON c2.game_id = g2.id
                    WHERE s2.is_draft = 0 AND s2.is_invalid = 0 AND c2.total_cost IS NOT NULL
                      AND g2.status IN ('OWNED', 'LENT_OUT')), 0)
        FROM games g
        JOIN game_costing c ON c.game_id = g.id
        WHERE c.total_cost IS NOT NULL AND g.status IN ('OWNED', 'LENT_OUT')
        """
    )
    fun observeOverallCostPerPlay(): Flow<Double?>

    /**
     * Accessories land in the year the game was added, because a cost line carries no
     * date of its own. It is the only date there is, and it keeps these bars summing to
     * the collection value; a year is not so fine a bucket that a set of sleeves bought
     * the following January makes it say something false.
     */
    @Query(
        """
        SELECT substr(g.date_added, 1, 4) AS label,
               COALESCE(SUM(c.total_cost), 0) AS value
        FROM games g
        JOIN game_costing c ON c.game_id = g.id
        WHERE c.total_cost IS NOT NULL AND g.status IN ('OWNED', 'LENT_OUT', 'SOLD')
        GROUP BY label ORDER BY label
        """
    )
    fun observeSpendByYear(): Flow<List<LabelledValue>>

    /** Owned, paid for, and still never played: the purchases that have earned nothing. */
    @Query(
        """
        SELECT g.title AS label, c.total_cost AS value
        FROM games g
        JOIN game_costing c ON c.game_id = g.id
        WHERE c.total_cost IS NOT NULL AND c.total_cost > 0
          AND g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0 AND s.is_invalid = 0)
        ORDER BY c.total_cost DESC
        LIMIT :limit
        """
    )
    fun observeDeadWeight(limit: Int): Flow<List<LabelledValue>>

    // --- players ------------------------------------------------------------------

    @Query(
        """
        SELECT
            p.id AS player_id, p.name AS player_name, p.color_hex,
            COUNT(sp.id) AS plays,
            COALESCE(SUM(sp.is_winner), 0) AS wins,
            AVG(sp.score) AS avg_score
        FROM players p
        JOIN session_players sp ON sp.player_id = p.id
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_invalid = 0
        WHERE (:gameId IS NULL OR s.game_id = :gameId)
        GROUP BY p.id
        HAVING COUNT(sp.id) > 0
        ORDER BY wins * 1.0 / COUNT(sp.id) DESC, plays DESC
        """
    )
    fun observeStandings(gameId: Long?): Flow<List<PlayerStandingRow>>

    /**
     * Head-to-head against the device owner. Only competitive sessions count: in a
     * co-op everybody wins or loses together, which says nothing about who is better.
     * Abandoned plays do not count either -- a game nobody finished settled nothing
     * between the two, and every other statistic here already filters them out.
     *
     * The three outcomes are about which of the pair came out ahead, so they turn on
     * both flags rather than on either one alone. A win is a sole win. Everything that
     * is not one player alone winning is a draw between them: a shared victory, since
     * `is_winner` is set on every player tied for first, and equally a play a third
     * player took, where the two of them finished level behind.
     *
     * So the three account for every shared play, which is why the count of them is not
     * shown beside the record any more.
     *
     * Ordered by how the record reads rather than by how much of it there is: wins over
     * the opponent first, then the fewest losses to them. A 10-0 outranks a 10-5, which
     * outranks a 10-13, and every one of those outranks a 5-3. Sorting by shared plays
     * instead would bury the records worth bragging about under the merely frequent.
     */
    @Query(
        """
        SELECT
            p.id AS opponent_id, p.name AS opponent_name, p.color_hex,
            COUNT(*) AS shared_plays,
            COALESCE(SUM(self.is_winner = 1 AND opp.is_winner = 0), 0) AS self_wins,
            COALESCE(SUM(self.is_winner = 0 AND opp.is_winner = 1), 0) AS opponent_wins,
            COALESCE(SUM(self.is_winner = opp.is_winner), 0) AS draws
        FROM session_players opp
        JOIN players p ON p.id = opp.player_id
        JOIN sessions s ON s.id = opp.session_id
            AND s.is_draft = 0 AND s.is_invalid = 0 AND s.is_cooperative = 0 AND s.is_incomplete = 0
        JOIN session_players self ON self.session_id = s.id
        JOIN players sp2 ON sp2.id = self.player_id AND sp2.is_self = 1
        WHERE p.is_self = 0
        GROUP BY p.id
        ORDER BY self_wins DESC, opponent_wins ASC, p.name COLLATE NOCASE
        """
    )
    fun observeHeadToHead(): Flow<List<HeadToHeadRow>>

    /**
     * Plays a rule stopped early are excluded. A game that ended the moment a condition
     * was met never reached final scoring, so any number recorded against it is a
     * partial count and averaging it together with full scores understates the average.
     *
     * The test is for the ordinary ending rather than for the absence of one: the column
     * was null on a normal play only until every play was asked how it ended, and it has
     * said `STANDARD` since. A row still holding null is one written before that.
     */
    @Query(
        """
        SELECT g.title AS label, AVG(sp.score) AS value
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_invalid = 0
            AND COALESCE(s.end_condition, 'STANDARD') = 'STANDARD'
        JOIN games g ON g.id = s.game_id
        WHERE sp.player_id = :playerId AND sp.score IS NOT NULL
        GROUP BY g.id
        ORDER BY value DESC
        LIMIT :limit
        """
    )
    fun observeAverageScoreByGame(playerId: Long, limit: Int): Flow<List<LabelledValue>>

    /**
     * Every game the player has a competitive play of. There is no minimum sample: a
     * profile is a record of what someone has played, and hiding the games played once
     * leaves a player who is still building a history looking at a single bar.
     *
     * Co-op plays stay out, as everywhere a win rate is computed. The table wins or
     * loses together, so counting those says nothing about one player.
     *
     * The sample size comes back with the rate so the screen can qualify it, and equal
     * rates are ranked by plays -- five wins from five outranks one from one, which is
     * the order anyone reading the list already has in mind.
     */
    @Query(
        """
        SELECT g.id AS game_id,
               g.title AS title,
               COUNT(*) AS plays,
               COALESCE(SUM(sp.is_winner), 0) AS wins,
               COALESCE(SUM(sp.is_winner), 0) * 100.0 / COUNT(*) AS win_rate
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_invalid = 0 AND s.is_cooperative = 0
        JOIN games g ON g.id = s.game_id
        WHERE sp.player_id = :playerId
        GROUP BY g.id
        ORDER BY win_rate DESC, plays DESC, title COLLATE NOCASE
        """
    )
    fun observeWinRateByGame(playerId: Long): Flow<List<GameWinRateRow>>

    /**
     * The best score the player has ever recorded at each game they have scored one at.
     *
     * Best is not always biggest. A game flagged as high-score-wins takes the largest
     * number; golf scoring takes the smallest, and MAX there would report somebody's
     * worst round as their record. The flag comes back with the row so the screen can
     * say which of the two it is showing.
     *
     * The same plays count as in the average, and for the same reasons: a draft is not a
     * play yet, and a play a rule stopped early never reached final scoring, so the
     * partial number against it is not a record of anything.
     *
     * Ordered by how much the player has played the game rather than by the score. Two
     * games' scores share no scale -- 92 at Wingspan against 8 at Hive is not a ranking
     * of anything -- so sorting the numbers against each other would invent a comparison
     * the data does not support.
     */
    @Query(
        """
        SELECT g.id AS game_id,
               g.title AS title,
               g.high_score_wins AS high_score_wins,
               COUNT(*) AS plays,
               CASE WHEN g.high_score_wins = 1 THEN MAX(sp.score) ELSE MIN(sp.score) END AS best_score
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_invalid = 0
            AND COALESCE(s.end_condition, 'STANDARD') = 'STANDARD'
        JOIN games g ON g.id = s.game_id
        WHERE sp.player_id = :playerId AND sp.score IS NOT NULL
        GROUP BY g.id
        ORDER BY plays DESC, title COLLATE NOCASE
        """
    )
    fun observePersonalBestByGame(playerId: Long): Flow<List<PersonalBestRow>>

    /**
     * Who walked away from one play holding a new record at that game.
     *
     * Asked of the play rather than of the player because that is the question the
     * shared card puts: not "what is Hafiz's best at Wingspan", but "did this evening
     * beat it". The answer is a set of player ids, one for each row of the session that
     * came out in front of everything that player had scored at the game before.
     *
     * Strictly better, so equalling a record does not set one. A record has to be beaten
     * to be new, and a card announcing a personal best next to a score somebody has
     * already made would be overstating the evening.
     *
     * A player's first scored play of a game is deliberately not a best. There was
     * nothing to beat, which is why the comparison is against MAX/MIN of the other
     * plays: with no other plays those are null, and the comparison drops the row rather
     * than crowning a debut. That play is already marked on the card as a first play.
     *
     * Which plays are allowed to hold the record, and which count as setting one, are
     * the same rules [observePersonalBestByGame] uses -- a draft is not a play yet, and
     * a play a rule stopped early never reached final scoring.
     */
    @Query(
        """
        SELECT sp.player_id
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_invalid = 0
            AND COALESCE(s.end_condition, 'STANDARD') = 'STANDARD'
        JOIN games g ON g.id = s.game_id
        WHERE sp.session_id = :sessionId AND sp.score IS NOT NULL
          AND CASE WHEN g.high_score_wins = 1
              THEN sp.score > (
                  SELECT MAX(p.score) FROM session_players p
                  JOIN sessions ps ON ps.id = p.session_id AND ps.is_draft = 0 AND ps.is_invalid = 0
                      AND COALESCE(ps.end_condition, 'STANDARD') = 'STANDARD'
                  WHERE p.player_id = sp.player_id AND ps.game_id = s.game_id
                    AND p.session_id <> sp.session_id AND p.score IS NOT NULL
              )
              ELSE sp.score < (
                  SELECT MIN(p.score) FROM session_players p
                  JOIN sessions ps ON ps.id = p.session_id AND ps.is_draft = 0 AND ps.is_invalid = 0
                      AND COALESCE(ps.end_condition, 'STANDARD') = 'STANDARD'
                  WHERE p.player_id = sp.player_id AND ps.game_id = s.game_id
                    AND p.session_id <> sp.session_id AND p.score IS NOT NULL
              )
              END
        """
    )
    suspend fun personalBestsSetIn(sessionId: Long): List<Long>
}
