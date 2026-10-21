package com.devbangs.beampad.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the watch remote on a round face to build/screenshots, like the
 * phone app's screenshot test. Nothing is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w192dp-h192dp-round-watch-xhdpi")
class ScreenshotTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun remote() {
        shoot(null, "watch_remote")
        shoot(app.getString(R.string.no_phone), "watch_remote_no_phone")
    }

    private fun shoot(status: String?, name: String) {
        val themed = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault)
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_remote, null)
        if (status != null) root.findViewById<TextView>(R.id.status).text = status
        val size = app.resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The round face: what lies outside the circle is not on the watch.
        canvas.drawColor(Color.rgb(0x3C, 0x40, 0x43))
        canvas.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) })
        root.draw(canvas)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
