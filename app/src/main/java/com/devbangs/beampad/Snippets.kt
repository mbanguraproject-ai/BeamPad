package com.devbangs.beampad

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What a snippet is for, which decides its icon, its field and its filter. */
enum class SnippetCategory(val labelRes: Int, val iconRes: Int) {
    WIFI(R.string.snip_cat_wifi, R.drawable.ic_wifi_high),
    ACCOUNT(R.string.snip_cat_account, R.drawable.ic_user),
    TEXT(R.string.snip_cat_text, R.drawable.ic_note),
    LINK(R.string.snip_cat_link, R.drawable.ic_link)
}

/**
 * A saved piece of text the user can type to a paired device.
 *
 * [encrypted] is held encrypted; call [SnippetStore.reveal] to decrypt.
 * [secret] marks entries that must not be displayed or sent without
 * authentication.
 */
data class Snippet(
    val id: String,
    val label: String,
    val encrypted: String,
    val secret: Boolean,
    val category: SnippetCategory = SnippetCategory.TEXT
)

/** Local only: snippets never leave the phone except as keystrokes to a paired device. */
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
                    secret = o.optBoolean("secret", false),
                    category = enumOrNull<SnippetCategory>(o.optString("cat")) ?: SnippetCategory.TEXT
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Snippet? = all().firstOrNull { it.id == id }

    /** Thrown when a secret snippet is saved on a device with no screen lock. */
    class NoScreenLockException : IllegalStateException(Auth.NO_LOCK_MESSAGE)

    /**
     * @throws NoScreenLockException when [secret] is true and the device has no
     * secure lock screen. Callers must surface the message, not swallow it.
     */
    fun add(
        label: String,
        plaintext: String,
        secret: Boolean,
        screenLockAvailable: Boolean = true,
        category: SnippetCategory = SnippetCategory.TEXT
    ): Snippet {
        if (secret && !screenLockAvailable) throw NoScreenLockException()
        val snippet = Snippet(
            id = UUID.randomUUID().toString(),
            label = label,
            encrypted = Vault.encrypt(plaintext),
            secret = secret,
            category = category
        )
        persist(all() + snippet)
        return snippet
    }

    /** Replaces a snippet in place, keeping its position. A null [plaintext] keeps the stored value. */
    fun update(
        id: String,
        label: String,
        plaintext: String?,
        secret: Boolean,
        category: SnippetCategory,
        screenLockAvailable: Boolean = true
    ) {
        if (secret && !screenLockAvailable) throw NoScreenLockException()
        persist(all().map { s ->
            if (s.id != id) s else s.copy(
                label = label,
                encrypted = plaintext?.let { Vault.encrypt(it) } ?: s.encrypted,
                secret = secret,
                category = category
            )
        })
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
                    put("cat", s.category.name)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "snippets"
    }
}

/**
 * Remembers a successful unlock for the auto-lock window chosen in
 * Settings, in memory only: leaving the app or restarting the phone
 * always locks again.
 */
object SnippetLock {

    private var unlockedAt = 0L

    fun isUnlocked(context: Context): Boolean {
        val grace = Prefs(context).snippetAutoLock.graceMillis
        return grace > 0 && unlockedAt != 0L && SystemClock.elapsedRealtime() - unlockedAt < grace
    }

    fun unlocked() {
        unlockedAt = SystemClock.elapsedRealtime()
    }

    fun lock() {
        unlockedAt = 0L
    }
}
