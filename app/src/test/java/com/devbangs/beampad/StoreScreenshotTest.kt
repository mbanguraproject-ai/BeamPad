package com.devbangs.beampad

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.navigation.NavigationBarView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The real screens for the Play listing, at 3x so they stay sharp in the
 * framed store images: dark theme, first-run tips already seen, and the top
 * bar as the app draws it once connected. The Bluetooth service cannot run
 * under Robolectric, so [connected] sets the same views MainActivity sets
 * for a connected device; everything else is the screen as it renders.
 * CI publishes these with the others as store_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class StoreScreenshotTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_seen", true)
            .putBoolean("asked_permissions", true)
            .commit()
        // No ad requests from tests.
        app.getSharedPreferences("beampad_ent", Context.MODE_PRIVATE).edit()
            .putBoolean("ads_removed", true)
            .commit()
        // Tips are a first-day thing; a listing shows the app as used after that.
        val tips = app.getSharedPreferences(SurfaceFragment.TIPS_FILE, Context.MODE_PRIVATE).edit()
        listOf(
            KeyboardFragment::class.java, RemoteFragment::class.java, TrackpadFragment::class.java,
            MouseFragment::class.java, MediaFragment::class.java, PresentationFragment::class.java,
            PanelFragment::class.java
        ).forEach { tips.putBoolean(it.simpleName, true) }
        tips.commit()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, HidService::class.java))
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS
        )
        Prefs(app).appearance = Appearance.DARK
    }

    private fun pro(on: Boolean) {
        app.getSharedPreferences("beampad_ent", Context.MODE_PRIVATE).edit()
            .putBoolean("pro_lifetime", on)
            .commit()
    }

    private fun main(mode: ControlMode = ControlMode.KEYBOARD, tab: Int? = null): MainActivity {
        Prefs(app).lastMode = mode
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        if (tab != null) {
            activity.findViewById<NavigationBarView>(R.id.bottomNav).selectedItemId = tab
        }
        settle()
        return activity
    }

    private fun settle() {
        repeat(3) { ShadowLooper.idleMainLooper() }
    }

    /** The top bar as MainActivity.renderTopBar draws it for a connected device. */
    private fun connected(activity: MainActivity, name: String, type: DeviceType) {
        val live = activity.themeColor(R.attr.bpLive)
        activity.findViewById<TextView>(R.id.deviceName).text = name
        activity.findViewById<TextView>(R.id.deviceStatus).apply {
            setText(R.string.state_connected)
            setTextColor(live)
        }
        activity.findViewById<View>(R.id.deviceDot).background.mutate().setTint(live)
        activity.findViewById<View>(R.id.deviceTile).setBackgroundResource(Ui.Tone.LIVE.tile)
        activity.findViewById<ImageView>(R.id.deviceIcon).apply {
            setImageResource(type.iconRes)
            imageTintList = ColorStateList.valueOf(activity.themeColor(Ui.Tone.LIVE.tint))
        }
        // Connected, the setup card has nothing to say (MainActivity.refreshStatus).
        activity.findViewById<View>(R.id.statusCard).visibility = View.GONE
    }

    private fun shoot(activity: MainActivity, name: String) {
        val root = activity.window.decorView
        val metrics = root.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun surface(mode: ControlMode, name: String, device: String, type: DeviceType) {
        val activity = main(mode)
        connected(activity, device, type)
        shoot(activity, name)
    }

    @Test
    fun keyboard() = surface(ControlMode.KEYBOARD, "store_1_keyboard", "Living room TV", DeviceType.TV)

    @Test
    fun remote() = surface(ControlMode.REMOTE, "store_2_remote", "Living room TV", DeviceType.TV)

    @Test
    fun snippets() {
        pro(true)
        seedSnippets()
        val activity = main(tab = R.id.tab_snippets)
        connected(activity, "Living room TV", DeviceType.TV)
        shoot(activity, "store_3_snippets")
    }

    @Test
    fun trackpad() = surface(ControlMode.TRACKPAD, "store_4_trackpad", "Office PC", DeviceType.COMPUTER)

    @Test
    fun fullKeyboard() {
        pro(true)
        Prefs(app).fullKeyboard = true
        surface(ControlMode.KEYBOARD, "store_5_full_keyboard", "Office PC", DeviceType.COMPUTER)
    }

    @Test
    fun media() = surface(ControlMode.MEDIA, "store_6_media", "Living room TV", DeviceType.TV)

    @Test
    fun presentation() {
        pro(true)
        surface(ControlMode.PRESENTATION, "store_7_presentation", "Meeting room", DeviceType.PROJECTOR)
    }

    /** The keystore is not available under Robolectric, so rows are written as stored. */
    private fun seedSnippets() {
        val rows = listOf(
            Triple("Home Wi-Fi", "WIFI", true),
            Triple("Streaming login", "ACCOUNT", false),
            Triple("Holiday photos", "LINK", false),
            Triple("Address", "TEXT", false)
        )
        val arr = org.json.JSONArray()
        rows.forEachIndexed { i, (label, cat, secret) ->
            arr.put(
                org.json.JSONObject().put("id", "s$i").put("label", label).put("value", "x")
                    .put("secret", secret).put("cat", cat)
            )
        }
        app.getSharedPreferences("beampad_snippets", Context.MODE_PRIVATE).edit()
            .putString("snippets", arr.toString()).commit()
    }
}
