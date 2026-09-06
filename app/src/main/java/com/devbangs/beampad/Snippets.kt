package com.devbangs.beampad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved piece of text the user can type to a paired device.
 *
 * [value] is held encrypted; call [SnippetStore.reveal] to decrypt.
 * [secret] marks entries that must not be displayed without authentication.
 */
data class Snippet(
    val id: String,
    val label: String,
    val encrypted: String,
    val secret: Boolean
)

class SnippetStore(context: Context) {

    private val prefs = context.getSharedPreferences("beampad_snippets", Context.MODE_PRIVATE)

    fun all(): List<Snippet> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Snippet(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    encrypted = o.getString("value"),
                    secret = o.optBoolean("secret", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(label: String, plaintext: String, secret: Boolean): Snippet {
        val snippet = Snippet(
            id = UUID.randomUUID().toString(),
            label = label,
            encrypted = Vault.encrypt(plaintext),
            secret = secret
        )
        persist(all() + snippet)
        return snippet
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }

    /** Decrypts a snippet's value. Gate this behind authentication when secret. */
    fun reveal(snippet: Snippet): String = Vault.decrypt(snippet.encrypted)

    private fun persist(list: List<Snippet>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("value", s.encrypted)
                    put("secret", s.secret)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "snippets"
    }
}
