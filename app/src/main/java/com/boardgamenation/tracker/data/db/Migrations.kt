package com.boardgamenation.tracker.data.db

import androidx.room.migration.Migration
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
        MIGRATION_11_12
    )
}
