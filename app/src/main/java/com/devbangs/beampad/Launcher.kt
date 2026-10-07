package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A TV app BeamPad can open, by typing [name] into the TV's search. */
data class TvApp(val id: String, val name: String)

/**
 * The app launcher (Pro): favourite TV apps per device, each opened in one
 * tap. A Bluetooth keyboard cannot ask the TV which apps it has or open one
 * directly, so an app is opened the way a person would: Home, the TV's
 * search, its name, Enter. That works on Google TV, Android TV and Fire TV.
 * Favourites are kept on this phone, per device.
 */
object Launcher {

    /** Apps offered when picking favourites; anything else can be added by name. */
    val catalogue: List<TvApp> = listOf(
        TvApp("youtube", "YouTube"),
        TvApp("netflix", "Netflix"),
        TvApp("prime", "Prime Video"),
        TvApp("disney", "Disney+"),
        TvApp("max", "Max"),
        TvApp("hulu", "Hulu"),
        TvApp("appletv", "Apple TV"),
        TvApp("paramount", "Paramount+"),
        TvApp("peacock", "Peacock"),
        TvApp("spotify", "Spotify"),
        TvApp("youtube_music", "YouTube Music"),
        TvApp("twitch", "Twitch"),
        TvApp("plex", "Plex"),
        TvApp("crunchyroll", "Crunchyroll"),
        TvApp("kodi", "Kodi"),
        TvApp("vlc", "VLC")
    )

    private val DEFAULTS = listOf("youtube", "netflix", "prime", "disney", "spotify", "plex")

    private const val FILE = "beampad_launch"
    private const val ANY_DEVICE = "any"

    private fun store(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Favourites for the device at [address] (or for any device, before one is known). */
    fun favorites(context: Context, address: String?): List<TvApp> {
        val raw = store(context).getString(address ?: ANY_DEVICE, null)
            ?: store(context).getString(ANY_DEVICE, null)
            ?: return DEFAULTS.mapNotNull { id -> catalogue.firstOrNull { it.id == id } }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TvApp(o.getString("id"), o.getString("name"))
            }
        }.getOrDefault(emptyList())
    }

    fun setFavorites(context: Context, address: String?, apps: List<TvApp>) {
        val array = JSONArray()
        apps.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        store(context).edit().putString(address ?: ANY_DEVICE, array.toString()).apply()
    }

    /** An app the user added by name, keyed by that name. */
    fun custom(name: String): TvApp = TvApp("custom:" + name.trim().lowercase(), name.trim())

    /** Home, the TV's search, the app's name, Enter. */
    fun steps(app: TvApp): List<Action> = listOf(
        Action.Consumer(HidReports.CC_HOME), Action.Delay(1500),
        Action.Consumer(HidReports.CC_SEARCH), Action.Delay(1200),
        Action.Text(app.name), Action.Delay(400),
        Action.Key(HidReports.KEY_ENTER.toInt())
    )

    /** Opens [app] on the connected device, with the usual progress and Stop. */
    fun open(activity: Activity, service: HidService?, app: TvApp) {
        MacroRunSheet.run(
            activity,
            service,
            Macro(name = activity.getString(R.string.launch_opening, app.name), steps = steps(app))
        )
    }

    /** The device favourites belong to: the connected one, else the last one used. */
    fun currentDevice(service: HidService?, prefs: Prefs): String? =
        service?.connectedDevice?.address ?: prefs.lastHost
}
