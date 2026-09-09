package com.boardgamenation.tracker.ui.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.projection.GameWinRateRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.data.db.projection.PersonalBestRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.SessionFilter
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.ui.components.HorizontalBarChart
import com.boardgamenation.tracker.ui.components.KeyValueRow
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.components.StatTile
import com.boardgamenation.tracker.ui.components.currentLocale
import com.boardgamenation.tracker.ui.navigation.Route
import com.boardgamenation.tracker.ui.sessions.SessionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PlayerDetailState(
    val player: PlayerEntity? = null,
    val winRateByGame: List<GameWinRateRow> = emptyList(),
    val averageScores: List<LabelledValue> = emptyList(),
    val personalBests: List<PersonalBestRow> = emptyList(),
    val sessions: List<SessionListItem> = emptyList(),
    val plays: Int = 0,
    val wins: Int = 0
)

@HiltViewModel
class PlayerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    playerRepository: PlayerRepository,
    statsRepository: StatsRepository,
    sessionRepository: SessionRepository
) : ViewModel() {

    private val playerId: Long = savedStateHandle.toRoute<Route.PlayerDetail>().playerId

    /**
     * Everything the profile says about the player's record. Split from the session list
     * only because `combine` takes five flows at a time and this is six.
     */
    private val record = combine(
        playerRepository.observePlayer(playerId),
        statsRepository.winRateByGame(playerId),
        statsRepository.averageScoreByGame(playerId),
        statsRepository.personalBestByGame(playerId),
        statsRepository.standings()
    ) { player, winRates, scores, bests, standings ->
        val standing = standings.firstOrNull { it.playerId == playerId }
        PlayerDetailState(
            player = player,
            winRateByGame = winRates,
            averageScores = scores,
            personalBests = bests,
            plays = standing?.plays ?: 0,
            wins = standing?.wins ?: 0
        )
    }

    val state: StateFlow<PlayerDetailState> = combine(
        record,
        sessionRepository.observeSessions(SessionFilter(playerId = playerId))
    ) { state, sessions ->
        state.copy(sessions = sessions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerDetailState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDetailScreen(onBack: () -> Unit, onOpenSession: (Long) -> Unit, viewModel: PlayerDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.player?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        label = stringResource(R.string.stats_total_plays),
                        value = state.plays.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.game_detail_win_rate),
                        value = if (state.plays > 0) {
                            "${state.wins * 100 / state.plays}%"
                        } else {
                            "—"
                        },
                        supporting = "${state.wins}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.stats_win_rate_by_game)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HorizontalBarChart(
                        data = state.winRateByGame.map { it.title to it.winRate },
                        maxRows = state.winRateByGame.size,
                        // The rate on its own would rank a first-play fluke level with a
                        // game somebody has actually learnt; the play count is what tells
                        // the two apart.
                        valueLabels = state.winRateByGame.map {
                            stringResource(
                                R.string.stats_win_rate_over_plays,
                                it.winRate.roundToInt(),
                                it.plays
                            )
                        },
                        valueWidth = 72.dp,
                        scaleMax = 100.0
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.stats_average_score)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HorizontalBarChart(
                        data = state.averageScores.map { it.label to it.value },
                        valueFormatter = { String.format(locale, "%.1f", it) }
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.stats_personal_best)) }
            if (state.personalBests.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            } else {
                items(state.personalBests.size, key = { state.personalBests[it].gameId }) { index ->
                    val best = state.personalBests[index]
                    KeyValueRow(
                        label = best.title,
                        value = stringResource(
                            if (best.highScoreWins) {
                                R.string.stats_personal_best_value
                            } else {
                                R.string.stats_personal_best_value_low_wins
                            },
                            formatScore(best.bestScore, locale),
                            best.plays
                        )
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.sessions_title)) }
            items(state.sessions.size, key = { state.sessions[it].id }) { index ->
                SessionRow(
                    session = state.sessions[index],
                    showGameTitle = true,
                    onClick = { onOpenSession(state.sessions[index].id) }
                )
            }
        }
    }
}

/**
 * A score as it was entered. Whole numbers lose the decimal point -- most games are
 * scored in whole points and "92.0" reads as a measurement rather than a score -- and
 * anything else keeps one place, which is as fine as the app lets a score be entered.
 */
private fun formatScore(score: Double, locale: Locale): String = if (score % 1.0 == 0.0) {
    score.toLong().toString()
} else {
    String.format(locale, "%.1f", score)
}
