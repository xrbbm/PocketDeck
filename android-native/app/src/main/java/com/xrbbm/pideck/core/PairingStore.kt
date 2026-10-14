package com.xrbbm.pideck.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pideck_pairing")

/**
 * Single saved PiDeck service. Keys mirror Flutter PairingStore
 * (`pair_base_url` / `pair_token` / `last_session_id` / `app_dark_theme`)
 * so behavior stays comparable across clients. Token stays empty while
 * the LAN server runs without auth checks.
 */
data class Pairing(val baseUrl: String, val token: String = "")

class PairingStore(private val context: Context) {
    private object Keys {
        val BaseUrl = stringPreferencesKey("pair_base_url")
        val Token = stringPreferencesKey("pair_token")
        val LastSessionId = stringPreferencesKey("last_session_id")
        val DarkTheme = booleanPreferencesKey("app_dark_theme")
        // Drawer group expand state: comma-joined group ids (empty = all collapsed).
        val ExpandedGroups = stringPreferencesKey("drawer_expanded_groups")
    }

    val pairingFlow: Flow<Pairing?> = context.dataStore.data.map { prefs ->
        val baseUrl = prefs[Keys.BaseUrl]
        if (baseUrl.isNullOrEmpty()) null else Pairing(baseUrl, prefs[Keys.Token] ?: "")
    }

    suspend fun load(): Pairing? = pairingFlow.first()

    suspend fun save(pairing: Pairing) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BaseUrl] = pairing.baseUrl
            prefs[Keys.Token] = pairing.token
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.BaseUrl)
            prefs.remove(Keys.Token)
            prefs.remove(Keys.LastSessionId)
        }
    }

    suspend fun loadLastSessionId(): String? {
        val id = context.dataStore.data.first()[Keys.LastSessionId]
        return if (id.isNullOrEmpty()) null else id
    }

    suspend fun saveLastSessionId(id: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LastSessionId] = id }
    }

    suspend fun clearLastSessionId() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.LastSessionId) }
    }

    suspend fun loadExpandedGroups(): Set<String> {
        val raw = context.dataStore.data.first()[Keys.ExpandedGroups] ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun saveExpandedGroups(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ExpandedGroups] = ids.joinToString(",")
        }
    }

    suspend fun loadDarkTheme(): Boolean =
        context.dataStore.data.first()[Keys.DarkTheme] ?: false

    suspend fun saveDarkTheme(dark: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DarkTheme] = dark }
    }
}
