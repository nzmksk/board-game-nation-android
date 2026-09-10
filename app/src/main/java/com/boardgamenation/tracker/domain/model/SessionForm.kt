package com.boardgamenation.tracker.domain.model

import java.time.LocalDate

/** One player's line on the session form. */
data class ParticipantForm(
    val playerId: Long,
    val playerName: String,
    val colorHex: String? = null,
    val score: Double? = null,
    val placement: Int? = null,
    val isWinner: Boolean = false,
    val faction: String? = null,

    /** Seat in the turn order; 1 went first, null means nobody recorded it. */
    val turnOrder: Int? = null,

    /** The chair round the table, numbered from 1. Null means nobody recorded it. */
    val seat: Int? = null,

    /** The side this player was on, when the game is played in teams. */
    val team: String? = null,

    val isNewPlayer: Boolean = false,
    val turnTimeMs: Long? = null,
    val bankTimeRemainingMs: Long? = null
)

/**
 * A session being entered or edited. Kept separate from the Room entity because the form
 * carries the player rows with it and works in [LocalDate] rather than ISO text.
 */
data class SessionForm(
    val id: Long = 0,
    val gameId: Long = 0,
    val gameTitle: String = "",
    val playedOn: LocalDate,
    val durationMinutes: Int = 0,
    val location: String? = null,
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
    val highScoreWins: Boolean = true,
    val coopOutcome: CoopOutcome? = null,

    /**
     * The configurations played: expansion sets, modules, a level, a scenario. Free
     * text, and a list because a game is very often set up several ways at once --
     * Heat's Championship season with Legends and Weather on top of it.
     */
    val modes: List<String> = emptyList(),

    /**
     * The objectives the table worked through, and what each one cost in hints and goes.
     *
     * Only an objective-based play records them, and only that mode's section of the form
     * offers them, so a play moved to another mode keeps none -- the save drops them for
     * the reason it drops a stale score or a stale side.
     */
    val objectives: List<SessionObjective> = emptyList(),

    /**
     * The side that won, for a team game. Not stored as a column of its own: the
     * winners are marked on the participants, so the winning side is whichever team
     * those rows belong to and cannot drift away from them.
     */
    val winningTeam: String? = null,

    /**
     * How the play ended. Never null on the form: a play the app was never able to ask
     * about reads back as [SessionEndCondition.STANDARD], which is what the nullable
     * column meant before there was anything else to say.
     */
    val endCondition: SessionEndCondition = SessionEndCondition.STANDARD,

    /** Free text naming the rule that stopped the play, when one did. */
    val endReason: String? = null,

    val isTeachingGame: Boolean = false,

    /**
     * The table played the wrong game: a piece set up wrongly, a rule read wrongly, and
     * nobody noticed until afterwards. The play stays in the log and drops out of every
     * statistic.
     *
     * Independent of [endCondition] rather than another value of it. How a play ended
     * and whether it was the right game are two different questions, and an evening can
     * easily answer both -- a misplayed game abandoned halfway through is still
     * abandoned.
     */
    val isInvalid: Boolean = false,

    val notes: String? = null,
    val photoUri: String? = null,
    val participants: List<ParticipantForm> = emptyList(),
    val expansionIds: List<Long> = emptyList(),
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val pausedMs: Long = 0,

    /**
     * False when the caller has already decided who won and no ranking should be
     * inferred -- quick log works that way, asking for winners directly instead of
     * scores.
     *
     * Deliberately transient and never persisted. Quick log used to express this by
     * forcing [scoringMode] to NONE, but the mode is written back onto the game after a
     * save, so one quick log silently reset the game's remembered scoring.
     */
    val derivePlacements: Boolean = true
) {
    /**
     * Whether the table shares one result. True of an investigative play as well as a
     * co-op: the case is cracked or it is not, and nobody at the table cracked it
     * privately.
     *
     * This is what writes `sessions.is_cooperative`, so the column means "the table had
     * one outcome" rather than "the game was a co-op". Every screen that reads it --
     * the result line on the session row, the shared card, the co-op win rates -- was
     * already asking the first question.
     */
    val isCooperative: Boolean get() = scoringMode.sharesTableOutcome

    /**
     * The configurations read as one line, which is what the session list, the share
     * card and the statistics show.
     *
     * Derived rather than held beside [modes] so the two cannot come to disagree, and
     * the `sessions.mode` column is written from this on every save for the same reason.
     */
    val mode: String? get() = SessionModes.label(modes)

    /** Sides win together, so nobody is marked a winner individually. */
    val isTeamBased: Boolean get() = scoringMode.recordsSides

    /** Whether this play lists objectives and what each of them cost. */
    val hasObjectives: Boolean get() = scoringMode.recordsObjectives

    /** Hints taken across the whole case, which is the figure worth comparing cases by. */
    val totalHints: Int get() = SessionObjectives.totalHints(objectives)

    /** The sides named on the form so far, in the order they were entered. */
    val teams: List<String>
        get() = participants.mapNotNull { it.team?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }

    /** A play a rule stopped before the game reached its own ending. */
    val endedEarly: Boolean get() = endCondition == SessionEndCondition.SPECIFIC

    /**
     * Abandoned before any ending was reached: counted in play totals, excluded from
     * duration and win-rate statistics.
     *
     * Read off the end condition rather than held beside it, so the two can never
     * disagree. A play cannot both have been given up on and have run to the last round,
     * and while they were separate fields the form allowed exactly that.
     */
    val isIncomplete: Boolean get() = endCondition == SessionEndCondition.ABANDONED

    /**
     * Whether the order the players are listed in is what decides the result.
     *
     * Only scored play needs this. A game stopped early has no final scores to rank by,
     * so the order the user puts the table in is the ranking. Every other mode already
     * decides the result some way the ending leaves alone -- a co-op by the table's
     * outcome, a team game by the winning side, manual placement by that same order --
     * and overriding those would throw away the answer the user actually gave.
     */
    val ranksByOrder: Boolean get() = endedEarly && scoringMode == ScoringMode.RANKED_SCORES

    /** Who took the first turn, when anyone said. */
    val firstPlayer: ParticipantForm? get() = participants.firstOrNull { it.turnOrder == 1 }

    /** The players who have been given a chair, read round the table from seat 1. */
    val seating: List<ParticipantForm>
        get() = participants.filter { it.seat != null }.sortedBy { it.seat }

    /** Who sat either side of whom, once the whole table has been seated. */
    val neighbours: Map<Long, Neighbours> get() = Seating.neighbours(participants)

    /** The form is savable once it names a game and has at least one player. */
    val isValid: Boolean get() = gameId != 0L && participants.isNotEmpty()
}

/**
 * The configurations one play was set up with.
 *
 * A set, not a sentence. The two rules here are what keep it one: a label is what
 * somebody typed with the spaces taken off, and naming the same configuration twice
 * names it once -- matched case-insensitively, the way sides and factions are matched
 * everywhere else, because "Weather" typed tonight and "weather" typed last month are
 * the same module to everybody except a string comparison.
 *
 * Nothing here ever splits a label. The user's own wording is the whole point of free
 * text, and a separator that divides "Championship + Legends" correctly cuts "Cities &
 * Knights" in half.
 */
object SessionModes {

    /** How the set reads on one line: "Championship + Legends + Weather". */
    const val SEPARATOR = " + "

    /** The labels worth storing, in the order they were named. */
    fun clean(modes: List<String>): List<String> = modes.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    /**
     * The one line every list and card shows, or null when nobody recorded anything.
     * A play with no configuration must read as absent rather than as an empty chip.
     */
    fun label(modes: List<String>): String? = clean(modes).joinToString(SEPARATOR).takeIf { it.isNotEmpty() }

    /** Whether this configuration is already on the play, however it was capitalised. */
    fun contains(modes: List<String>, mode: String): Boolean = modes.any { it.equals(mode.trim(), ignoreCase = true) }
}

/**
 * One objective and what it cost the table: the hints taken before it fell, and the goes
 * it took to get it right.
 *
 * Hints and attempts are what an investigative game is actually worth measuring by, and
 * they are per objective rather than per play because a case is a handful of puzzles: one
 * room that stopped everybody dead and four that did not is the story of the evening, and
 * a single total for the night hides it.
 */
data class SessionObjective(val objective: String, val hintsUsed: Int = 0, val attempts: Int = 1)

/**
 * The objectives one play worked through.
 *
 * The rules are the ones [SessionModes] keeps, for the same reasons: a label is what
 * somebody typed with the spaces taken off, and naming the same objective twice names it
 * once, matched case-insensitively because "The safe" and "the safe" are one puzzle to
 * everybody except a string comparison.
 *
 * The counts are held to what they can mean. Hints cannot be negative, and attempts
 * cannot be fewer than one: an objective somebody wrote down is one the table had a go
 * at, and zero goes would describe a puzzle nobody touched.
 */
object SessionObjectives {

    /** The rows worth storing, in the order they were entered. */
    fun clean(objectives: List<SessionObjective>): List<SessionObjective> = objectives
        .map {
            it.copy(
                objective = it.objective.trim(),
                hintsUsed = it.hintsUsed.coerceAtLeast(0),
                attempts = it.attempts.coerceAtLeast(1)
            )
        }
        .filter { it.objective.isNotEmpty() }
        .distinctBy { it.objective.lowercase() }

    /** Whether this objective is already on the play, however it was capitalised. */
    fun contains(objectives: List<SessionObjective>, objective: String): Boolean =
        objectives.any { it.objective.equals(objective.trim(), ignoreCase = true) }

    /** Hints taken across the whole case. */
    fun totalHints(objectives: List<SessionObjective>): Int = clean(objectives).sumOf { it.hintsUsed }

    /** Goes taken across the whole case, counting the ones that worked. */
    fun totalAttempts(objectives: List<SessionObjective>): Int = clean(objectives).sumOf { it.attempts }
}

/**
 * Turns scores into placements.
 *
 * Uses standard competition ranking, so a tie for first produces 1, 1, 3 rather than
 * 1, 1, 2. Tied players are all winners, which is the behaviour the data model was
 * built for: `is_winner` is explicit precisely so more than one row can carry it.
 */
object PlacementCalculator {

    fun derive(participants: List<ParticipantForm>, highScoreWins: Boolean): List<ParticipantForm> {
        val (scored, unscored) = participants.partition { it.score != null }
        if (scored.isEmpty()) {
            return participants.map { it.copy(placement = null, isWinner = false) }
        }

        val ordered = scored.sortedWith(
            if (highScoreWins) {
                compareByDescending { it.score!! }
            } else {
                compareBy { it.score!! }
            }
        )

        val placed = mutableListOf<ParticipantForm>()
        var currentPlacement = 1
        var previousScore: Double? = null
        ordered.forEachIndexed { index, participant ->
            if (previousScore != null && participant.score != previousScore) {
                // Skip the placements consumed by the tie above, so 1,1 is followed by 3.
                currentPlacement = index + 1
            }
            previousScore = participant.score
            placed += participant.copy(
                placement = currentPlacement,
                isWinner = currentPlacement == 1
            )
        }

        // Players with no score entered cannot be ranked, and are certainly not winners.
        placed += unscored.map { it.copy(placement = null, isWinner = false) }

        // Restore the caller's original ordering so the form does not jump around while
        // the user is still typing.
        val byId = placed.associateBy { it.playerId }
        return participants.mapNotNull { byId[it.playerId] }
    }

    /**
     * Applies manual ordering: the list order *is* the ranking, everyone above the first
     * gap is placed sequentially, and only position one wins.
     */
    fun fromOrder(participants: List<ParticipantForm>): List<ParticipantForm> = participants.mapIndexed { index, participant ->
        participant.copy(placement = index + 1, isWinner = index == 0)
    }

    /**
     * Applies a team result: everyone on the winning side wins, everyone else does not.
     *
     * Matched case-insensitively on the trimmed name, because "Liberals" typed once and
     * "liberals" typed again are the same side to everybody except a string comparison.
     * Nobody is placed: a side winning says nothing about the order within it.
     */
    fun applyTeams(participants: List<ParticipantForm>, winningTeam: String?): List<ParticipantForm> {
        val winner = winningTeam?.trim()?.lowercase()
        return participants.map { participant ->
            participant.copy(
                placement = null,
                isWinner = winner != null && participant.team?.trim()?.lowercase() == winner
            )
        }
    }

    /** In a co-op the table shares one result, so every participant gets the same flag. */
    fun applyCoop(participants: List<ParticipantForm>, outcome: CoopOutcome?): List<ParticipantForm> {
        val won = outcome == CoopOutcome.WIN
        return participants.map { it.copy(placement = null, isWinner = won) }
    }
}

/**
 * Keeps the turn order on a form coherent.
 *
 * The order is built by naming players in the order they played, and it has to survive
 * the edits that follow: dropping the second of four players must not leave 1, 3, 4, and
 * no two rows may both claim to have gone first. Recorded seats are therefore renumbered
 * into a run starting at 1 whenever the form is touched or saved.
 *
 * Players nobody named keep a null. A partial answer -- very often just "Aina started" --
 * is real information, and filling in the rest of the table would be inventing it.
 */
object TurnOrder {

    /** Closes gaps and breaks ties, leaving unrecorded players unrecorded. */
    fun normalise(participants: List<ParticipantForm>): List<ParticipantForm> = participants.renumber(
        position = { it.turnOrder },
        reseat = { participant, seat -> participant.copy(turnOrder = seat) }
    )

    /**
     * Adds a player to the end of the order, or takes one out of it.
     *
     * This is the whole interaction behind the picker: naming people in sequence builds
     * the order, and naming one again removes them while everyone behind closes up.
     */
    fun toggle(participants: List<ParticipantForm>, playerId: Long): List<ParticipantForm> {
        val alreadySeated = participants.any { it.playerId == playerId && it.turnOrder != null }
        val nextSeat = (participants.mapNotNull { it.turnOrder }.maxOrNull() ?: 0) + 1
        return normalise(
            participants.map { participant ->
                when {
                    participant.playerId != playerId -> participant
                    alreadySeated -> participant.copy(turnOrder = null)
                    else -> participant.copy(turnOrder = nextSeat)
                }
            }
        )
    }

    /** Forgets the order entirely, for when it was recorded wrongly. */
    fun clear(participants: List<ParticipantForm>): List<ParticipantForm> = participants.map { it.copy(turnOrder = null) }

    /**
     * Records only who went first, which is all the quick sheet asks for. A null player
     * id leaves the table with no first player, which is a perfectly ordinary answer.
     */
    fun firstOnly(participants: List<ParticipantForm>, playerId: Long?): List<ParticipantForm> =
        participants.map { it.copy(turnOrder = if (it.playerId == playerId) 1 else null) }
}

/** The two players a given player sat between, read round the table. */
data class Neighbours(val anticlockwise: ParticipantForm, val clockwise: ParticipantForm)

/**
 * Keeps the seating on a form coherent, and works out who ended up next to whom.
 *
 * Seats are renumbered into a run starting at 1 by the same rule the turn order uses,
 * and for the same reason: a table assembled from parts, or edited after the fact, must
 * not end up with a gap where somebody used to sit or with two people in one chair.
 *
 * Where this parts company with [TurnOrder] is what a partial answer is worth. A partial
 * turn order is real information -- "Aina started" is the common case and stands on its
 * own. A partial seating is not: the question a seating answers is who was *beside*
 * whom, and an unseated player may well have been sitting between two seated ones, so
 * every adjacency in a half-filled ring is a guess. Neighbours are therefore reported
 * only once the whole table has a chair, and withheld rather than approximated until
 * then.
 */
object Seating {

    /** Closes gaps and breaks ties, leaving unseated players unseated. */
    fun normalise(participants: List<ParticipantForm>): List<ParticipantForm> = participants.renumber(
        position = { it.seat },
        reseat = { participant, seat -> participant.copy(seat = seat) }
    )

    /**
     * Seats a player in the next chair round, or takes one out of the ring.
     *
     * The same tap-in-sequence interaction the turn order uses: going round the table
     * naming people builds the arrangement, and naming one again stands them up while
     * everybody after them shuffles along one chair.
     */
    fun toggle(participants: List<ParticipantForm>, playerId: Long): List<ParticipantForm> {
        val alreadySeated = participants.any { it.playerId == playerId && it.seat != null }
        val nextSeat = (participants.mapNotNull { it.seat }.maxOrNull() ?: 0) + 1
        return normalise(
            participants.map { participant ->
                when {
                    participant.playerId != playerId -> participant
                    alreadySeated -> participant.copy(seat = null)
                    else -> participant.copy(seat = nextSeat)
                }
            }
        )
    }

    /** Forgets the arrangement entirely, for when it was recorded wrongly. */
    fun clear(participants: List<ParticipantForm>): List<ParticipantForm> = participants.map { it.copy(seat = null) }

    /**
     * Whether the ring closes: everybody at the table has a chair, and there are at
     * least two of them.
     *
     * A solo play is excluded rather than special-cased later. One player in a ring is
     * their own neighbour on both sides, which is arithmetically true and worth nothing.
     */
    fun isComplete(participants: List<ParticipantForm>): Boolean = participants.size >= 2 &&
        participants.all { it.seat != null }

    /**
     * Who each player sat between, or nothing at all if the ring does not close.
     *
     * The wrap is the point: the player in the last chair is sitting next to the player
     * in the first, which is exactly the adjacency a turn order does not have. At a
     * table of two both sides are the same person, which is not a degenerate case but
     * the truth about a two-player game -- and why 7 Wonders starts at three.
     */
    fun neighbours(participants: List<ParticipantForm>): Map<Long, Neighbours> {
        if (!isComplete(participants)) return emptyMap()
        val ring = participants.sortedBy { it.seat }
        return ring.mapIndexed { index, participant ->
            participant.playerId to Neighbours(
                anticlockwise = ring[(index - 1 + ring.size) % ring.size],
                clockwise = ring[(index + 1) % ring.size]
            )
        }.toMap()
    }
}

/**
 * Renumbers whichever position column is passed in into a run starting at 1, closing
 * gaps and breaking ties, and leaves rows holding null holding null.
 *
 * Shared by the turn order and the seating because it is one rule, not two that happen
 * to look alike: both are positions the user builds by tapping and then edits, and both
 * break in the same way when a player in the middle is removed.
 */
private fun List<ParticipantForm>.renumber(
    position: (ParticipantForm) -> Int?,
    reseat: (ParticipantForm, Int?) -> ParticipantForm
): List<ParticipantForm> {
    val renumbered = filter { position(it) != null }
        // sortedBy is stable, so two rows that somehow claim the same position keep the
        // order the form holds them in rather than swapping about.
        .sortedBy(position)
        .mapIndexed { index, participant -> participant.playerId to index + 1 }
        .toMap()
    return map { reseat(it, renumbered[it.playerId]) }
}
