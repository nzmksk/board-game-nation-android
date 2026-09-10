package com.boardgamenation.tracker.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.ui.components.BottomBarGap
import com.boardgamenation.tracker.ui.components.ConfirmDialog
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.GameThumbnail
import com.boardgamenation.tracker.ui.components.LoadingRows
import com.boardgamenation.tracker.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(onOpenSession: (Long) -> Unit, onNewSession: () -> Unit, viewModel: SessionListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Discarding throws away the only record of how long the play took, and the row puts
    // that one tap away from the button that keeps it, so it asks first.
    var discarding by remember { mutableStateOf<SessionListItem?>(null) }

    discarding?.let { draft ->
        ConfirmDialog(
            title = stringResource(R.string.session_draft_discard_confirm_title),
            body = stringResource(R.string.session_draft_discard_confirm_body, draft.gameTitle),
            confirmLabel = stringResource(R.string.session_draft_discard),
            destructive = true,
            onConfirm = {
                viewModel.discardDraft(draft.id)
                discarding = null
            },
            onDismiss = { discarding = null }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sessions_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSession,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.session_edit_new)) }
            )
        }
    ) { padding ->
        // The list runs to the bottom of the screen, so it needs the same gap above the
        // tab row that every other top-level destination keeps.
        Column(Modifier.padding(padding).padding(bottom = BottomBarGap)) {
            FilterRow(
                state = state,
                onGame = viewModel::setGame,
                onPlayer = viewModel::setPlayer,
                onClear = viewModel::clearFilters
            )

            if (state.isLoading) {
                LoadingRows()
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    // A timed play nobody has saved is work in progress, so it leads the
                    // tab rather than sitting in date order among the finished plays --
                    // its date is only whenever the clock happened to be started.
                    if (state.drafts.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.sessions_drafts_header))
                        }
                        items(state.drafts, key = { DRAFT_KEY + it.id }) { draft ->
                            DraftRow(
                                draft = draft,
                                onResume = { onOpenSession(draft.id) },
                                onDiscard = { discarding = draft }
                            )
                        }
                    }

                    if (state.sessions.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(
                                    if (state.filter.gameId != null || state.filter.playerId != null) {
                                        R.string.sessions_empty_filtered
                                    } else {
                                        R.string.sessions_empty
                                    }
                                ),
                                icon = Icons.AutoMirrored.Filled.List
                            )
                        }
                    } else {
                        items(state.sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                showGameTitle = true,
                                onClick = { onOpenSession(session.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(state: SessionListUiState, onGame: (Long?) -> Unit, onPlayer: (Long?) -> Unit, onClear: () -> Unit) {
    var gameMenu by remember { mutableStateOf(false) }
    var playerMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val filteredGame = state.games.firstOrNull { it.id == state.filter.gameId }
        val filteredPlayer = state.players.firstOrNull { it.id == state.filter.playerId }

        if (state.filter.gameId != null || state.filter.playerId != null) {
            FilterChip(
                selected = true,
                onClick = onClear,
                label = { Text(stringResource(R.string.action_clear)) },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) }
            )
        }

        Box {
            FilterChip(
                selected = filteredGame != null,
                onClick = { gameMenu = true },
                label = {
                    Text(filteredGame?.title ?: stringResource(R.string.session_filter_any_game))
                }
            )
            DropdownMenu(expanded = gameMenu, onDismissRequest = { gameMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_filter_any_game)) },
                    onClick = {
                        onGame(null)
                        gameMenu = false
                    }
                )
                state.games.forEach { game ->
                    DropdownMenuItem(
                        text = { Text(game.title) },
                        onClick = {
                            onGame(game.id)
                            gameMenu = false
                        }
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = filteredPlayer != null,
                onClick = { playerMenu = true },
                label = {
                    Text(filteredPlayer?.name ?: stringResource(R.string.session_filter_any_player))
                }
            )
            DropdownMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_filter_any_player)) },
                    onClick = {
                        onPlayer(null)
                        playerMenu = false
                    }
                )
                state.players.forEach { player ->
                    DropdownMenuItem(
                        text = { Text(player.name) },
                        onClick = {
                            onPlayer(player.id)
                            playerMenu = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * A draft and a logged play can share an id, and the list holds both, so the draft half
 * of it is prefixed rather than left to collide.
 */
private const val DRAFT_KEY = "draft-"

/**
 * A draft: the game and what the clock managed to record, with the two things left to
 * decide about it. Tinted apart from the logged plays below it, because it is not one.
 */
@Composable
private fun DraftRow(draft: SessionListItem, onResume: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onResume)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameThumbnail(path = draft.thumbnailPath, title = draft.gameTitle, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = draft.gameTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${draft.playedOn} · ${DurationFormat.minutes(draft.durationMinutes)}" +
                            " · ${pluralStringResource(
                                R.plurals.unit_players,
                                draft.playerCount,
                                draft.playerCount
                            )}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.session_draft_unsaved),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.session_draft_discard))
                }
                FilledTonalButton(onClick = onResume) {
                    Text(stringResource(R.string.session_draft_save))
                }
            }
        }
    }
}

/** Shared by the session list, the dashboard, and a game's play history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionRow(session: SessionListItem, showGameTitle: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showGameTitle) {
                GameThumbnail(
                    path = session.thumbnailPath,
                    title = session.gameTitle,
                    size = 44.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showGameTitle) {
                    Text(
                        text = session.gameTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${session.playedOn} · ${DurationFormat.minutes(session.durationMinutes)}" +
                        " · ${pluralStringResource(
                            R.plurals.unit_players,
                            session.playerCount,
                            session.playerCount
                        )}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // A result means little without what it was played at -- a co-op's
                // level, a competitive game's scenario or board side -- so the
                // configuration rides along with the outcome rather than below it.
                val outcome = withMode(
                    outcome = when {
                        session.isCooperative && session.coopWon ->
                            stringResource(R.string.session_coop_win)

                        session.isCooperative ->
                            stringResource(R.string.session_coop_loss)

                        // A side won, so the side is the result; who was on it is detail.
                        !session.winningTeam.isNullOrBlank() ->
                            stringResource(R.string.session_team_won, session.winningTeam)

                        !session.winnerNames.isNullOrBlank() ->
                            stringResource(R.string.session_winner, session.winnerNames)

                        else -> null
                    },
                    mode = session.mode
                )
                outcome?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Flows rather than a Row: these badges wrap onto a second line on a
                // narrow screen instead of the last one being clipped off the card.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // First, and the only badge in the app coloured as an error: it does
                    // not qualify the result on the row above, it withdraws it. Nothing
                    // else on the screen says so, because every statistic has already
                    // dropped this play without leaving a mark.
                    if (session.isInvalid) {
                        Text(
                            text = stringResource(R.string.session_invalid),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // Deliberately not styled like "Unfinished" next to it: the game did
                    // finish, it just finished the moment a condition was met.
                    session.endReason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    session.firstPlayerName?.let { name ->
                        Text(
                            text = stringResource(R.string.session_first_player, name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (session.isIncomplete) {
                        Text(
                            text = stringResource(R.string.session_incomplete),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (session.isTeachingGame) {
                        Text(
                            text = stringResource(R.string.session_teaching),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * "We won · Level 12", or just the result when no configuration was recorded -- and the
 * configuration alone when nothing else marked the play, which is the ordinary shape of a
 * competitive session logged without winners.
 */
private fun withMode(outcome: String?, mode: String?): String? = when {
    mode.isNullOrBlank() -> outcome
    outcome == null -> mode
    else -> "$outcome · $mode"
}
