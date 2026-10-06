package com.devbangs.beampad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A short local history of connection events, so a failure can be
 * explained after the fact on the Diagnostics screen, and shared by the
 * user if they choose. Never uploaded: it stays on the phone, holds no
 * typed text, and keeps only the newest [MAX] entries.
 */
object ConnectionLog {

    enum class Kind {
        BLUETOOTH, REGISTERED, UNSUPPORTED, CONNECTING, CONNECTED,
        DISCONNECTED, LOST, RETRY, FAILED, SEND_FAILED
    }

    data class Entry(val at: Long, val kind: Kind, val detail: String)

    private const val FILE = "beampad_log"
    private const val KEY = "entries"
    private const val MAX = 150
    private val LOCK = Any()

    fun add(context: Context, kind: Kind, detail: String = "") = synchronized(LOCK) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("k", kind.name).put("d", detail))
        val trimmed = JSONArray()
        val from = (arr.length() - MAX).coerceAtLeast(0)
        for (i in from until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY, trimmed.toString()).apply()
    }

    /** Newest first. */
    fun entries(context: Context): List<Entry> = synchronized(LOCK) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = enumOrNull<Kind>(o.optString("k")) ?: return@mapNotNull null
            Entry(o.optLong("at"), kind, o.optString("d"))
        }.reversed()
    }

    fun clear(context: Context) = synchronized(LOCK) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
