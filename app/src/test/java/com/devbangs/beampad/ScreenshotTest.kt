package com.devbangs.beampad

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders each screen to build/screenshots so layouts can be reviewed
 * without a device. CI publishes the images; nothing is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {

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
        // Robolectric cannot run the Bluetooth service; screens render the
        // state they show before it binds.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, HidService::class.java))
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private fun appearance(value: Appearance) {
        Prefs(app).appearance = value
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

    private inline fun <reified A : Activity> screen(intent: Intent? = null): A {
        val controller = if (intent != null) Robolectric.buildActivity(A::class.java, intent)
        else Robolectric.buildActivity(A::class.java)
        val activity = controller.setup().get()
        settle()
        return activity
    }

    private fun settle() {
        repeat(3) { ShadowLooper.idleMainLooper() }
    }

    private fun shoot(activity: Activity, name: String) = shoot(activity.window.decorView, name)

    private fun shootDialog(name: String) {
        settle()
        val dialog = ShadowDialog.getLatestDialog() ?: error("no dialog for $name")
        shoot(dialog.window!!.decorView, name)
    }

    private fun shoot(root: View, name: String) {
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

    private fun pro(on: Boolean) {
        app.getSharedPreferences("beampad_ent", Context.MODE_PRIVATE).edit()
            .putBoolean("pro_lifetime", on)
            .commit()
    }

    @Test
    fun controlModes() {
        appearance(Appearance.DARK)
        ControlMode.entries.forEach { mode ->
            pro(mode.pro != null)
            shoot(main(mode), "control_${mode.name.lowercase()}")
        }
        pro(false)
    }

    @Test
    fun remoteLayouts() {
        appearance(Appearance.DARK)
        Prefs(app).remoteLayout = Prefs.RemoteLayout.FULL
        shoot(main(ControlMode.REMOTE), "remote_full")
        Prefs(app).remoteLayout = Prefs.RemoteLayout.MINIMAL
        shoot(main(ControlMode.REMOTE), "remote_minimal")
        Prefs(app).remoteLayout = Prefs.RemoteLayout.STANDARD
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi")
    fun landscape() {
        appearance(Appearance.DARK)
        pro(true)
        shoot(main(ControlMode.PRESENTATION), "land_presentation")
        shoot(main(ControlMode.REMOTE), "land_remote")
        pro(false)
    }

    private fun seedDevices() {
        val store = DeviceStore(app)
        val now = System.currentTimeMillis()
        store.save(SavedDevice("AA:BB:CC:00:00:01", "BRAVIA 4K", "Living room TV", DeviceType.TV,
            listOf("Living room"), now - 3_600_000L, ControlMode.REMOTE))
        store.save(SavedDevice("AA:BB:CC:00:00:02", "DESKTOP-7Q2", "Office PC", DeviceType.COMPUTER,
            emptyList(), now - 86_400_000L * 2, ControlMode.TRACKPAD))
        store.save(SavedDevice("AA:BB:CC:00:00:03", "Epson EF-12", null, DeviceType.PROJECTOR,
            emptyList(), now - 86_400_000L * 9))
    }

    @Test
    fun devices() {
        appearance(Appearance.DARK)
        shoot(main(tab = R.id.tab_devices), "tab_devices_empty")
        seedDevices()
        shoot(main(tab = R.id.tab_devices), "tab_devices")
        val intent = Intent(app, DeviceActivity::class.java).putExtra(DeviceActivity.EXTRA_ADDRESS, "AA:BB:CC:00:00:01")
        shoot(screen<DeviceActivity>(intent), "device_detail")
        shoot(screen<CompatibilityActivity>(), "compatibility")
        shoot(screen<DiagnosticsActivity>(), "diagnostics")
    }

    @Test
    fun panels() {
        appearance(Appearance.DARK)
        shoot(main(tab = R.id.tab_panels), "tab_panels_free")
        pro(true)
        val panel = Panel(name = "Living room", components = Library.components(app, Library.PanelTemplate.TV))
        PanelStore(app).save(panel)
        PanelStore(app).save(Panel(name = "Presentation", components = Library.components(app, Library.PanelTemplate.PRESENTATION)))
        val macro = Macro(name = "Open YouTube", steps = Library.steps(Library.MacroExample.YOUTUBE))
        MacroStore(app).save(macro)
        shoot(main(tab = R.id.tab_panels), "tab_panels")
        Prefs(app).lastPanelId = panel.id
        shoot(main(), "control_panel")
        Prefs(app).lastPanelId = null
        val editor = Intent(app, PanelEditorActivity::class.java).putExtra(PanelEditorActivity.EXTRA_PANEL, panel.id)
        shoot(screen<PanelEditorActivity>(editor), "panel_editor")
        val macroEditor = Intent(app, MacroEditorActivity::class.java).putExtra(MacroEditorActivity.EXTRA_MACRO, macro.id)
        shoot(screen<MacroEditorActivity>(macroEditor), "macro_editor")
        pro(false)
    }

    @Test
    fun light() {
        appearance(Appearance.LIGHT)
        shoot(main(ControlMode.KEYBOARD), "light_control_keyboard")
        shoot(main(ControlMode.TRACKPAD), "light_control_trackpad")
        shoot(main(ControlMode.REMOTE), "light_control_remote")
        shoot(main(ControlMode.MEDIA), "light_control_media")
        seedSnippets()
        shoot(main(tab = R.id.tab_snippets), "light_tab_snippets")
        shoot(screen<SettingsActivity>(), "light_settings")
    }

    @Test
    fun screens() {
        appearance(Appearance.DARK)
        shoot(screen<SettingsActivity>(), "settings")
        val doc = Intent(app, DocActivity::class.java)
            .putExtra(DocActivity.EXTRA_ASSET, "privacy.txt")
            .putExtra(DocActivity.EXTRA_TITLE, R.string.privacy_title)
        shoot(screen<DocActivity>(doc), "doc_privacy")
        shoot(screen<ProActivity>(), "pro")
    }

    /** The keystore is not available under Robolectric, so rows are written as stored. */
    private fun seedSnippets() {
        val rows = listOf(
            Triple("Home Wi-Fi", "WIFI", true),
            Triple("Netflix email", "ACCOUNT", false),
            Triple("Holiday photos", "LINK", false),
            Triple("Address", "TEXT", false)
        )
        val arr = org.json.JSONArray()
        rows.forEachIndexed { i, (label, cat, secret) ->
            arr.put(org.json.JSONObject().put("id", "s$i").put("label", label).put("value", "x")
                .put("secret", secret).put("cat", cat))
        }
        app.getSharedPreferences("beampad_snippets", Context.MODE_PRIVATE).edit()
            .putString("snippets", arr.toString()).commit()
    }

    @Test
    fun snippets() {
        appearance(Appearance.DARK)
        shoot(main(tab = R.id.tab_snippets), "tab_snippets_empty")
        val activity = main(tab = R.id.tab_snippets)
        activity.findViewById<android.widget.LinearLayout>(R.id.actions).getChildAt(0).performClick()
        shootDialog("sheet_add_snippet")
        pro(true)
        seedSnippets()
        shoot(main(tab = R.id.tab_snippets), "tab_snippets")
        pro(false)
    }

    @Test
    fun onboarding() {
        appearance(Appearance.DARK)
        app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", false).commit()
        val activity = screen<OnboardingActivity>()
        shoot(activity, "onboarding_1")
        activity.findViewById<View>(R.id.next).performClick()
        settle()
        shoot(activity, "onboarding_2")
        activity.findViewById<View>(R.id.next).performClick()
        settle()
        shoot(activity, "onboarding_3")
    }
}
