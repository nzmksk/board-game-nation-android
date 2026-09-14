package com.boardgamenation.tracker.data.db

import androidx.room.migration.Migration
import com.boardgamenation.tracker.data.db.entity.BACKFILL_SESSION_MODES_SQL
import com.boardgamenation.tracker.data.db.entity.GAME_COSTING_SQL
import com.boardgamenation.tracker.data.db.entity.GameCostingView

/**
 * Every schema change ships a migration here and a test in `MigrationTest` that opens a
 * real database at the old version, runs the migration, and asserts the data survived.
 *
 * There is exactly one rule in this file: never delete user data to make a schema fit.
 * `fallbackToDestructiveMigration` is not used anywhere in this project, so a missing
 * migration is a loud crash in development rather than a silent wipe on someone's phone.
 *
 * Adding one looks like:
 *
 *     private val MIGRATION_4_5 = Migration(4, 5) { db ->
 *         db.execSQL("ALTER TABLE games ADD COLUMN sleeved INTEGER NOT NULL DEFAULT 0")
 *     }
 *
 * then append it to [ALL].
 */
object Migrations {

    /**
     * Records how a play ended, for games that can finish the moment a condition is met.
     *
     * Purely additive, and deliberately so: a null `end_condition` means the ordinary
     * case of playing through to final scoring, which is exactly what every session
     * written before this migration did. Nothing needs backfilling.
     */
    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL(
            "ALTER TABLE games ADD COLUMN sudden_death_possible INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("ALTER TABLE sessions ADD COLUMN end_condition TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN end_reason TEXT")
    }

    /**
     * Moves designers out of a comma-joined column and into the tag table.
     *
     * `games.designers` was one delimited string, a different shape from mechanics and
     * categories despite being the same kind of data, and nothing could filter or group
     * on it. The names are split into `tags` rows of a new `DESIGNER` kind and linked
     * through `game_tags`, after which the column goes away.
     *
     * The backfill runs before the drop and inside the one transaction Room wraps a
     * migration in, so either every designer survives the move or none of it happens.
     */
    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        // Splitting a delimited column needs a recursive CTE; SQLite has had them since
        // 3.8.3, comfortably below the 3.18 that ships with minSdk 26. A trailing comma
        // is appended so the last (or only) name terminates like every other one.
        //
        // A designer whose own name contains a comma splits wrongly. That ambiguity was
        // baked into the comma-joined column and cannot be recovered here; getting out of
        // that representation is the whole point of the migration.
        db.execSQL(
            """
            CREATE TEMP TABLE designer_split AS
            WITH RECURSIVE split(game_id, name, rest) AS (
                SELECT id, '', designers || ','
                  FROM games
                 WHERE designers IS NOT NULL AND trim(designers) <> ''
                UNION ALL
                SELECT game_id,
                       trim(substr(rest, 1, instr(rest, ',') - 1)),
                       substr(rest, instr(rest, ',') + 1)
                  FROM split
                 WHERE rest <> ''
            )
            SELECT game_id, name FROM split WHERE name <> ''
            """.trimIndent()
        )

        // The unique index on (name, kind) makes OR IGNORE the de-duplicator: two games
        // by the same designer converge on one tag row.
        db.execSQL(
            """
            INSERT OR IGNORE INTO tags (name, kind)
            SELECT DISTINCT name, 'DESIGNER' FROM designer_split
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO game_tags (game_id, tag_id)
            SELECT s.game_id, t.id
              FROM designer_split s
              JOIN tags t ON t.name = s.name AND t.kind = 'DESIGNER'
            """.trimIndent()
        )
        db.execSQL("DROP TABLE designer_split")

        // minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
        // removing the column means the create/copy/drop/rename recipe.
        //
        // The AUTOINCREMENT counter is stashed first. Dropping the table takes its
        // sqlite_sequence row with it, and copying the rows back only raises the counter
        // as far as MAX(id) -- so a collection whose highest-numbered game had been
        // deleted would start handing that id out a second time.
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `best_player_count` TEXT,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `publisher` TEXT,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `in_possession` INTEGER NOT NULL DEFAULT 1,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `sudden_death_possible` INTEGER NOT NULL DEFAULT 0,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `sudden_death_possible`, `notes`,
                `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `sudden_death_possible`, `notes`,
                `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)"
        )

        // Put the counter back where it was, but never move it backwards.
        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Lets the live timer be a count-up clock as well as a dual countdown.
     *
     * Both columns are additive with defaults matching what every stored clock already
     * is: a turn-based one, with no table time because time belonged to a seat.
     */
    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL(
            "ALTER TABLE timer_state ADD COLUMN mode TEXT NOT NULL DEFAULT 'TURN_BASED'"
        )
        db.execSQL(
            "ALTER TABLE timer_state ADD COLUMN table_time_ms INTEGER NOT NULL DEFAULT 0"
        )
    }

    /**
     * Records the configuration a game was played at -- modules, level, scenario.
     *
     * Additive and nullable: a null `mode` means nobody recorded one, which is what
     * every session written before this migration is. Nothing needs backfilling.
     */
    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT")
    }

    /**
     * Records who went first and the order the table took its turns in.
     *
     * Additive and nullable, so nothing needs backfilling. Null is the honest answer for
     * every play logged before the column existed: defaulting it to 1 would have handed
     * a first player to thousands of rows nobody recorded one for, and the first-player
     * statistics would then be measuring the default rather than the table.
     */
    private val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE session_players ADD COLUMN turn_order INTEGER")
    }

    /**
     * Records which side a player was on, for games where a side wins together.
     *
     * Additive and nullable. A null team is every session written before this one,
     * where a win belonged to a player rather than to a side.
     */
    private val MIGRATION_6_7 = Migration(6, 7) { db ->
        db.execSQL("ALTER TABLE session_players ADD COLUMN team TEXT")
    }

    /**
     * Records where each player sat, for the games whose rules reach sideways.
     *
     * Additive and nullable, and it stays null on every existing row for a stronger
     * reason than the migrations above. A turn order could at least be guessed at from
     * the order rows were written in; a seating cannot, because the question it answers
     * is who was *beside* whom, and a wrong neighbour is worse than an admitted unknown.
     * Numbering the old rows 1..n would have invented an arrangement for every play in
     * the database and read back as though someone had recorded it.
     */
    private val MIGRATION_7_8 = Migration(7, 8) { db ->
        db.execSQL("ALTER TABLE session_players ADD COLUMN seat INTEGER")
    }

    /**
     * Turns sudden death into an ending every play has, whatever its scoring.
     *
     * Three things used to answer "how did this end?", and only between them: a null
     * `end_condition` meant the game ran to its own finish, `SUDDEN_DEATH` meant a rule
     * stopped it, and `is_incomplete` -- a separate column, on the other side of the
     * form -- meant the table gave up. Nothing joined them up, so a play could claim two
     * at once, and a game with no `sudden_death_possible` flag was never asked at all.
     *
     * Every existing row is written into the one vocabulary. None of it is guesswork:
     *
     *  - abandoned wins where both were ticked. It is the stronger claim about the play
     *    -- there is no result -- and it is the one the statistics already act on, so
     *    letting the other reading win would quietly readmit those plays to the win-rate.
     *  - `SUDDEN_DEATH` becomes `SPECIFIC`, which is the same fact renamed.
     *  - a null becomes `STANDARD`, which is what the column's own documentation said a
     *    null meant. This is the previous meaning written down, not a new claim about
     *    plays nobody recorded.
     *
     * Reasons left on rows that are no longer `SPECIFIC` are kept where they are. They
     * are the user's own words and cost nothing to hold; the query that offers them back
     * as chips asks for the ending that owns one, so a stale reason is unread, not shown.
     *
     * `sudden_death_possible` then goes: the form asks every game how the play ended, so
     * there is no per-game flag left to gate it. minSdk 26 ships SQLite 3.18, which
     * predates ALTER TABLE DROP COLUMN (3.35), so removing the column means the
     * create/copy/drop/rename recipe -- and the AUTOINCREMENT counter has to be carried
     * over by hand, exactly as in [MIGRATION_2_3], or a collection whose highest-numbered
     * game had been deleted would hand that id out a second time.
     */
    private val MIGRATION_8_9 = Migration(8, 9) { db ->
        db.execSQL("UPDATE sessions SET end_condition = 'ABANDONED' WHERE is_incomplete = 1")
        db.execSQL(
            "UPDATE sessions SET end_condition = 'SPECIFIC' WHERE end_condition = 'SUDDEN_DEATH'"
        )
        db.execSQL("UPDATE sessions SET end_condition = 'STANDARD' WHERE end_condition IS NULL")

        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `best_player_count` TEXT,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `publisher` TEXT,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `in_possession` INTEGER NOT NULL DEFAULT 1,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)"
        )

        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Rewrites the games a preorder status was the only home for.
     *
     * "Preordered" answered a question about a receipt rather than about a shelf, and it
     * was the only status that did: everything else says where the copy is. Nothing in
     * the app ever acted on it -- it did not count toward the collection, it carried no
     * delivery date, and the only thing that told it apart from a wishlist entry was the
     * word on the chip -- so it was a fifth thing to choose between with nothing behind
     * it. The status a game played on somebody else's copy needs is a real gap; this was
     * not one.
     *
     * The rows become `WISHLIST`, which is the same claim the preorder was making: a game
     * somebody wants and does not have yet. That is a rename of what was already true, not
     * a new statement about anybody's collection.
     *
     * It has to happen here and not only in `GameStatus.fromStorage`, because the column
     * is filtered on by name. A row left saying `PREORDERED` would be read back as a
     * wishlist game on the detail screen while the wishlist filter -- which asks SQLite
     * for `status IN ('WISHLIST')` -- went on not returning it, and the game would sit in
     * the collection reachable only by scrolling past every filter that should have found
     * it.
     *
     * Nothing about the schema changes: `status` is TEXT with no constraint naming the
     * statuses, so this is a version bump carrying data across and 10.json is 9.json with
     * a new number on it.
     */
    private val MIGRATION_9_10 = Migration(9, 10) { db ->
        db.execSQL("UPDATE games SET status = 'WISHLIST' WHERE status = 'PREORDERED'")
    }

    /**
     * Drops `in_possession`, which was a second answer to a question the status answers.
     *
     * A game is in your possession exactly when it is `OWNED`: lending it is what moves
     * it to `LENT_OUT`, and a wishlist entry, a sold game or a game played on somebody
     * else's copy has no copy to possess. The column said so too, but only where
     * something remembered to write it -- the lend and return queries set it beside the
     * status, the edit form derived it from the status on save, and the bulk status menu
     * changed the status alone. A collection bulk-marked as lent out therefore went on
     * answering the "on the shelf" filter, and one bulk-marked as owned came back
     * claiming it was still out on loan.
     *
     * Nothing is backfilled and nothing needs to be. The column held no fact that the
     * `status` beside it does not, so dropping it is the removal of a disagreement rather
     * than the loss of an answer -- and where the two disagreed, the status is the one
     * the collection screen was already showing.
     *
     * `lent_to` and `lent_date` stay as they are, including on the rows whose status no
     * longer says lent out. They are what somebody typed, they cost nothing to hold, and
     * the lending section asks the status first, so a stale borrower is unread rather
     * than shown -- the same treatment [MIGRATION_8_9] gave a reason left on a play that
     * was no longer specific.
     *
     * minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
     * this is the create/copy/drop/rename recipe again, AUTOINCREMENT counter carried
     * over by hand exactly as in [MIGRATION_8_9].
     */
    private val MIGRATION_10_11 = Migration(10, 11) { db ->
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `best_player_count` TEXT,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `publisher` TEXT,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)"
        )

        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Adds the accessory costs a game accumulates after the box is paid for, and the
     * view that lets every costing query stop pretending `price` is the whole bill.
     *
     * Purely additive. No existing row is touched, and a collection that never records
     * an accessory sums to exactly the totals it summed to before: the view falls back
     * to `price` alone when a game has no cost rows, and still reports null -- unpriced,
     * excluded -- for a game that has neither.
     *
     * The view has to be created here. Room creates views for a fresh database only; an
     * upgraded one gets whatever the migration leaves behind, and the schema check that
     * runs immediately afterwards compares the text of the view it found against the
     * text it expects. Both sides read [GAME_COSTING_SQL], so there is no second copy to
     * keep in step.
     */
    private val MIGRATION_11_12 = Migration(11, 12) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `game_costs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `game_id` INTEGER NOT NULL,
                `label` TEXT NOT NULL,
                `amount` REAL NOT NULL,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_game_costs_game_id` ON `game_costs` (`game_id`)"
        )
        db.execSQL("DROP VIEW IF EXISTS `${GameCostingView.NAME}`")
        db.execSQL("CREATE VIEW `${GameCostingView.NAME}` AS $GAME_COSTING_SQL")
    }

    /**
     * Lets a play carry more than one configuration, because most of them always did.
     *
     * `sessions.mode` asked for one line of free text and got a combination typed into
     * it: "Championship + Legends + Weather" is three modules, not a mode. Written that
     * way the answer can be shown and nothing else -- which plays used Weather is a
     * question the string cannot be asked, and the chips the form offered back were
     * whole evenings rather than the parts they were assembled from.
     *
     * Nothing is lost and nothing is guessed. A play stored with one configuration is
     * given exactly that configuration, whole, as a one-element set; the string is never
     * split on anything, because the separator that would split "Championship + Legends"
     * correctly would cut "Cities & Knights" in half somewhere else.
     *
     * The column stays, and stays correct. Every list, share card and statistic reads it
     * for the one line they show, and it is written from the set on every save the way
     * `is_incomplete` is written from the end condition -- derived from it, never set
     * beside it.
     */
    private val MIGRATION_12_13 = Migration(12, 13) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `session_modes` (
                `session_id` INTEGER NOT NULL,
                `mode` TEXT NOT NULL,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`session_id`, `mode`),
                FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(BACKFILL_SESSION_MODES_SQL)
    }

    /**
     * Lets a play be marked as one that did not count, because the table played it
     * wrongly.
     *
     * Additive and defaulted to 0, which is the truth about every row already there: a
     * play nobody has flagged is a play that counted. Nothing is backfilled, because
     * only the user knows which evening was played with the rules misread, and guessing
     * would quietly delete real history from their statistics.
     *
     * Indexed for the same reason `is_draft` is. Every statistic in the app now filters
     * on this column, in SQL, and an index the query planner can use is worth more than
     * the row it costs.
     */
    private val MIGRATION_13_14 = Migration(13, 14) { db ->
        db.execSQL("ALTER TABLE sessions ADD COLUMN is_invalid INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_is_invalid` ON `sessions` (`is_invalid`)")
    }

    /**
     * Gives an investigative or escape-room play somewhere to record what each objective
     * cost.
     *
     * These games are all but always winnable and very rarely lost, so the one thing the
     * app could already record about them -- the table won -- is the one thing that says
     * nothing. What separates a case solved clean from one solved on the fourth hint is
     * per objective, so objectives get rows of their own.
     *
     * Purely additive, and nothing is backfilled. A play logged before this table existed
     * has no hint count hiding anywhere to recover: the question was never asked, and
     * inventing a zero would claim the table solved an old case unaided.
     */
    private val MIGRATION_14_15 = Migration(14, 15) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `session_objectives` (
                `session_id` INTEGER NOT NULL,
                `objective` TEXT NOT NULL,
                `hints_used` INTEGER NOT NULL DEFAULT 0,
                `attempts` INTEGER NOT NULL DEFAULT 1,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`session_id`, `objective`),
                FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    /**
     * Moves the publisher out of its own column and into the tag table.
     *
     * `games.publisher` held one name. A game usually has more than one -- the original
     * publisher, whoever localised the copy on the shelf, whoever reprinted it -- and BGG
     * lists them all, so the import kept the first and dropped the rest. The names become
     * `tags` rows of a new `PUBLISHER` kind, linked through `game_tags`, which is the
     * shape designers were moved to in [MIGRATION_2_3] and the same one mechanics and
     * categories have always had.
     *
     * Unlike that migration this one does not split on commas. `designers` was a joined
     * list and had to be taken apart; `publisher` was a single-valued field that BGG
     * itself wrote one name into, so a comma in it is far more likely to be part of a
     * company name than a separator somebody meant. The whole trimmed value becomes one
     * publisher, and a collection that did cram two names into the field can separate
     * them on the edit form -- which is not something a wrongly split "Days of Wonder,
     * Inc." can be put back together from.
     *
     * The backfill runs before the drop and inside the one transaction Room wraps a
     * migration in, so either every publisher survives the move or none of it happens.
     */
    private val MIGRATION_15_16 = Migration(15, 16) { db ->
        // The unique index on (name, kind) makes OR IGNORE the de-duplicator: two games
        // from the same publisher converge on one tag row.
        db.execSQL(
            """
            INSERT OR IGNORE INTO tags (name, kind)
            SELECT DISTINCT trim(publisher), 'PUBLISHER'
              FROM games
             WHERE publisher IS NOT NULL AND trim(publisher) <> ''
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO game_tags (game_id, tag_id)
            SELECT g.id, t.id
              FROM games g
              JOIN tags t ON t.name = trim(g.publisher) AND t.kind = 'PUBLISHER'
             WHERE g.publisher IS NOT NULL AND trim(g.publisher) <> ''
            """.trimIndent()
        )

        // minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
        // removing the column is the create/copy/drop/rename recipe again, AUTOINCREMENT
        // counter carried over by hand exactly as in [MIGRATION_2_3].
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `best_player_count` TEXT,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)"
        )

        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Drops the best-player-count column.
     *
     * The column held free text -- "3", "3–4", "best at 4", whatever somebody typed --
     * written by two sources that never agreed: BGG's suggested-players poll on import,
     * and the edit form on every save after it. Nothing filtered on it, nothing counted
     * it, and nothing on screen said which of the two had written the value being shown.
     *
     * Unlike the designers and publisher columns, this one is not moved anywhere. Those
     * held names worth keeping in a shape that could not hold them; this held an opinion
     * about a game that the collection's own play history answers better and that no
     * feature was left reading, so the values go with the column.
     *
     * minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
     * removing the column is the create/copy/drop/rename recipe again, AUTOINCREMENT
     * counter carried over by hand exactly as in [MIGRATION_15_16].
     */
    private val MIGRATION_16_17 = Migration(16, 17) { db ->
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)"
        )

        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Moves what an expansion expands out of a column and into a link table.
     *
     * `games.base_game_id` held one answer, and the question has more than one: Ticket to
     * Ride: France expands both Ticket to Ride and Ticket to Ride: Europe, and the column
     * could only name whichever of them was picked on the form. The pairs move to
     * `game_expansions`, which also lets an expansion expand another expansion -- Legends
     * of the Sea Robbers sits on Catan and on Catan: Seafarers -- because both ends of the
     * link are ordinary `games` rows.
     *
     * The existing links are copied to a temp table before `games` is rebuilt, so the
     * pairs outlive the column they came from; nothing is dropped until they are safely
     * out. Every expansion keeps exactly the base game it had, as the only row of its set.
     *
     * `ON DELETE CASCADE` on both ends replaces what the nullable column promised. A link
     * row dies with either game and takes neither with it, so an expansion still outlives
     * the base game somebody sold.
     *
     * minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
     * removing the column is the create/copy/drop/rename recipe again, AUTOINCREMENT
     * counter carried over by hand exactly as in [MIGRATION_16_17].
     */
    private val MIGRATION_17_18 = Migration(17, 18) { db ->
        db.execSQL(
            "CREATE TEMP TABLE expansion_links AS " +
                "SELECT id AS expansion_id, base_game_id FROM games " +
                " WHERE base_game_id IS NOT NULL"
        )
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `thumbnail_path`, `date_added`, `price`,
                `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `lent_to`, `lent_date`, `is_expansion`,
                `scoring_mode`, `high_score_wins`, `notes`, `created_at`, `updated_at`
            FROM `games`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")

        // Created after the rebuild so its REFERENCES clause is written against the table
        // that is staying, rather than against one about to be dropped and renamed over.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `game_expansions` (
                `expansion_id` INTEGER NOT NULL,
                `base_game_id` INTEGER NOT NULL,
                PRIMARY KEY(`expansion_id`, `base_game_id`),
                FOREIGN KEY(`expansion_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_game_expansions_base_game_id` " +
                "ON `game_expansions` (`base_game_id`)"
        )
        // A link whose base game is not a row here would be a dangling reference the
        // column could not hold either, since it was a foreign key too.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `game_expansions` (`expansion_id`, `base_game_id`)
            SELECT expansion_id, base_game_id FROM expansion_links
             WHERE base_game_id IN (SELECT id FROM games)
               AND expansion_id IN (SELECT id FROM games)
            """.trimIndent()
        )
        db.execSQL("DROP TABLE expansion_links")

        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent()
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Ordered oldest to newest. Room composes them, so a device three versions behind
     * walks the chain rather than needing a 1-to-4 migration of its own.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18
    )
}
