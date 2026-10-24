/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kids

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.BuildConfig
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * Parent PIN that unlocks the full app.
 *
 * A PIN baked in at build time (KIDS_PARENT_PIN) always wins, because it survives
 * "Clear data" in Android settings. Without one, the first person to open parent
 * mode chooses a PIN, which is stored (hashed) in the app's preferences.
 */
object ParentLock {
    private val StoredPinHashKey = stringPreferencesKey("kidsParentPinHash")

    private val buildPinHash: String?
        get() = BuildConfig.KIDS_PARENT_PIN_HASH.takeIf { it.isNotBlank() }

    fun hash(pin: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("kids-music:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    suspend fun needsSetup(context: Context): Boolean =
        buildPinHash == null && context.dataStore.data.first()[StoredPinHashKey].isNullOrBlank()

    suspend fun verify(context: Context, pin: String): Boolean {
        val expected = buildPinHash ?: context.dataStore.data.first()[StoredPinHashKey] ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(pin).toByteArray())
    }

    suspend fun setPin(context: Context, pin: String): Boolean =
        context.safeDataStoreEdit { it[StoredPinHashKey] = hash(pin) }
}
