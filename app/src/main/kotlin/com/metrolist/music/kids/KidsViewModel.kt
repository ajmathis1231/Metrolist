/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kids

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.utils.completed
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data object Error : LoadState<Nothing>

    data class Ready<T>(val value: T) : LoadState<T>
}

data class KidsMessage(
    @StringRes val resId: Int,
    val arg: String? = null,
)

// Playlists created from the locked screen. Only these can be edited or deleted there;
// everything else in the library (the parent's playlists) stays read-only.
private val OwnPlaylistIdsKey = stringSetPreferencesKey("kidsOwnPlaylistIds")

@HiltViewModel
class KidsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        /** null until the stored account state has been read. */
        val isLoggedIn: StateFlow<Boolean?> =
            context.dataStore.data
                .map { it[InnerTubeCookieKey]?.contains("SAPISID") == true }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val _playlists = MutableStateFlow<LoadState<List<PlaylistItem>>>(LoadState.Loading)
        val playlists = _playlists.asStateFlow()

        private val _playlist = MutableStateFlow<LoadState<PlaylistPage>>(LoadState.Loading)
        val playlist = _playlist.asStateFlow()

        val ownPlaylistIds: StateFlow<Set<String>> =
            context.dataStore.data
                .map { it[OwnPlaylistIdsKey].orEmpty() }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        private val _messages = MutableSharedFlow<KidsMessage>(extraBufferCapacity = 4)
        val messages = _messages.asSharedFlow()

        private val _busy = MutableStateFlow(false)
        val busy = _busy.asStateFlow()

        private var loadedPlaylistId: String? = null
        private var playlistJob: Job? = null

        init {
            viewModelScope.launch {
                isLoggedIn.collect { loggedIn ->
                    if (loggedIn == true) loadPlaylists()
                }
            }
        }

        fun loadPlaylists() {
            viewModelScope.launch {
                _playlists.value = LoadState.Loading
                awaitSession()
                YouTube
                    .library("FEmusic_liked_playlists")
                    .completed()
                    .onSuccess { page ->
                        _playlists.value =
                            LoadState.Ready(
                                page.items
                                    .filterIsInstance<PlaylistItem>()
                                    // "SE" is the saved-podcast-episodes list, not music.
                                    .filterNot { it.id == "SE" || it.isPodcast },
                            )
                    }.onFailure {
                        reportException(it)
                        _playlists.value = LoadState.Error
                    }
            }
        }

        fun openPlaylist(
            playlistId: String,
            forceReload: Boolean = false,
        ) {
            if (!forceReload && playlistId == loadedPlaylistId && _playlist.value is LoadState.Ready) return
            loadedPlaylistId = playlistId
            playlistJob?.cancel()
            playlistJob =
                viewModelScope.launch {
                    _playlist.value = LoadState.Loading
                    awaitSession()
                    YouTube
                        .playlist(playlistId)
                        .completed()
                        .onSuccess { _playlist.value = LoadState.Ready(it) }
                        .onFailure {
                            reportException(it)
                            _playlist.value = LoadState.Error
                        }
                }
        }

        private suspend fun isOwn(playlistId: String): Boolean =
            playlistId in context.dataStore.data.first()[OwnPlaylistIdsKey].orEmpty()

        private fun runAction(block: suspend () -> Unit) {
            if (_busy.value) return
            viewModelScope.launch {
                _busy.value = true
                try {
                    awaitSession()
                    block()
                } finally {
                    _busy.value = false
                }
            }
        }

        /** Creates a playlist in the signed-in account, optionally adding [firstSong] to it. */
        fun createPlaylist(
            title: String,
            firstSong: SongItem? = null,
        ) {
            val name = title.trim().take(MAX_PLAYLIST_NAME)
            if (name.isEmpty()) return
            runAction {
                val playlistId =
                    runCatching { YouTube.createPlaylist(name) }
                        .onFailure(::reportException)
                        .getOrNull()
                if (playlistId.isNullOrBlank()) {
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_create_failed))
                    return@runAction
                }
                context.safeDataStoreEdit { it[OwnPlaylistIdsKey] = it[OwnPlaylistIdsKey].orEmpty() + playlistId }

                val current = (_playlists.value as? LoadState.Ready)?.value.orEmpty()
                _playlists.value =
                    LoadState.Ready(
                        listOf(
                            PlaylistItem(
                                id = playlistId,
                                title = name,
                                author = null,
                                songCountText = null,
                                thumbnail = firstSong?.thumbnail,
                                playEndpoint = null,
                                shuffleEndpoint = null,
                                radioEndpoint = null,
                                isEditable = true,
                            ),
                        ) + current.filterNot { it.id == playlistId },
                    )

                if (firstSong != null) {
                    addSongNow(playlistId, name, firstSong)
                } else {
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_created, name))
                }
            }
        }

        fun addSong(
            playlistId: String,
            playlistTitle: String,
            song: SongItem,
        ) = runAction { addSongNow(playlistId, playlistTitle, song) }

        private suspend fun addSongNow(
            playlistId: String,
            playlistTitle: String,
            song: SongItem,
        ) {
            if (!isOwn(playlistId)) return
            YouTube
                .addToPlaylist(playlistId, song.id)
                .onSuccess {
                    _messages.tryEmit(KidsMessage(R.string.kids_added_to_playlist, playlistTitle))
                    if (playlistId == loadedPlaylistId) openPlaylist(playlistId, forceReload = true)
                }.onFailure {
                    reportException(it)
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_edit_failed))
                }
        }

        fun removeSong(
            playlistId: String,
            song: SongItem,
        ) = runAction {
            val setVideoId = song.setVideoId
            if (!isOwn(playlistId) || setVideoId == null) return@runAction
            YouTube
                .removeFromPlaylist(playlistId, song.id, setVideoId)
                .onSuccess {
                    _messages.tryEmit(KidsMessage(R.string.kids_removed_from_playlist))
                    openPlaylist(playlistId, forceReload = true)
                }.onFailure {
                    reportException(it)
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_edit_failed))
                }
        }

        fun deletePlaylist(
            playlistId: String,
            playlistTitle: String,
            onDeleted: () -> Unit,
        ) = runAction {
            if (!isOwn(playlistId)) return@runAction
            YouTube
                .deletePlaylist(playlistId)
                .onSuccess {
                    context.safeDataStoreEdit { it[OwnPlaylistIdsKey] = it[OwnPlaylistIdsKey].orEmpty() - playlistId }
                    (_playlists.value as? LoadState.Ready)?.let { ready ->
                        _playlists.value = LoadState.Ready(ready.value.filterNot { it.id == playlistId })
                    }
                    if (loadedPlaylistId == playlistId) loadedPlaylistId = null
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_deleted, playlistTitle))
                    onDeleted()
                }.onFailure {
                    reportException(it)
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_edit_failed))
                }
        }

        // App applies the saved cookie to YouTube asynchronously at startup; requests made
        // before that would come back as a signed-out user with an empty library.
        private suspend fun awaitSession() {
            withTimeoutOrNull(5_000) {
                while (YouTube.cookie.isNullOrBlank()) delay(100)
            }
        }

        companion object {
            const val MAX_PLAYLIST_NAME = 60
        }
    }
