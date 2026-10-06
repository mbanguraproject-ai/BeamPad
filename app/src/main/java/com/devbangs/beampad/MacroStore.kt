package com.devbangs.beampad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named sequence of actions with optional delays between them, run as
 * one tap from a panel button. Delays are [Action.Delay] steps.
 */
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<Action> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Macro? = runCatching {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            Macro(
                id = o.getString("id"),
                name = o.getString("name"),
                steps = (0 until arr.length()).mapNotNull { Action.fromJson(arr.optJSONObject(it)) }
            )
        }.getOrNull()
    }
}

/** Saved macros, in the user's order. Stored on this phone only. */
class MacroStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): List<Macro> = synchronized(LOCK) { read() }

    fun get(id: String?): Macro? = id?.let { i -> all().firstOrNull { it.id == i } }

    fun save(macro: Macro) = synchronized(LOCK) {
        val list = read().toMutableList()
        val at = list.indexOfFirst { it.id == macro.id }
        if (at >= 0) list[at] = macro else list += macro
        write(list)
    }

    fun delete(id: String) = synchronized(LOCK) { write(read().filterNot { it.id == id }) }

    private fun read(): List<Macro> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Macro.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<Macro>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val FILE = "beampad_macros"
        const val KEY = "macros"
        val LOCK = Any()
    }
}
