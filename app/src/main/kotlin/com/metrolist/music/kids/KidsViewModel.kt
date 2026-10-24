/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kids

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.utils.completed
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

        // App applies the saved cookie to YouTube asynchronously at startup; requests made
        // before that would come back as a signed-out user with an empty library.
        private suspend fun awaitSession() {
            withTimeoutOrNull(5_000) {
                while (YouTube.cookie.isNullOrBlank()) delay(100)
            }
        }
    }
