package com.boardgamenation.tracker.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.AchievementUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AchievementsUiState(val achievements: List<AchievementUi> = emptyList(), val unlocked: Int = 0, val total: Int = 0)

@HiltViewModel
class AchievementsViewModel @Inject constructor(repository: AchievementRepository) : ViewModel() {

    val uiState: StateFlow<AchievementsUiState> = combine(
        repository.observeAchievements(),
        repository.observeUnlockedCount(),
        repository.observeTotalCount()
    ) { achievements, unlocked, total ->
        AchievementsUiState(achievements, unlocked, total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(onBack: () -> Unit, viewModel: AchievementsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.achievements_title)) },
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
        Column(Modifier.padding(padding)) {
            Text(
                text = pluralStringResource(
                    R.plurals.achievements_progress,
                    state.total,
                    state.unlocked,
                    state.total
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            // Grouped by category so a long list reads as sections rather than a wall.
            val grouped = state.achievements.groupBy { it.category }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (category, items) ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(items.size, key = { items[it].code }) { index ->
                        AchievementTile(items[index])
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementTile(achievement: AchievementUi) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (achievement.isUnlocked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            // A grid row is as tall as its tallest tile, so the name and the description
            // together hold room for their full line counts whether or not they need them.
            //
            // Reserved across the pair rather than on each of them: reserving it on the
            // name left a one-line name sitting above an empty line, which read as a gap
            // between the name and the description rather than as the slack it was. Held
            // by the pair, the slack collects under the description, where the tile has
            // space to give, and the description stays where it belongs -- one gap under
            // the name, wherever the name ends.
            //
            // A minimum, not a fixed height. A fixed one is a maximum too, and a name that
            // needed its second line was measured against a box that had rounded a fraction
            // of a pixel off the two it reserved -- so the second line did not fit and the
            // name was ellipsised onto one instead of wrapping.
            val nameStyle = MaterialTheme.typography.titleSmall
            val descriptionStyle = MaterialTheme.typography.bodySmall
            val density = LocalDensity.current
            val nameLineHeight = with(density) { nameStyle.lineHeight.toDp() }
            val descriptionLineHeight = with(density) { descriptionStyle.lineHeight.toDp() }
            Column(
                Modifier.heightIn(
                    min = nameLineHeight * NAME_LINES +
                        NameDescriptionGap +
                        descriptionLineHeight * DESCRIPTION_LINES
                )
            ) {
                // Only as tall as the name really is, so centring puts the icon on the
                // middle of the name itself -- on the single line, or between the two.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (achievement.isUnlocked) {
                            Icons.Filled.EmojiEvents
                        } else {
                            Icons.Filled.Lock
                        },
                        contentDescription = stringResource(
                            if (achievement.isUnlocked) {
                                R.string.cd_achievement_unlocked
                            } else {
                                R.string.cd_achievement_locked
                            }
                        ),
                        tint = if (achievement.isUnlocked) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (achievement.isSecret) {
                            stringResource(R.string.achievements_hidden_name)
                        } else {
                            achievement.name
                        },
                        style = nameStyle,
                        maxLines = NAME_LINES,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(NameDescriptionGap))
                Text(
                    text = if (achievement.isSecret) {
                        stringResource(R.string.achievements_hidden_description)
                    } else {
                        achievement.description
                    },
                    style = descriptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))

            // Every tile ends on a bar and a single status line, so the footer is the same
            // height whatever state the achievement is in. A hidden one still shows the
            // bar: the secret is what it is for, not how close you are.
            LinearProgressIndicator(
                progress = { if (achievement.isUnlocked) 1f else achievement.progress.fraction },
                color = if (achievement.isUnlocked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    achievement.isUnlocked -> stringResource(
                        R.string.achievements_unlocked_on,
                        achievement.unlockedAt?.let { DateUtils.epochMillisToIso(it) }.orEmpty()
                    )

                    achievement.progress.target > 0 -> stringResource(
                        R.string.achievements_progress_value,
                        formatValue(achievement.progress.current),
                        formatValue(achievement.progress.target)
                    )

                    else -> stringResource(R.string.achievements_locked)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (achievement.isUnlocked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Lines a tile reserves for the name, so every tile in a row is the same height. */
private const val NAME_LINES = 2

/** Lines a tile reserves for the description, for the same reason. */
private const val DESCRIPTION_LINES = 3

/** The one gap between a tile's name and its description, however long either runs. */
private val NameDescriptionGap = 4.dp

/** Whole numbers stay whole; hours and rates keep one decimal. */
private fun formatValue(value: Double): String = if (value % 1.0 == 0.0) {
    value.roundToInt().toString()
} else {
    String.format(Locale.getDefault(), "%.1f", value)
}
