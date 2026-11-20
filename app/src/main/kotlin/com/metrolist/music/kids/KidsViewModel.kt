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
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
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

data class KidsPlaylist(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val songCount: Int,
)

data class KidsPlaylistDetail(
    val playlist: KidsPlaylist,
    val songs: List<PlaylistSong>,
)

// Playlists created from the locked screen (local database ids). Only these can be edited
// or deleted there; everything else (the parent's playlists) stays read-only.
private val OwnPlaylistIdsKey = stringSetPreferencesKey("kidsOwnLocalPlaylistIds")

private fun Playlist.toKids() =
    KidsPlaylist(
        id = id,
        title = title,
        thumbnail = thumbnails.firstOrNull(),
        songCount = songCount,
    )

/**
 * The locked screen reads playlists from the app's own database, the same copy parent mode
 * shows. YouTube's playlist pages can come back empty for supervised accounts even when the
 * playlist has songs, so they can't be relied on here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KidsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val syncUtils: SyncUtils,
    ) : ViewModel() {
        /** null until the stored account state has been read. */
        val isLoggedIn: StateFlow<Boolean?> =
            context.dataStore.data
                .map { it[InnerTubeCookieKey]?.contains("SAPISID") == true }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val playlists: StateFlow<LoadState<List<KidsPlaylist>>> =
            database
                .playlistsByCreateDateAsc()
                .map<List<Playlist>, LoadState<List<KidsPlaylist>>> { list -> LoadState.Ready(list.map { it.toKids() }) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

        private val openPlaylistId = MutableStateFlow<String?>(null)

        val playlist: StateFlow<LoadState<KidsPlaylistDetail>> =
            openPlaylistId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(LoadState.Loading)
                    } else {
                        combine(database.playlist(id), database.playlistSongs(id)) { playlist, songs ->
                            if (playlist == null) {
                                LoadState.Error
                            } else {
                                LoadState.Ready(KidsPlaylistDetail(playlist.toKids(), songs))
                            }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

        val ownPlaylistIds: StateFlow<Set<String>> =
            context.dataStore.data
                .map { it[OwnPlaylistIdsKey].orEmpty() }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        private val _messages = MutableSharedFlow<KidsMessage>(extraBufferCapacity = 4)
        val messages = _messages.asSharedFlow()

        private val _busy = MutableStateFlow(false)
        val busy = _busy.asStateFlow()

        /** Pulls the account's saved playlists down into the database. */
        fun refresh() {
            syncUtils.syncSavedPlaylists()
            syncUtils.syncAutoSyncPlaylists()
        }

        fun openPlaylist(playlistId: String) {
            openPlaylistId.value = playlistId
        }

        private suspend fun isOwn(playlistId: String): Boolean =
            playlistId in context.dataStore.data.first()[OwnPlaylistIdsKey].orEmpty()

        private fun runAction(block: suspend () -> Unit) {
            if (_busy.value) return
            _busy.value = true
            viewModelScope.launch {
                try {
                    block()
                } catch (e: Exception) {
                    reportException(e)
                    _messages.tryEmit(KidsMessage(R.string.kids_playlist_edit_failed))
                } finally {
                    _busy.value = false
                }
            }
        }

        /** Creates a playlist (synced to YouTube when possible), optionally adding [firstSongId]. */
        fun createPlaylist(
            title: String,
            firstSongId: String? = null,
        ) {
            val name = title.trim().take(MAX_PLAYLIST_NAME)
            if (name.isEmpty() || _busy.value) return
            _busy.value = true
            viewModelScope.launch {
                val sync = withContext(Dispatchers.IO) { context.isSyncEnabled() }
                syncUtils.createPlaylist(
                    playlist =
                        PlaylistEntity(
                            name = name,
                            bookmarkedAt = LocalDateTime.now(),
                            isEditable = true,
                        ),
                    syncWithYouTube = sync,
                ) { playlistId, _ ->
                    viewModelScope.launch {
                        try {
                            context.safeDataStoreEdit {
                                it[OwnPlaylistIdsKey] = it[OwnPlaylistIdsKey].orEmpty() + playlistId
                            }
                            if (firstSongId != null) {
                                addSongNow(playlistId, firstSongId)
                            } else {
                                _messages.tryEmit(KidsMessage(R.string.kids_playlist_created, name))
                            }
                        } catch (e: Exception) {
                            reportException(e)
                            _messages.tryEmit(KidsMessage(R.string.kids_playlist_edit_failed))
                        } finally {
                            _busy.value = false
                        }
                    }
                }
            }
        }

        fun addSong(
            playlistId: String,
            songId: String,
        ) = runAction { addSongNow(playlistId, songId) }

        private suspend fun addSongNow(
            playlistId: String,
            songId: String,
        ) {
            if (!isOwn(playlistId)) return
            val target = database.playlist(playlistId).first() ?: return
            withContext(Dispatchers.IO) {
                if (database.playlistDuplicates(playlistId, listOf(songId)).isNotEmpty()) {
                    _messages.tryEmit(KidsMessage(R.string.kids_already_in_playlist, target.title))
                    return@withContext
                }
                database.addSongsToPlaylist(target, listOf(songId to null))
                target.playlist.browseId?.let { syncUtils.scheduleAddToPlaylist(it, target.id, listOf(songId)) }
                _messages.tryEmit(KidsMessage(R.string.kids_added_to_playlist, target.title))
            }
        }

        fun removeSong(
            playlistId: String,
            song: PlaylistSong,
        ) = runAction {
            if (!isOwn(playlistId)) return@runAction
            val browseId = database.playlist(playlistId).first()?.playlist?.browseId
            val map = song.map
            database.transaction {
                move(map.playlistId, map.position, Int.MAX_VALUE)
                delete(map.copy(position = Int.MAX_VALUE))
            }
            if (browseId != null) {
                syncUtils.scheduleRemoveFromPlaylist(browseId, map.songId, map.playlistId) { map.setVideoId }
            }
            _messages.tryEmit(KidsMessage(R.string.kids_removed_from_playlist))
        }

        fun deletePlaylist(
            playlistId: String,
            onDeleted: () -> Unit,
        ) = runAction {
            if (!isOwn(playlistId)) return@runAction
            val target = database.playlist(playlistId).first() ?: return@runAction
            database.query { delete(target.playlist) }
            target.playlist.browseId?.let { browseId ->
                viewModelScope.launch(Dispatchers.IO) {
                    YouTube.deletePlaylist(browseId).onFailure(::reportException)
                }
            }
            context.safeDataStoreEdit { it[OwnPlaylistIdsKey] = it[OwnPlaylistIdsKey].orEmpty() - playlistId }
            _messages.tryEmit(KidsMessage(R.string.kids_playlist_deleted, target.title))
            onDeleted()
        }

        companion object {
            const val MAX_PLAYLIST_NAME = 60
        }
    }
