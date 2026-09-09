package com.boardgamenation.tracker.data.csv

/**
 * The exact shape of every exported file.
 *
 * Primary keys are exported. That is what lets a replace-mode import reinstate the
 * database row for row, ids included, so an export/wipe/import round trip reproduces
 * identical statistics rather than merely equivalent ones. Merge mode ignores the ids
 * and matches on the natural keys instead, because ids from another device mean nothing.
 */
object CsvSchema {

    const val GAMES = "games.csv"
    const val GAME_COSTS = "game_costs.csv"
    const val TAGS = "tags.csv"
    const val GAME_TAGS = "game_tags.csv"
    const val PLAYERS = "players.csv"
    const val SESSIONS = "sessions.csv"
    const val SESSION_PLAYERS = "session_players.csv"
    const val SESSION_EXPANSIONS = "session_expansions.csv"
    const val SESSION_MODES = "session_modes.csv"
    const val RUBRICS = "rubrics.csv"
    const val RUBRIC_CRITERIA = "rubric_criteria.csv"
    const val GAME_RATINGS = "game_ratings.csv"
    const val GAME_RATING_SCORES = "game_rating_scores.csv"
    const val ACHIEVEMENT_UNLOCKS = "achievement_unlocks.csv"
    const val MANIFEST = "manifest.csv"

    val ALL_FILES = listOf(
        GAMES, GAME_COSTS, TAGS, GAME_TAGS, PLAYERS, SESSIONS, SESSION_PLAYERS,
        SESSION_EXPANSIONS, SESSION_MODES, RUBRICS, RUBRIC_CRITERIA, GAME_RATINGS,
        GAME_RATING_SCORES, ACHIEVEMENT_UNLOCKS, MANIFEST
    )

    /**
     * Import order. Parents before children, so a foreign key never points at a row that
     * has not been written yet.
     */
    val IMPORT_ORDER = listOf(
        GAMES, GAME_COSTS, TAGS, GAME_TAGS, PLAYERS, SESSIONS, SESSION_PLAYERS,
        SESSION_EXPANSIONS, SESSION_MODES, RUBRICS, RUBRIC_CRITERIA, GAME_RATINGS,
        GAME_RATING_SCORES, ACHIEVEMENT_UNLOCKS
    )

    val gameColumns = listOf(
        "id", "bgg_id", "title", "year_published", "min_players", "max_players",
        "best_player_count", "min_playtime_minutes", "max_playtime_minutes", "weight",
        "bgg_rating", "publisher", "thumbnail_path", "date_added", "price",
        "currency", "purchase_note", "status", "wishlist_priority",
        "lent_to", "lent_date", "is_expansion", "base_game_id", "scoring_mode",
        "high_score_wins", "notes", "created_at", "updated_at"
    )

    val gameCostColumns = listOf("id", "game_id", "label", "amount", "sort_order")

    val tagColumns = listOf("id", "name", "kind")

    val gameTagColumns = listOf("game_id", "tag_id")

    val playerColumns = listOf("id", "name", "is_self", "color_hex", "notes", "archived")

    val sessionColumns = listOf(
        "id", "game_id", "played_on", "started_at", "ended_at", "duration_minutes",
        "player_count", "location", "is_cooperative", "coop_outcome", "mode",
        "end_condition", "end_reason", "is_incomplete", "is_teaching_game", "paused_ms",
        "photo_uri", "notes", "created_at", "updated_at"
    )

    val sessionPlayerColumns = listOf(
        "id", "session_id", "player_id", "score", "placement", "is_winner", "faction",
        "turn_order", "seat", "team", "is_new_player", "turn_time_ms",
        "bank_time_remaining_ms"
    )

    val sessionExpansionColumns = listOf("session_id", "game_id")

    /**
     * The `mode` column on `sessions.csv` is the same answer written as one line, kept
     * because it is what every list and card reads. This file is the set behind it, and
     * is what an import restores; an archive from before it existed has only the line,
     * and gets the one-element set that line always meant.
     */
    val sessionModeColumns = listOf("session_id", "mode", "sort_order")

    val rubricColumns = listOf("id", "name", "description", "archived")

    val rubricCriterionColumns = listOf(
        "id",
        "rubric_id",
        "name",
        "description",
        "weight",
        "max_score",
        "sort_order"
    )

    val gameRatingColumns = listOf(
        "id",
        "game_id",
        "rubric_id",
        "rated_on",
        "computed_score",
        "notes"
    )

    val gameRatingScoreColumns = listOf("id", "game_rating_id", "criterion_id", "score")

    /**
     * Unlocks are keyed by achievement *code*, not id. Codes are stable across app
     * versions and installs; the numeric id is an implementation detail of one database.
     */
    val achievementUnlockColumns = listOf(
        "achievement_code",
        "unlocked_at",
        "progress_value",
        "session_id"
    )

    val manifestColumns = listOf("key", "value")

    /** The columns an importer refuses to proceed without. */
    fun requiredColumnsFor(file: String): List<String> = when (file) {
        GAMES -> listOf("title", "date_added", "status")
        GAME_COSTS -> listOf("game_id", "label", "amount")
        TAGS -> listOf("name", "kind")
        GAME_TAGS -> listOf("game_id", "tag_id")
        PLAYERS -> listOf("name")
        SESSIONS -> listOf("game_id", "played_on", "duration_minutes", "player_count")
        SESSION_PLAYERS -> listOf("session_id", "player_id")
        SESSION_EXPANSIONS -> listOf("session_id", "game_id")
        SESSION_MODES -> listOf("session_id", "mode")
        RUBRICS -> listOf("name")
        RUBRIC_CRITERIA -> listOf("rubric_id", "name")
        GAME_RATINGS -> listOf("game_id", "rubric_id", "rated_on", "computed_score")
        GAME_RATING_SCORES -> listOf("game_rating_id", "criterion_id", "score")
        ACHIEVEMENT_UNLOCKS -> listOf("achievement_code", "unlocked_at")
        else -> emptyList()
    }

    fun columnsFor(file: String): List<String> = when (file) {
        GAMES -> gameColumns
        GAME_COSTS -> gameCostColumns
        TAGS -> tagColumns
        GAME_TAGS -> gameTagColumns
        PLAYERS -> playerColumns
        SESSIONS -> sessionColumns
        SESSION_PLAYERS -> sessionPlayerColumns
        SESSION_EXPANSIONS -> sessionExpansionColumns
        SESSION_MODES -> sessionModeColumns
        RUBRICS -> rubricColumns
        RUBRIC_CRITERIA -> rubricCriterionColumns
        GAME_RATINGS -> gameRatingColumns
        GAME_RATING_SCORES -> gameRatingScoreColumns
        ACHIEVEMENT_UNLOCKS -> achievementUnlockColumns
        MANIFEST -> manifestColumns
        else -> emptyList()
    }
}
