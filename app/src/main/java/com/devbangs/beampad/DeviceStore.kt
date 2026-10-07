package com.devbangs.beampad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a saved device is, for its icon and its suggested control mode. */
enum class DeviceType(val labelRes: Int, val iconRes: Int, val suggestedMode: ControlMode) {
    TV(R.string.type_tv, R.drawable.ic_television_simple, ControlMode.REMOTE),
    COMPUTER(R.string.type_computer, R.drawable.ic_desktop, ControlMode.TRACKPAD),
    PROJECTOR(R.string.type_projector, R.drawable.ic_projector_screen, ControlMode.PRESENTATION),
    PHONE(R.string.type_phone, R.drawable.ic_device_mobile, ControlMode.KEYBOARD),
    OTHER(R.string.type_other, R.drawable.ic_bluetooth, ControlMode.KEYBOARD)
}

/**
 * A device BeamPad has connected to, with the user's own name, icon and
 * labels, and its profile: the mode and panel to open, and its keyboard
 * layout. Profiles beyond name and icon are Pro (see [Features]).
 */
data class SavedDevice(
    val address: String,
    val systemName: String,
    val customName: String? = null,
    val type: DeviceType = DeviceType.TV,
    val labels: List<String> = emptyList(),
    val lastConnected: Long = 0L,
    val preferredMode: ControlMode? = null,
    val defaultPanelId: String? = null,
    val layout: HidReports.Layout? = null,
    /** A macro run automatically each time this device connects. */
    val connectMacroId: String? = null
) {
    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: systemName

    fun toJson(): JSONObject = JSONObject()
        .put("address", address)
        .put("systemName", systemName)
        .put("customName", customName)
        .put("type", type.name)
        .put("labels", JSONArray(labels))
        .put("lastConnected", lastConnected)
        .put("preferredMode", preferredMode?.name)
        .put("defaultPanelId", defaultPanelId)
        .put("layout", layout?.name)
        .put("connectMacro", connectMacroId)

    companion object {
        fun fromJson(o: JSONObject): SavedDevice? = runCatching {
            SavedDevice(
                address = o.getString("address"),
                systemName = o.optString("systemName", o.getString("address")),
                customName = o.optString("customName").takeIf { it.isNotEmpty() && it != "null" },
                type = enumOrNull<DeviceType>(o.optString("type")) ?: DeviceType.TV,
                labels = o.optJSONArray("labels")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                lastConnected = o.optLong("lastConnected", 0L),
                preferredMode = enumOrNull<ControlMode>(o.optString("preferredMode")),
                defaultPanelId = o.optString("defaultPanelId").takeIf { it.isNotEmpty() && it != "null" },
                layout = enumOrNull<HidReports.Layout>(o.optString("layout")),
                connectMacroId = o.optString("connectMacro").takeIf { it.isNotEmpty() && it != "null" }
            )
        }.getOrNull()
    }
}

inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

/**
 * Saved devices, most recently connected first. Stored on this phone only.
 * Writes are synchronized: the connection service records connects from its
 * callback thread while screens edit names on the main thread.
 */
class DeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): List<SavedDevice> = synchronized(LOCK) { read() }
        .sortedByDescending { it.lastConnected }

    fun get(address: String?): SavedDevice? =
        address?.let { a -> all().firstOrNull { it.address == a } }

    fun save(device: SavedDevice) = synchronized(LOCK) {
        write(read().filterNot { it.address == device.address } + device)
    }

    fun remove(address: String) = synchronized(LOCK) {
        write(read().filterNot { it.address == address })
    }

    /**
     * Called on every successful connection. Keeps the user's name, icon and
     * profile; refreshes the system name and the time.
     */
    fun recordConnected(address: String, systemName: String, guessedType: DeviceType): SavedDevice =
        synchronized(LOCK) {
            val list = read()
            val existing = list.firstOrNull { it.address == address }
            val updated = existing?.copy(
                systemName = systemName,
                lastConnected = System.currentTimeMillis()
            ) ?: SavedDevice(
                address = address,
                systemName = systemName,
                type = guessedType,
                lastConnected = System.currentTimeMillis()
            )
            write(list.filterNot { it.address == address } + updated)
            updated
        }

    private fun read(): List<SavedDevice> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { SavedDevice.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<SavedDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val FILE = "beampad_devices"
        const val KEY = "devices"
        val LOCK = Any()
    }
}
