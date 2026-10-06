package com.devbangs.beampad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The building blocks a custom panel can hold. Each sits on a 4-column grid
 * and spans [PanelComponent.span] columns and [PanelComponent.rows] rows.
 */
enum class ComponentType(val labelRes: Int, val defaultSpan: Int, val defaultRows: Int) {
    /** One action, e.g. Home or Netflix's shortcut. */
    BUTTON(R.string.comp_button, 1, 1),

    /** Arrows with OK in the middle. */
    DPAD(R.string.comp_dpad, 4, 3),

    /** A touch surface moving the pointer. */
    TRACKPAD(R.string.comp_trackpad, 4, 3),

    /** Steps two actions up and down, e.g. volume. Config: "up", "down". */
    SLIDER(R.string.comp_slider, 4, 1),

    /** Two states with an action each, e.g. mute. Config: "on", "off". */
    TOGGLE(R.string.comp_toggle, 2, 1),

    /** A field typing live onto the device. */
    KEYBOARD(R.string.comp_keyboard, 4, 1),

    /** Previous, play/pause, next. */
    MEDIA(R.string.comp_media, 4, 1),

    /** A field that sends its text on Enter. */
    TEXT_INPUT(R.string.comp_text_input, 4, 1),

    /** A key combination, e.g. Alt+Tab. */
    SHORTCUT(R.string.comp_shortcut, 2, 1),

    /** Runs a saved macro. Config: "macroId". */
    MACRO(R.string.comp_macro, 2, 1)
}

data class PanelComponent(
    val id: String = UUID.randomUUID().toString(),
    val type: ComponentType,
    val label: String? = null,
    val action: Action? = null,
    val span: Int = type.defaultSpan,
    val rows: Int = type.defaultRows,
    /** Type-specific settings, kept as JSON so new options need no migration. */
    val config: JSONObject = JSONObject()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("label", label)
        .put("action", action?.toJson())
        .put("span", span)
        .put("rows", rows)
        .put("config", config)

    companion object {
        fun fromJson(o: JSONObject): PanelComponent? = runCatching {
            val type = enumOrNull<ComponentType>(o.getString("type")) ?: return null
            PanelComponent(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                type = type,
                label = o.optString("label").takeIf { it.isNotEmpty() && it != "null" },
                action = Action.fromJson(o.optJSONObject("action")),
                span = o.optInt("span", type.defaultSpan).coerceIn(1, Panel.COLUMNS),
                rows = o.optInt("rows", type.defaultRows).coerceIn(1, 4),
                config = o.optJSONObject("config") ?: JSONObject()
            )
        }.getOrNull()
    }
}

data class Panel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val components: List<PanelComponent> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("components", JSONArray().also { a -> components.forEach { a.put(it.toJson()) } })

    companion object {
        /** The grid every panel is laid out on; landscape and tablets scale cells, not columns. */
        const val COLUMNS = 4

        fun fromJson(o: JSONObject): Panel? = runCatching {
            val arr = o.optJSONArray("components") ?: JSONArray()
            Panel(
                id = o.getString("id"),
                name = o.getString("name"),
                components = (0 until arr.length()).mapNotNull {
                    PanelComponent.fromJson(arr.getJSONObject(it))
                }
            )
        }.getOrNull()
    }
}

/** Custom panels, in the user's order. Stored on this phone only. */
class PanelStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): List<Panel> = synchronized(LOCK) { read() }

    fun get(id: String?): Panel? = id?.let { i -> all().firstOrNull { it.id == i } }

    fun create(name: String): Panel = Panel(name = name).also { save(it) }

    /** Inserts or replaces in place, keeping the panel's position. */
    fun save(panel: Panel) = synchronized(LOCK) {
        val list = read().toMutableList()
        val at = list.indexOfFirst { it.id == panel.id }
        if (at >= 0) list[at] = panel else list += panel
        write(list)
    }

    fun delete(id: String) = synchronized(LOCK) { write(read().filterNot { it.id == id }) }

    fun duplicate(id: String, copyName: String): Panel? = synchronized(LOCK) {
        val list = read().toMutableList()
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) return null
        val source = list[at]
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = copyName,
            components = source.components.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        list.add(at + 1, copy)
        write(list)
        copy
    }

    /** Puts panels in the order of [ids]; any not listed keep their relative order at the end. */
    fun reorder(ids: List<String>) = synchronized(LOCK) {
        val list = read()
        val ordered = ids.mapNotNull { id -> list.firstOrNull { it.id == id } }
        write(ordered + list.filterNot { it.id in ids })
    }

    private fun read(): List<Panel> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Panel.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<Panel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val FILE = "beampad_panels"
        const val KEY = "panels"
        val LOCK = Any()
    }
}
