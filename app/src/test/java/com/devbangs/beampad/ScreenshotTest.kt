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

    @Test
    fun tabs() {
        appearance(Appearance.DARK)
        shoot(main(tab = R.id.tab_devices), "tab_devices")
        shoot(main(tab = R.id.tab_panels), "tab_panels")
        shoot(main(tab = R.id.tab_snippets), "tab_snippets")
    }

    @Test
    fun light() {
        appearance(Appearance.LIGHT)
        shoot(main(ControlMode.KEYBOARD), "light_control_keyboard")
        shoot(main(ControlMode.TRACKPAD), "light_control_trackpad")
        shoot(main(ControlMode.REMOTE), "light_control_remote")
        shoot(main(ControlMode.MEDIA), "light_control_media")
    }

    @Test
    fun screens() {
        appearance(Appearance.DARK)
        shoot(screen<SettingsActivity>(), "settings")
        shoot(screen<ProActivity>(), "pro")
        shoot(screen<OnboardingActivity>(), "onboarding")
    }

    @Test
    fun addSnippet() {
        appearance(Appearance.DARK)
        val activity = main(tab = R.id.tab_snippets)
        activity.findViewById<View>(R.id.add).performClick()
        shootDialog("sheet_add_snippet")
    }
}
