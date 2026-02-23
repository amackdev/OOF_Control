package com.oof.control.game

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Singleton store for all per-app game profiles.
 * Backed by SharedPreferences as a JSON array.
 * Thread-safe via @Synchronized.
 */
object GameProfileStore {

    private const val TAG   = "GameProfileStore"
    private const val PREFS = "game_profiles"
    private const val KEY   = "profiles_json"

    private var prefs: SharedPreferences? = null
    private val cache = mutableMapOf<String, GameProfile>()
    private var loaded = false

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            load()
        }
    }

    @Synchronized
    fun getAll(): List<GameProfile> {
        ensureLoaded()
        return cache.values.toList().sortedBy { it.label.lowercase() }
    }

    @Synchronized
    fun get(packageName: String): GameProfile? {
        ensureLoaded()
        return cache[packageName]
    }

    @Synchronized
    fun put(profile: GameProfile) {
        ensureLoaded()
        cache[profile.packageName] = profile
        save()
    }

    @Synchronized
    fun remove(packageName: String) {
        ensureLoaded()
        cache.remove(packageName)
        save()
    }

    @Synchronized
    fun contains(packageName: String): Boolean {
        ensureLoaded()
        return cache.containsKey(packageName)
    }

    private fun ensureLoaded() { if (!loaded) load() }

    private fun load() {
        cache.clear()
        try {
            val json = prefs?.getString(KEY, null) ?: return
            val arr  = JSONArray(json)
            for (i in 0 until arr.length()) {
                val p = GameProfile.fromJson(arr.getJSONObject(i))
                cache[p.packageName] = p
            }
            Log.d(TAG, "Loaded ${cache.size} profiles")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profiles", e)
        }
        loaded = true
    }

    private fun save() {
        try {
            val arr = JSONArray()
            cache.values.forEach { arr.put(it.toJson()) }
            prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }
}
