package com.boardgamenation.tracker.ui.gameedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.entity.GameCostEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One "other cost" line while it is being typed. The amount is a string for the same
 * reason every other number on this form is one.
 */
data class CostLine(val label: String = "", val amount: String = "")

/**
 * The edit form as plain text fields.
 *
 * Numbers are held as strings while being typed. A partially typed "12" is not an Int,
 * and coercing on every keystroke makes fields fight the user; parsing happens once, on
 * save, where a bad value can be reported.
 */
data class GameEditState(
    val id: Long = 0,
    val title: String = "",
    val yearPublished: String = "",
    val minPlayers: String = "",
    val maxPlayers: String = "",
    val minPlaytime: String = "",
    val maxPlaytime: String = "",
    val weight: String = "",
    val bggRating: String = "",
    val dateAdded: String = "",
    val price: String = "",
    val currency: String = "MYR",
    val purchaseNote: String = "",
    val otherCosts: List<CostLine> = emptyList(),
    val status: GameStatus = GameStatus.OWNED,
    val wishlistPriority: Int? = null,
    val isExpansion: Boolean = false,

    /**
     * Every game this expansion expands, not just one. Ticket to Ride: France goes on top
     * of Ticket to Ride and of Ticket to Ride: Europe, and a single answer had to drop one.
     */
    val baseGameIds: List<Long> = emptyList(),
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
    val highScoreWins: Boolean = true,
    val notes: String = "",
    val mechanics: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val designers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    /**
     * What can be picked in [baseGameIds]. Expansions are among them: an expansion of an
     * expansion is an ordinary box, and Legends of the Sea Robbers goes on Catan: Seafarers.
     */
    val baseGameOptions: List<GameEntity> = emptyList(),
    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val titleError: Boolean = false
) {
    val canSave: Boolean get() = title.isNotBlank() && !isSaving

    /**
     * The cost lines worth storing. A line is what it is called: an amount typed against
     * no label is not a cost anybody could read back later, and a line left empty is the
     * add button pressed one time too many. Both are dropped, and both are visibly empty
     * on the form before save, so nothing disappears that was not already blank on screen.
     */
    fun costEntities(): List<GameCostEntity> = otherCosts
        .filter { it.label.isNotBlank() }
        .map { GameCostEntity(gameId = id, label = it.label.trim(), amount = it.amount.toDoubleOrNull() ?: 0.0) }
}

@HiltViewModel
class GameEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: AppClock
) : ViewModel() {

    private val gameId: Long = savedStateHandle.toRoute<Route.GameEdit>().gameId

    private val _state = MutableStateFlow(GameEditState())
    val state: StateFlow<GameEditState> = _state.asStateFlow()

    private val _saved = MutableStateFlow<Long?>(null)
    val saved: StateFlow<Long?> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val defaultCurrency = settingsRepository.settings.first().defaultCurrency
            val options = gameRepository.getBaseGameCandidates(gameId)

            if (gameId == 0L) {
                _state.value = GameEditState(
                    dateAdded = DateUtils.toIso(clock.today()),
                    currency = defaultCurrency,
                    baseGameOptions = options,
                    isNew = true
                )
            } else {
                val game = gameRepository.getGame(gameId)
                val tags = gameRepository.observeTags(gameId).first()
                val costs = gameRepository.getCosts(gameId)
                val bases = gameRepository.getBaseGamesOf(gameId)
                if (game != null) {
                    _state.value = GameEditState(
                        id = game.id,
                        title = game.title,
                        yearPublished = game.yearPublished?.toString().orEmpty(),
                        minPlayers = game.minPlayers?.toString().orEmpty(),
                        maxPlayers = game.maxPlayers?.toString().orEmpty(),
                        minPlaytime = game.minPlaytimeMinutes?.toString().orEmpty(),
                        maxPlaytime = game.maxPlaytimeMinutes?.toString().orEmpty(),
                        weight = game.weight?.toString().orEmpty(),
                        bggRating = game.bggRating?.toString().orEmpty(),
                        dateAdded = game.dateAdded,
                        price = game.price?.toString().orEmpty(),
                        currency = game.currency,
                        purchaseNote = game.purchaseNote.orEmpty(),
                        otherCosts = costs.map { CostLine(it.label, it.amount.toString()) },
                        status = game.status,
                        wishlistPriority = game.wishlistPriority,
                        isExpansion = game.isExpansion,
                        baseGameIds = bases.map { it.id },
                        scoringMode = game.scoringMode,
                        highScoreWins = game.highScoreWins,
                        notes = game.notes.orEmpty(),
                        mechanics = tags.filter { it.kind == TagKind.MECHANIC }.map { it.name },
                        categories = tags.filter { it.kind == TagKind.CATEGORY }.map { it.name },
                        designers = tags.filter { it.kind == TagKind.DESIGNER }.map { it.name },
                        publishers = tags.filter { it.kind == TagKind.PUBLISHER }.map { it.name },
                        baseGameOptions = options,
                        isNew = false
                    )
                }
            }
        }
    }

    fun update(block: (GameEditState) -> GameEditState) {
        _state.value = block(_state.value)
    }

    /**
     * One base game on or off, since the picker is a set of checkboxes rather than a
     * single choice. The order is the order they were ticked in; the list is read back
     * sorted by title, so nothing depends on it.
     */
    fun toggleBaseGame(id: Long) {
        val current = _state.value.baseGameIds
        _state.value = _state.value.copy(
            baseGameIds = if (id in current) current - id else current + id
        )
    }

    /**
     * Add and remove are written once over the kind rather than once per list. Four
     * kinds would otherwise mean eight near-identical methods.
     */
    fun addTag(kind: TagKind, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.mapTags(kind) { (it + trimmed).distinct() }
    }

    fun removeTag(kind: TagKind, name: String) {
        _state.value = _state.value.mapTags(kind) { it - name }
    }

    private fun GameEditState.mapTags(kind: TagKind, block: (List<String>) -> List<String>): GameEditState = when (kind) {
        TagKind.MECHANIC -> copy(mechanics = block(mechanics))
        TagKind.CATEGORY -> copy(categories = block(categories))
        TagKind.DESIGNER -> copy(designers = block(designers))
        TagKind.PUBLISHER -> copy(publishers = block(publishers))
        TagKind.CUSTOM -> this
    }

    /**
     * Adds an empty line for the user to fill in, rather than asking for a label in a
     * dialog first. The form is a list of fields everywhere else and this is one more.
     */
    fun addCost() {
        _state.value = _state.value.copy(otherCosts = _state.value.otherCosts + CostLine())
    }

    /**
     * Both edits check the index first. A row that has just been removed can still hand
     * back one last callback from the list that drew it, and an unchecked index would
     * turn that into a crash on the way out.
     */
    fun updateCost(index: Int, line: CostLine) {
        val costs = _state.value.otherCosts
        if (index !in costs.indices) return
        _state.value = _state.value.copy(
            otherCosts = costs.mapIndexed { i, existing -> if (i == index) line else existing }
        )
    }

    fun removeCost(index: Int) {
        val costs = _state.value.otherCosts
        if (index !in costs.indices) return
        _state.value = _state.value.copy(otherCosts = costs.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        val current = _state.value
        if (current.title.isBlank()) {
            _state.value = current.copy(titleError = true)
            return
        }
        _state.value = current.copy(isSaving = true, titleError = false)

        viewModelScope.launch {
            val now = clock.nowMillis()
            // Read once: bgg_id and the cached cover belong to the stored row, not to the
            // form, and must survive an edit that never showed them.
            val existing = if (current.isNew) null else gameRepository.getGame(current.id)
            val entity = GameEntity(
                id = current.id,
                bggId = existing?.bggId,
                title = current.title.trim(),
                yearPublished = current.yearPublished.toIntOrNull(),
                minPlayers = current.minPlayers.toIntOrNull(),
                maxPlayers = current.maxPlayers.toIntOrNull(),
                minPlaytimeMinutes = current.minPlaytime.toIntOrNull(),
                maxPlaytimeMinutes = current.maxPlaytime.toIntOrNull(),
                weight = current.weight.toDoubleOrNull(),
                bggRating = current.bggRating.toDoubleOrNull(),
                thumbnailPath = existing?.thumbnailPath,
                dateAdded = current.dateAdded.ifBlank { DateUtils.toIso(clock.today()) },
                price = current.price.toDoubleOrNull(),
                currency = current.currency.ifBlank { "MYR" },
                purchaseNote = current.purchaseNote.trim().ifBlank { null },
                status = current.status,
                wishlistPriority = current.wishlistPriority
                    .takeIf { current.status == GameStatus.WISHLIST },
                isExpansion = current.isExpansion,
                scoringMode = current.scoringMode,
                highScoreWins = current.highScoreWins,
                notes = current.notes.trim().ifBlank { null },
                createdAt = now,
                updatedAt = now
            )

            val mechanicIds = gameRepository.resolveTags(current.mechanics, TagKind.MECHANIC)
            val categoryIds = gameRepository.resolveTags(current.categories, TagKind.CATEGORY)
            val designerIds = gameRepository.resolveTags(current.designers, TagKind.DESIGNER)
            val publisherIds = gameRepository.resolveTags(current.publishers, TagKind.PUBLISHER)
            val tagIds = mechanicIds + categoryIds + designerIds + publisherIds

            val id = if (current.isNew) {
                gameRepository.addGame(entity, tagIds)
            } else {
                gameRepository.updateGame(entity, tagIds)
                current.id
            }
            gameRepository.replaceCosts(id, current.costEntities())
            // Cleared rather than left alone when the toggle is off, so a game demoted
            // back to a base game does not keep pointing at what it used to expand.
            gameRepository.replaceBaseGames(
                id,
                if (current.isExpansion) current.baseGameIds else emptyList()
            )
            _saved.value = id
        }
    }
}
