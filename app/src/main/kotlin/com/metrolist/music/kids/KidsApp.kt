/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kids

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.music.R
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The only UI a child sees: the playlists saved in the signed-in account, their songs,
 * and basic playback controls. No search, history, video, queue editing or settings.
 */
@Composable
fun KidsApp(
    playerConnection: PlayerConnection?,
    onUnlockParentMode: () -> Unit,
    viewModel: KidsViewModel = hiltViewModel(),
) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlist by viewModel.playlist.collectAsState()

    val emptyMetadata = remember { MutableStateFlow<MediaMetadata?>(null) }
    val mediaMetadata by (playerConnection?.mediaMetadata ?: emptyMetadata).collectAsState()

    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlaylistTitle by rememberSaveable { mutableStateOf("") }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showParentGate by remember { mutableStateOf(false) }

    LaunchedEffect(openPlaylistId) {
        openPlaylistId?.let(viewModel::openPlaylist)
    }

    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
    BackHandler(enabled = !showNowPlaying && openPlaylistId != null) { openPlaylistId = null }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val currentId = openPlaylistId
                if (currentId == null) {
                    PlaylistsScreen(
                        isLoggedIn = isLoggedIn,
                        state = playlists,
                        onRetry = viewModel::loadPlaylists,
                        onOpen = {
                            openPlaylistTitle = it.title
                            openPlaylistId = it.id
                        },
                        onParentMode = { showParentGate = true },
                    )
                } else {
                    PlaylistScreen(
                        title = openPlaylistTitle,
                        state = playlist,
                        currentSongId = mediaMetadata?.id,
                        onBack = { openPlaylistId = null },
                        onRetry = { viewModel.openPlaylist(currentId, forceReload = true) },
                        onPlay = { page, index, shuffle ->
                            playerConnection?.let { playPlaylist(it, page, index, shuffle) }
                        },
                    )
                }
            }

            val metadata = mediaMetadata
            if (playerConnection != null && metadata != null) {
                MiniPlayer(
                    playerConnection = playerConnection,
                    metadata = metadata,
                    onClick = { showNowPlaying = true },
                )
            }
        }

        AnimatedVisibility(
            visible = showNowPlaying && playerConnection != null && mediaMetadata != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            val metadata = mediaMetadata
            if (playerConnection != null && metadata != null) {
                NowPlaying(
                    playerConnection = playerConnection,
                    metadata = metadata,
                    onClose = { showNowPlaying = false },
                )
            }
        }

        if (showParentGate) {
            ParentGateDialog(
                onDismiss = { showParentGate = false },
                onUnlocked = {
                    showParentGate = false
                    onUnlockParentMode()
                },
            )
        }
    }
}

private fun playPlaylist(
    playerConnection: PlayerConnection,
    page: PlaylistPage,
    startIndex: Int,
    shuffle: Boolean,
) {
    if (page.songs.isEmpty()) return
    playerConnection.playQueue(
        YouTubePlaylistQueue(
            playlistId = page.playlist.id,
            playlistTitle = page.playlist.title,
            initialSongs = if (shuffle) page.songs.shuffled() else page.songs,
            initialContinuation = null,
            startIndex = if (shuffle) 0 else startIndex,
        ),
    )
}

@Composable
private fun TopBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
    ) {
        if (navigationIcon != null) {
            navigationIcon()
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
        )
        actions()
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    iconRes: Int,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PlaylistsScreen(
    isLoggedIn: Boolean?,
    state: LoadState<List<PlaylistItem>>,
    onRetry: () -> Unit,
    onOpen: (PlaylistItem) -> Unit,
    onParentMode: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.kids_my_music),
            actions = {
                if (isLoggedIn == true) {
                    IconButton(onClick = onRetry) {
                        Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.kids_retry))
                    }
                }
                IconButton(onClick = onParentMode) {
                    Icon(painterResource(R.drawable.lock), contentDescription = stringResource(R.string.kids_parent_mode))
                }
            },
        )

        when {
            isLoggedIn == null -> Loading()
            isLoggedIn == false -> CenteredMessage(stringResource(R.string.kids_ask_parent_setup), R.drawable.account)
            state is LoadState.Loading -> Loading()
            state is LoadState.Error ->
                CenteredMessage(
                    text = stringResource(R.string.kids_load_error),
                    iconRes = R.drawable.error,
                    actionLabel = stringResource(R.string.kids_retry),
                    onAction = onRetry,
                )
            state is LoadState.Ready && state.value.isEmpty() ->
                CenteredMessage(
                    text = stringResource(R.string.kids_no_playlists),
                    iconRes = R.drawable.queue_music,
                    actionLabel = stringResource(R.string.kids_retry),
                    onAction = onRetry,
                )
            state is LoadState.Ready ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.value, key = { it.id }) { item ->
                        PlaylistCard(item = item, onClick = { onOpen(item) })
                    }
                }
        }
    }
}

@Composable
private fun PlaylistCard(
    item: PlaylistItem,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(4.dp),
    ) {
        Artwork(
            url = item.thumbnail?.resize(544, 544),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            cornerRadius = 16,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.songCountText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PlaylistScreen(
    title: String,
    state: LoadState<PlaylistPage>,
    currentSongId: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (page: PlaylistPage, index: Int, shuffle: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = title,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        when (state) {
            LoadState.Loading -> Loading()
            LoadState.Error ->
                CenteredMessage(
                    text = stringResource(R.string.kids_load_error),
                    iconRes = R.drawable.error,
                    actionLabel = stringResource(R.string.kids_retry),
                    onAction = onRetry,
                )
            is LoadState.Ready -> {
                val page = state.value
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                        ) {
                            Artwork(
                                url = page.playlist.thumbnail?.resize(544, 544),
                                cornerRadius = 20,
                                modifier = Modifier.size(200.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = pluralCount(page.songs.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { onPlay(page, 0, false) },
                                    enabled = page.songs.isNotEmpty(),
                                ) {
                                    Icon(painterResource(R.drawable.play), contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.play))
                                }
                                FilledTonalButton(
                                    onClick = { onPlay(page, 0, true) },
                                    enabled = page.songs.isNotEmpty(),
                                ) {
                                    Icon(painterResource(R.drawable.shuffle), contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.kids_shuffle))
                                }
                            }
                        }
                    }

                    itemsIndexed(page.songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
                        SongRow(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            onClick = { onPlay(page, index, false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun pluralCount(count: Int): String = stringResource(R.string.kids_song_count, count)

@Composable
private fun SongRow(
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
                ).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Artwork(url = song.thumbnail.resize(120, 120), modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberProgress(
    playerConnection: PlayerConnection,
    songId: String,
): Pair<Long, Long> {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playerConnection, songId) {
        while (true) {
            val player = playerConnection.player
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }
    return position to duration
}

@Composable
private fun MiniPlayer(
    playerConnection: PlayerConnection,
    metadata: MediaMetadata,
    onClick: () -> Unit,
) {
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val (position, duration) = rememberProgress(playerConnection, metadata.id)

    Surface(
        tonalElevation = 3.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column {
            HorizontalDivider()
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Artwork(url = metadata.thumbnailUrl?.resize(120, 120), modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = metadata.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metadata.artists.joinToString { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = playerConnection::togglePlayPause) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = null,
                    )
                }
                IconButton(onClick = playerConnection::seekToNext, enabled = canSkipNext) {
                    Icon(painterResource(R.drawable.skip_next), contentDescription = null)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun NowPlaying(
    playerConnection: PlayerConnection,
    metadata: MediaMetadata,
    onClose: () -> Unit,
) {
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val (position, duration) = rememberProgress(playerConnection, metadata.id)
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.expand_more), contentDescription = null)
                }
                Text(
                    text = stringResource(R.string.kids_now_playing),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                Artwork(
                    url = metadata.thumbnailUrl?.resize(720, 720),
                    cornerRadius = 24,
                    modifier =
                        Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = metadata.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            Slider(
                value = dragPosition ?: if (duration > 0) position.toFloat() else 0f,
                valueRange = 0f..(duration.takeIf { it > 0 }?.toFloat() ?: 1f),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { playerConnection.seekTo(it.toLong()) }
                    dragPosition = null
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(dragPosition?.toLong() ?: position),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = {
                    playerConnection.player.shuffleModeEnabled = !shuffleEnabled
                }) {
                    Icon(
                        painterResource(if (shuffleEnabled) R.drawable.shuffle_on else R.drawable.shuffle),
                        contentDescription = stringResource(R.string.kids_shuffle),
                    )
                }
                IconButton(
                    onClick = playerConnection::seekToPrevious,
                    enabled = canSkipPrevious,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(painterResource(R.drawable.skip_previous), contentDescription = null, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = playerConnection::togglePlayPause,
                    modifier = Modifier.size(80.dp),
                ) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(
                    onClick = playerConnection::seekToNext,
                    enabled = canSkipNext,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(painterResource(R.drawable.skip_next), contentDescription = null, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                    Icon(
                        painterResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ),
                        contentDescription = null,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private const val MAX_PIN_ATTEMPTS = 5
private const val LOCKOUT_MS = 60_000L

@Composable
private fun ParentGateDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var needsSetup by remember { mutableStateOf<Boolean?>(null) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { needsSetup = ParentLock.needsSetup(context) }

    val setup = needsSetup ?: return
    val pinValid = pin.length in 4..8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (setup) R.string.kids_create_pin_title else R.string.kids_parent_mode))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (setup) R.string.kids_create_pin_desc else R.string.kids_enter_pin_desc))
                PinField(value = pin, label = stringResource(R.string.kids_pin), onValueChange = { pin = it })
                if (setup) {
                    PinField(
                        value = confirmPin,
                        label = stringResource(R.string.kids_confirm_pin),
                        onValueChange = { confirmPin = it },
                    )
                }
                errorRes?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pinValid,
                onClick = {
                    scope.launch {
                        if (setup) {
                            if (pin != confirmPin) {
                                errorRes = R.string.kids_pin_mismatch
                            } else if (ParentLock.setPin(context, pin)) {
                                onUnlocked()
                            }
                            return@launch
                        }
                        if (System.currentTimeMillis() < lockedUntil) {
                            errorRes = R.string.kids_too_many_attempts
                            return@launch
                        }
                        if (ParentLock.verify(context, pin)) {
                            onUnlocked()
                        } else {
                            pin = ""
                            failedAttempts++
                            if (failedAttempts >= MAX_PIN_ATTEMPTS) {
                                failedAttempts = 0
                                lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                                errorRes = R.string.kids_too_many_attempts
                            } else {
                                errorRes = R.string.kids_wrong_pin
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.kids_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kids_cancel)) }
        },
    )
}

@Composable
private fun PinField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.length <= 8 && input.all(Char::isDigit)) onValueChange(input) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
