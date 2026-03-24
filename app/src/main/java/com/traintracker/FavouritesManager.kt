package com.traintracker

import android.content.Context
import androidx.core.content.edit

class FavouritesManager(context: Context) {

    private val prefs = context.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    fun getAll(): List<Pair<String, String>> {
        return prefs.all.map { (crs, name) -> Pair(crs, name.toString()) }
            .sortedBy { it.first }
    }

    fun add(crs: String, name: String = "") {
        prefs.edit { putString(crs.uppercase(), name) }
    }

    fun remove(crs: String) {
        prefs.edit { remove(crs.uppercase()) }
    }

    fun isFavourite(crs: String) = prefs.contains(crs.uppercase())
}
