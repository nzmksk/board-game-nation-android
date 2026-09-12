package com.boardgamenation.tracker.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.projection.FactionRecord
import com.boardgamenation.tracker.data.db.projection.FirstPlayerRecord
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.data.db.projection.RatingWithRubric
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.repository.DeleteGameOutcome
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.data.repository.SessionFilter
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GameDetailUiState(
    val game: GameEntity? = null,
    val aggregates: GameAggregates? = null,
    val tags: List<TagEntity> = emptyList(),
    val sessions: List<SessionListItem> = emptyList(),
    val expansions: List<GameEntity> = emptyList(),
    val ratings: List<RatingWithRubric> = emptyList(),

    /** Sleeves, inserts and the rest, in the order they were entered. */
    val costs: List<GameCostEntity> = emptyList(),

    /** Win rate per faction, best first. Empty until somebody records a faction. */
    val factions: List<FactionRecord> = emptyList(),

    /** How the first seat has fared here. Empty until a play names who started. */
    val firstPlayer: FirstPlayerRecord = FirstPlayerRecord(0, 0, null),

    val daysOnLoan: Long? = null,
    val isLoading: Boolean = true
) {
    val currentRating: RatingWithRubric? get() = ratings.firstOrNull()

    val accessoriesTotal: Double get() = costs.sumOf { it.amount }

    /**
     * What the game has actually taken, box and accessories together. Null where the
     * `game_costing` view is null: a game with no price and nothing spent on it is
     * unpriced rather than free, and saying "0.00" would be a claim nobody made.
     */
    val totalCost: Double?
        get() {
            val price = game?.price
            if (price == null && accessoriesTotal == 0.0) return null
            return (price ?: 0.0) + accessoriesTotal
        }

    /**
     * Cost per play is the metric that most changes buying behaviour, so it is computed
     * even when there is only one play: a game bought last week and played once has a
     * cost per play, and it is a large one.
     *
     * It divides [totalCost], not the price. A game whose insert cost half as much again
     * as the box has earned that back over rather fewer plays than the price implies.
     */
    val costPerPlay: Double?
        get() {
            val total = totalCost ?: return null
            val plays = aggregates?.playCount ?: 0
            return if (plays > 0) total / plays else null
        }

    /**
     * The lines that add up to [totalCost], for showing underneath it.
     *
     * The price leads because it is the one line every priced game has and nearly
     * always the largest; the accessories follow by label, since the order they were
     * typed in means nothing to anybody reading the list back, and alphabetical at
     * least tells you where to look for the sleeves.
     */
    val costBreakdown: List<CostBreakdownLine>
        get() {
            val price = game?.price?.let { listOf(CostBreakdownLine(null, it)) }.orEmpty()
            return price + costs
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .map { CostBreakdownLine(it.label, it.amount) }
        }
}

/**
 * One line of the cost breakdown.
 *
 * [label] is null for the price of the box itself, which is named by a string resource
 * rather than by anything the user typed and so cannot be resolved this far from the
 * screen.
 */
data class CostBreakdownLine(val label: String?, val amount: Double)

/** Whether deleting needs a confirmation, and what it would take with it. */
data class DeletePrompt(val sessionCount: Int, val expansionCount: Int)

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gameRepository: GameRepository,
    private val sessionRepository: SessionRepository,
    private val rubricRepository: RubricRepository,
    statsRepository: StatsRepository
) : ViewModel() {

    val gameId: Long = savedStateHandle.toRoute<Route.GameDetail>().gameId

    /**
     * The flows that do not fit in one combine, which takes five.
     *
     * Named rather than nested pairs and triples: a positional shape has to be counted
     * out to be read, and it miscasts silently the day somebody reorders it.
     */
    private data class Extras(
        val expansions: List<GameEntity>,
        val ratings: List<RatingWithRubric>,
        val factions: List<FactionRecord>,
        val firstPlayer: FirstPlayerRecord,
        val costs: List<GameCostEntity>
    )

    private val _deletePrompt = MutableStateFlow<DeletePrompt?>(null)
    val deletePrompt: StateFlow<DeletePrompt?> = _deletePrompt

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted

    val uiState: StateFlow<GameDetailUiState> = combine(
        gameRepository.observeGame(gameId),
        gameRepository.observeAggregates(gameId),
        gameRepository.observeTags(gameId),
        sessionRepository.observeSessions(SessionFilter(gameId = gameId)),
        combine(
            gameRepository.observeExpansions(gameId),
            rubricRepository.observeRatingsFor(gameId),
            gameRepository.observeFactionRecords(gameId),
            statsRepository.firstPlayerRecord(gameId),
            gameRepository.observeCosts(gameId),
            ::Extras
        )
    ) { game, aggregates, tags, sessions, extras ->
        GameDetailUiState(
            game = game,
            aggregates = aggregates,
            tags = tags,
            sessions = sessions,
            expansions = extras.expansions,
            ratings = extras.ratings,
            costs = extras.costs,
            factions = extras.factions,
            firstPlayer = extras.firstPlayer,
            daysOnLoan = gameRepository.daysOnLoan(game?.lentDate),
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameDetailUiState())

    fun requestDelete() {
        viewModelScope.launch {
            when (val outcome = gameRepository.deleteGame(gameId, confirmed = false)) {
                is DeleteGameOutcome.NeedsConfirmation ->
                    _deletePrompt.value = DeletePrompt(outcome.sessionCount, outcome.expansionCount)

                DeleteGameOutcome.Deleted -> _deleted.value = true
            }
        }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            gameRepository.deleteGame(gameId, confirmed = true)
            _deletePrompt.value = null
            _deleted.value = true
        }
    }

    fun dismissDelete() {
        _deletePrompt.value = null
    }

    fun lend(person: String) {
        if (person.isBlank()) return
        viewModelScope.launch { gameRepository.lendGame(gameId, person) }
    }

    fun markReturned() {
        viewModelScope.launch { gameRepository.returnGame(gameId) }
    }
}
