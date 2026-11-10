package com.bainance.tradess.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bainance_prefs")

/** Watchlist stored locally on the device (symbols only; nothing sensitive). */
class FavoritesStore(private val context: Context) {
    private val key = stringSetPreferencesKey("favorites")

    val favorites: Flow<Set<String>> = context.dataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(symbol: String) {
        context.dataStore.edit { p ->
            val cur = p[key] ?: emptySet()
            p[key] = if (symbol in cur) cur - symbol else cur + symbol
        }
    }
}
