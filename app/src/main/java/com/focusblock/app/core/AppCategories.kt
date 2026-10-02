package com.focusblock.app.core

import android.content.Context
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.policy.AppCategory
import com.focusblock.app.policy.CategoryDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Distracting / Neutral / Productive per app (Activity tab). The user's choice wins; otherwise
 * [CategoryDefaults] decides from well-known apps and the category the developer declared.
 * Choices are stored as JSON in the settings table, so no schema change is needed.
 */
class AppCategories(private val context: Context, private val db: FocusBlockDatabase) {
    @Volatile private var overrides: Map<String, AppCategory> = emptyMap()
    @Volatile private var loaded = false
    private val declared = ConcurrentHashMap<String, Int>()

    suspend fun load(): Map<String, AppCategory> = withContext(Dispatchers.IO) {
        val json = db.settingsDao().getValue(PrefKeys.APP_CATEGORIES)
        overrides = runCatching {
            val o = JSONObject(json.orEmpty().ifBlank { "{}" })
            o.keys().asSequence().mapNotNull { k -> AppCategory.values().firstOrNull { it.name == o.optString(k) }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        loaded = true
        overrides
    }

    suspend fun set(pkg: String, category: AppCategory) = withContext(Dispatchers.IO) {
        if (!loaded) load()
        val next = overrides + (pkg to category)
        val json = JSONObject().apply { next.forEach { (k, v) -> put(k, v.name) } }.toString()
        db.settingsDao().insert(AppSettings(PrefKeys.APP_CATEGORIES, json))
        overrides = next
    }

    fun isCustom(pkg: String): Boolean = pkg in overrides

    /** Call [load] first; until then only defaults are used. */
    fun categoryOf(pkg: String): AppCategory = overrides[pkg] ?: CategoryDefaults.forPackage(pkg, declaredCategory(pkg))

    private fun declaredCategory(pkg: String): Int? {
        declared[pkg]?.let { return it.takeIf { c -> c >= 0 } }
        val c = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)
        declared[pkg] = c
        return c.takeIf { it >= 0 }
    }
}
