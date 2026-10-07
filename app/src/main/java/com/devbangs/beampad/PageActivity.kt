package com.devbangs.beampad

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.devbangs.beampad.databinding.ActivityPageBinding

/**
 * A pushed screen built from the shared page template: header with Back,
 * scrolling content, optional fixed footer. Subclasses fill [render],
 * which runs on create, on resume and whenever they call [refresh], so a
 * page always shows current state without hand-written view updates.
 *
 * [service] is bound without auto-create when [wantsService] is true:
 * opening a detail screen never starts Bluetooth on its own.
 */
abstract class PageActivity : BeamActivity() {

    protected lateinit var page: ActivityPageBinding

    protected val app: BeamPadApp get() = application as BeamPadApp
    protected val prefs: Prefs get() = app.prefs

    protected open val wantsService: Boolean = false
    protected var service: HidService? = null
        private set
    private var bound = false

    private val serviceListener: () -> Unit = { runOnUiThread { refresh() } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? HidService.LocalBinder)?.service ?: return
            service = s
            s.addListener(serviceListener)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private val entitlementObserver: (Entitlements.Change) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = ActivityPageBinding.inflate(layoutInflater)
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        Ui.header(page.header.root, title(), subtitle()) { finish() }
        app.observeEntitlement(entitlementObserver)
        if (wantsService) {
            bound = runCatching {
                bindService(Intent(this, HidService::class.java), connection, 0)
            }.getOrDefault(false)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.stopObservingEntitlement(entitlementObserver)
        service?.removeListener(serviceListener)
        if (bound) runCatching { unbindService(connection) }
    }

    protected abstract fun title(): CharSequence
    protected open fun subtitle(): CharSequence? = null

    /** Rebuilds the content from current state, keeping the scroll position. */
    protected fun refresh() {
        if (!::page.isInitialized || isFinishing) return
        val y = page.scroll.scrollY
        Ui.header(page.header.root, title(), subtitle()) { finish() }
        page.content.removeAllViews()
        render()
        page.scroll.post { page.scroll.scrollTo(0, y) }
    }

    protected abstract fun render()
}
