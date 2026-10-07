package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A TV app BeamPad can open, by typing [name] into the TV's search. Known
 * apps carry their [logo] and colours: [tile] behind it and [ink] for the
 * mark (or for the initial, where no free logo exists). Apps added by name
 * have none and get a plain initial.
 */
data class TvApp(
    val id: String,
    val name: String,
    val logo: Int? = null,
    val tile: Int? = null,
    val ink: Int? = null
)

/**
 * The app launcher (Pro): favourite TV apps per device, each opened in one
 * tap. A Bluetooth keyboard cannot ask the TV which apps it has or open one
 * directly, so an app is opened the way a person would: Home, the TV's
 * search, its name, Enter. That works on Google TV, Android TV and Fire TV.
 * Favourites are kept on this phone, per device.
 */
object Launcher {

    /** Apps offered when picking favourites; anything else can be added by name. */
    // Logos from Simple Icons (CC0); colours follow each app's own icon.
    // Prime Video, Disney+, Hulu and Peacock have no free logo (their
    // owners withdrew them), so they show an initial in their colours.
    val catalogue: List<TvApp> = listOf(
        TvApp("youtube", "YouTube", R.drawable.ic_app_youtube, WHITE, 0xFFFF0000.toInt()),
        TvApp("netflix", "Netflix", R.drawable.ic_app_netflix, BLACK, 0xFFE50914.toInt()),
        TvApp("prime", "Prime Video", null, 0xFF00A8E1.toInt(), WHITE),
        TvApp("disney", "Disney+", null, 0xFF113CCF.toInt(), WHITE),
        TvApp("max", "Max", R.drawable.ic_app_max, 0xFF002BE7.toInt(), WHITE),
        TvApp("hulu", "Hulu", null, 0xFF1CE783.toInt(), BLACK),
        TvApp("appletv", "Apple TV", R.drawable.ic_app_appletv, BLACK, WHITE),
        TvApp("paramount", "Paramount+", R.drawable.ic_app_paramount, 0xFF0064FF.toInt(), WHITE),
        TvApp("peacock", "Peacock", null, BLACK, WHITE),
        TvApp("spotify", "Spotify", R.drawable.ic_app_spotify, 0xFF1ED760.toInt(), BLACK),
        TvApp("youtube_music", "YouTube Music", R.drawable.ic_app_youtube_music, 0xFFFF0000.toInt(), WHITE),
        TvApp("twitch", "Twitch", R.drawable.ic_app_twitch, 0xFF9146FF.toInt(), WHITE),
        TvApp("plex", "Plex", R.drawable.ic_app_plex, 0xFF1F1F1F.toInt(), 0xFFEBAF00.toInt()),
        TvApp("crunchyroll", "Crunchyroll", R.drawable.ic_app_crunchyroll, 0xFFFF5E00.toInt(), WHITE),
        TvApp("kodi", "Kodi", R.drawable.ic_app_kodi, 0xFF17B2E7.toInt(), WHITE),
        TvApp("vlc", "VLC", R.drawable.ic_app_vlc, 0xFFFF8800.toInt(), WHITE)
    )

    private val DEFAULTS = listOf("youtube", "netflix", "prime", "disney", "spotify", "plex")

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

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
                val id = o.getString("id")
                // Known apps come back with their logo and colours.
                catalogue.firstOrNull { it.id == id } ?: TvApp(id, o.getString("name"))
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
