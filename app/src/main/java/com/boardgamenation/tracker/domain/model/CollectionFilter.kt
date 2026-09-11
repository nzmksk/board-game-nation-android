package com.boardgamenation.tracker.domain.model

/** Playtime filter buckets, expressed against the game's stated maximum playtime. */
enum class PlaytimeBucket(val minMinutes: Int, val maxMinutes: Int) {
    UNDER_30(0, 29),
    THIRTY_TO_60(30, 60),
    SIXTY_TO_120(61, 120),
    OVER_120(121, Int.MAX_VALUE)
}

enum class CollectionSort {
    TITLE,
    DATE_ADDED,
    PLAY_COUNT,
    RATING,
    PRICE,
    COST_PER_PLAY,
    LAST_PLAYED,
    WEIGHT
}

/** How the collection list is displayed. */
enum class CollectionLayout { LIST, GRID }

/**
 * Every filter the collection list can apply. Held as one value so the screen state is
 * a single object and the SQL builder has one input.
 */
data class CollectionFilter(
    val search: String = "",
    val statuses: Set<GameStatus> = emptySet(),

    /** Games playable with exactly this many players. */
    val playerCount: Int? = null,
    val playtime: PlaytimeBucket? = null,

    /** Matches games carrying any of these tags. */
    val tagIds: Set<Long> = emptySet(),

    /** null means "don't care"; true only rated, false only unrated. */
    val rated: Boolean? = null,
    val includeExpansions: Boolean = true,

    val sort: CollectionSort = CollectionSort.TITLE,
    val ascending: Boolean = true
) {
    /**
     * Lights a status chip or puts it out, keeping the set to one question at a time.
     *
     * OWNED, WISHLIST, SOLD and LENT_OUT each say where a copy of the game is: on the
     * shelf, not bought yet, gone, or out on loan. [GameStatus.PLAYED_NOT_OWNED] says
     * there is no copy and never was, so it is not another place to look -- and a set
     * holding it alongside any of the others is an OR that hands back the very games the
     * chip just tapped excludes. Asking for owned games and being shown games marked as
     * never owned reads as the filter having failed, not as a wider search.
     *
     * So the two sides put each other out: lighting a shelf status clears
     * [GameStatus.PLAYED_NOT_OWNED], and lighting [GameStatus.PLAYED_NOT_OWNED] clears
     * every shelf status. Only lighting a chip does this; turning one off is left alone,
     * because that never widens the list.
     */
    fun withStatusToggled(status: GameStatus): CollectionFilter = copy(
        statuses = when {
            status in statuses -> statuses - status
            status == GameStatus.PLAYED_NOT_OWNED -> setOf(status)
            else -> statuses - GameStatus.PLAYED_NOT_OWNED + status
        }
    )

    val isActive: Boolean
        get() = search.isNotBlank() || statuses.isNotEmpty() || playerCount != null ||
            playtime != null || tagIds.isNotEmpty() || rated != null ||
            !includeExpansions

    /** How many chips are lit, for the "clear filters" affordance. */
    val activeCount: Int
        get() = listOf(
            statuses.isNotEmpty(),
            playerCount != null,
            playtime != null,
            tagIds.isNotEmpty(),
            rated != null,
            !includeExpansions
        ).count { it }
}
