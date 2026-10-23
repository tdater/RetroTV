package com.aiconsole.retrotv.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's game collections, kept in SharedPreferences in [CollectionRules]' text format. */
class CollectionStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("collections", Context.MODE_PRIVATE)
    private val _collections = MutableStateFlow(CollectionRules.parse(prefs.getString("all", null)))
    val collections: StateFlow<List<GameCollection>> = _collections.asStateFlow()

    private fun apply(next: List<GameCollection>) {
        if (next == _collections.value) return
        _collections.value = next
        prefs.edit { putString("all", CollectionRules.serialize(next)) }
    }

    /** Creates a collection and returns its id; null when the name is blank or taken or there are too many. */
    fun create(name: String): String? {
        val before = _collections.value
        val after = CollectionRules.create(before, name)
        if (after.size == before.size) return null
        apply(after)
        return after.last().id
    }

    fun rename(id: String, name: String) = apply(CollectionRules.rename(_collections.value, id, name))

    fun delete(id: String) = apply(CollectionRules.delete(_collections.value, id))

    fun add(id: String, gameKey: String) = apply(CollectionRules.add(_collections.value, id, gameKey))

    fun remove(id: String, gameKey: String) = apply(CollectionRules.remove(_collections.value, id, gameKey))

    fun toggle(id: String, gameKey: String) = apply(CollectionRules.toggle(_collections.value, id, gameKey))

    companion object {
        @Volatile private var instance: CollectionStore? = null

        fun from(context: Context): CollectionStore =
            instance ?: synchronized(this) { instance ?: CollectionStore(context).also { instance = it } }
    }
}

/** Which home rows show and in what order ([HomeLayout]), saved in SharedPreferences. */
class HomeLayoutStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    private val _rows = MutableStateFlow(HomeLayout.parse(prefs.getString("rows", null)))
    val rows: StateFlow<List<HomeLayout.Row>> = _rows.asStateFlow()

    fun set(next: List<HomeLayout.Row>) {
        _rows.value = next
        prefs.edit { putString("rows", HomeLayout.serialize(next)) }
    }

    fun reset() = set(HomeLayout.default())

    companion object {
        @Volatile private var instance: HomeLayoutStore? = null

        fun from(context: Context): HomeLayoutStore =
            instance ?: synchronized(this) { instance ?: HomeLayoutStore(context).also { instance = it } }
    }
}
