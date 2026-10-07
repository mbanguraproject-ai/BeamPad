package com.devbangs.beampad

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import java.util.Locale

/**
 * Settings, grouped as the blueprint lays them out: Connection, Keyboard,
 * Trackpad, Remote, Snippets, Appearance, Accessibility, Privacy, then
 * purchases, help and about. Every setting the app has lives here, built
 * from the shared rows, so the page reads like one product.
 *
 * Pro settings are stored as chosen but shown and applied through
 * [Features], so a lapsed subscription reverts behaviour without losing
 * the user's choices.
 */
class SettingsActivity : PageActivity() {

    /** Bound without auto-create, for the test characters, which need a live connection. */
    override val wantsService = true

    override fun title(): CharSequence = getString(R.string.settings)

    override fun render() {
        val c = page.content
        val pro = Features.isPro(this)

        plan(c, pro)
        connection(c)
        keyboard(c, pro)
        trackpad(c, pro)
        remote(c)
        snippets(c)
        appearance(c)
        accessibility(c)
        privacy(c)
        help(c)
        about(c)
    }

    // ---- Groups -------------------------------------------------------------------

    private fun plan(c: ViewGroup, pro: Boolean) {
        Ui.space(c, 4)
        val card = Ui.card(c)
        val row = Ui.row(card,
            getString(if (pro) R.string.settings_pro_active else R.string.settings_pro_title),
            getString(if (pro) R.string.settings_pro_active_body else R.string.settings_pro_body),
            R.drawable.ic_crown_simple_fill, Ui.Tone.ACCENT) { ProActivity.open(this, null) }
        row.subtitle.maxLines = 3
        if (!pro) {
            row.trailing.addView(Ui.button(row.trailing, Ui.ButtonKind.SMALL_PRIMARY, getString(R.string.see_pro)) {
                ProActivity.open(this, null)
            })
        }
        Ui.divider(card)
        Ui.row(card, getString(R.string.settings_restore), getString(R.string.settings_restore_body), R.drawable.ic_arrow_clockwise) {
            restore()
        }
    }

    private fun connection(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_connection))
        val card = Ui.card(c)
        Ui.switchRow(card, getString(R.string.settings_reconnect), getString(R.string.set_reconnect_body),
            R.drawable.ic_bluetooth_connected, prefs.autoReconnect) { prefs.autoReconnect = it; it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_retry), getString(R.string.set_retry_body),
            R.drawable.ic_arrow_clockwise, prefs.retryAfterDrop) { prefs.retryAfterDrop = it; it }
        Ui.divider(card)
        picker(card, R.string.set_timeout, R.drawable.ic_timer, prefs.connectTimeout,
            Prefs.ConnectTimeout.entries.map { it to getString(R.string.set_seconds, (it.millis / 1000).toInt()) }) {
            prefs.connectTimeout = it
        }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.settings_screen_on), getString(R.string.set_screen_on_body),
            R.drawable.ic_sun, prefs.keepScreenOn) { prefs.keepScreenOn = it; it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_sound), getString(R.string.set_sound_body),
            R.drawable.ic_speaker_high, prefs.connectionSound) { prefs.connectionSound = it; it }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.set_devices), getString(R.string.set_devices_body), R.drawable.ic_devices) {
            MainActivity.openTab(this, R.id.tab_devices)
        }
        Ui.divider(card)
        val lastName = service?.lastHostName()
        val forget = Ui.row(card, getString(R.string.settings_forget),
            when {
                prefs.lastHost == null -> getString(R.string.settings_forget_none)
                lastName != null -> lastName
                else -> getString(R.string.settings_forget_hint)
            }, R.drawable.ic_x) {
            prefs.lastHost = null
            Toast.makeText(this, R.string.settings_forgot, Toast.LENGTH_SHORT).show()
            refresh()
        }
        forget.enabled(prefs.lastHost != null)
    }

    private fun keyboard(c: ViewGroup, pro: Boolean) {
        Ui.section(c, getString(R.string.set_group_keyboard))
        val card = Ui.card(c)
        picker(card, R.string.settings_layout, R.drawable.ic_globe, prefs.layout,
            HidReports.Layout.entries.map { it to it.label }, subtitle = getString(R.string.set_layout_body)) {
            prefs.layout = it
        }
        Ui.divider(card)
        Ui.row(card, getString(R.string.settings_probe), getString(R.string.set_probe_body, HidReports.PROBE),
            R.drawable.ic_text_aa) { sendProbe() }
        Ui.divider(card)
        val (live, _) = Ui.switchRow(card, getString(R.string.settings_live), getString(R.string.set_live_body),
            R.drawable.ic_lightning, Features.liveTyping(this)) {
            if (!requirePro(Features.Pro.LIVE_TYPING)) false else { prefs.liveTyping = it; it }
        }
        live.pro(!pro)
        Ui.divider(card)
        picker(card, R.string.set_enter, R.drawable.ic_arrow_elbow_down_left, prefs.enterBehavior, listOf(
            Prefs.EnterBehavior.SEND_AND_ENTER to getString(R.string.set_enter_both),
            Prefs.EnterBehavior.SEND_ONLY to getString(R.string.set_enter_send)
        ), subtitle = getString(R.string.set_enter_body)) { prefs.enterBehavior = it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_modifiers), getString(R.string.set_modifiers_body),
            R.drawable.ic_command, prefs.showModifiers) { prefs.showModifiers = it; it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_fkeys), getString(R.string.set_fkeys_body),
            R.drawable.ic_function, prefs.showFunctionKeys) { prefs.showFunctionKeys = it; it }
    }

    private fun trackpad(c: ViewGroup, pro: Boolean) {
        Ui.section(c, getString(R.string.set_group_trackpad))
        val card = Ui.card(c)
        Ui.switchRow(card, getString(R.string.set_tap_click), getString(R.string.set_tap_click_body),
            R.drawable.ic_hand_tap, prefs.tapToClick) { prefs.tapToClick = it; it }
        Ui.divider(card)
        picker(card, R.string.set_touch, R.drawable.ic_hand_pointing, prefs.touchResponse, listOf(
            Prefs.TouchResponse.RELAXED to getString(R.string.set_touch_relaxed),
            Prefs.TouchResponse.NORMAL to getString(R.string.set_touch_normal),
            Prefs.TouchResponse.QUICK to getString(R.string.set_touch_quick)
        ), subtitle = getString(R.string.set_touch_body)) { prefs.touchResponse = it }

        // Tuning: Pro. The sliders show the values in effect.
        Ui.divider(card)
        val (speedRow, speed) = Ui.sliderRow(card, getString(R.string.settings_pointer), R.drawable.ic_cursor,
            0.8f, 3.2f, 0.2f, Features.pointerSpeed(this), { multiplier(it / Prefs.DEFAULT_POINTER_SPEED) }) {
            prefs.pointerSpeed = it
        }
        speedRow.pro(!pro)
        speed.isEnabled = pro
        Ui.divider(card)
        val (accelRow, accel) = Ui.sliderRow(card, getString(R.string.set_accel), R.drawable.ic_gauge,
            0f, 1f, 0.1f, Features.acceleration(this), { percent(it) }) { prefs.acceleration = it }
        accelRow.pro(!pro)
        accel.isEnabled = pro
        Ui.divider(card)
        val (scrollRow, scroll) = Ui.sliderRow(card, getString(R.string.settings_scroll), R.drawable.ic_mouse_scroll,
            0.5f, 2.5f, 0.25f, Features.scrollSpeed(this), { multiplier(it) }) { prefs.scrollSpeed = it }
        scrollRow.pro(!pro)
        scroll.isEnabled = pro
        if (!pro) listOf(speedRow, accelRow, scrollRow).forEach { r ->
            r.root.setOnClickListener { requirePro(Features.Pro.TRACKPAD) }
        }
        Ui.divider(card)
        val (natural, _) = Ui.switchRow(card, getString(R.string.set_natural), getString(R.string.set_natural_body),
            R.drawable.ic_arrows_down_up, Features.reverseScroll(this)) {
            if (!requirePro(Features.Pro.TRACKPAD)) false else { prefs.reverseScroll = it; it }
        }
        natural.pro(!pro)
        Ui.divider(card)
        val (pinch, _) = Ui.switchRow(card, getString(R.string.set_pinch), getString(R.string.set_pinch_body),
            R.drawable.ic_magnifying_glass_plus, Features.pinchToZoom(this)) {
            if (!requirePro(Features.Pro.TRACKPAD)) false else { prefs.pinchToZoom = it; it }
        }
        pinch.pro(!pro)

        // Three-finger swipes.
        Ui.section(c, getString(R.string.set_group_gestures))
        val gestures = Ui.card(c)
        Prefs.SwipeDirection.entries.forEachIndexed { i, dir ->
            if (i > 0) Ui.divider(gestures)
            val current = Actions.byId(Features.gesture(this, dir))
            val (row, _) = Ui.valueRow(gestures, getString(directionLabel(dir)),
                current?.let { Actions.label(this, it) } ?: getString(R.string.none), icon = directionIcon(dir)) {
                if (!requirePro(Features.Pro.TRACKPAD)) return@valueRow
                pickGesture(dir)
            }
            row.pro(!pro)
        }
    }

    private fun remote(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_remote))
        val card = Ui.card(c)
        picker(card, R.string.set_remote_layout, R.drawable.ic_arrows_out_cardinal, prefs.remoteLayout, listOf(
            Prefs.RemoteLayout.MINIMAL to getString(R.string.set_remote_minimal),
            Prefs.RemoteLayout.STANDARD to getString(R.string.set_remote_standard),
            Prefs.RemoteLayout.FULL to getString(R.string.set_remote_full)
        ), details = mapOf(
            Prefs.RemoteLayout.MINIMAL to getString(R.string.set_remote_minimal_body),
            Prefs.RemoteLayout.STANDARD to getString(R.string.set_remote_standard_body),
            Prefs.RemoteLayout.FULL to getString(R.string.set_remote_full_body)
        )) { prefs.remoteLayout = it }
        Ui.divider(card)
        picker(card, R.string.set_button_size, R.drawable.ic_squares_four, prefs.buttonSize, listOf(
            Prefs.ButtonSize.COMPACT to getString(R.string.set_size_compact),
            Prefs.ButtonSize.REGULAR to getString(R.string.set_size_regular),
            Prefs.ButtonSize.LARGE to getString(R.string.set_size_large)
        )) { prefs.buttonSize = it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_volume_buttons), getString(R.string.set_volume_buttons_body),
            R.drawable.ic_speaker_simple_high, prefs.volumeButtons) { prefs.volumeButtons = it; it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.settings_haptics), getString(R.string.set_haptics_body),
            R.drawable.ic_vibrate, prefs.haptics) { prefs.haptics = it; refresh(); it }
        if (prefs.haptics) {
            Ui.divider(card)
            picker(card, R.string.set_haptic_strength, R.drawable.ic_pulse, prefs.hapticStrength, listOf(
                Prefs.HapticStrength.LIGHT to getString(R.string.set_strength_light),
                Prefs.HapticStrength.MEDIUM to getString(R.string.set_strength_medium),
                Prefs.HapticStrength.STRONG to getString(R.string.set_strength_strong)
            )) {
                prefs.hapticStrength = it
                Haptics.tick(page.content)
            }
        }
    }

    private fun snippets(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_snippets))
        val card = Ui.card(c)
        picker(card, R.string.set_autolock, R.drawable.ic_lock_simple, prefs.snippetAutoLock, listOf(
            Prefs.AutoLock.EVERY_SEND to getString(R.string.set_autolock_every),
            Prefs.AutoLock.ONE_MINUTE to getString(R.string.set_autolock_minute),
            Prefs.AutoLock.FIVE_MINUTES to getString(R.string.set_autolock_five)
        ), subtitle = getString(R.string.set_autolock_body)) {
            prefs.snippetAutoLock = it
            SnippetLock.lock()
        }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_clipboard), getString(R.string.set_clipboard_body),
            R.drawable.ic_clipboard_text, prefs.confirmClipboard) { prefs.confirmClipboard = it; it }
    }

    private fun appearance(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_appearance))
        val card = Ui.card(c)
        picker(card, R.string.set_theme, R.drawable.ic_moon, prefs.appearance,
            Appearance.entries.map { it to getString(it.labelRes) }) {
            prefs.appearance = it
            // BeamActivity re-themes on resume; this screen redraws now.
            recreate()
        }
    }

    private fun accessibility(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_accessibility))
        val card = Ui.card(c)
        Ui.switchRow(card, getString(R.string.set_large), getString(R.string.set_large_body),
            R.drawable.ic_frame_corners, prefs.largeControls) { prefs.largeControls = it; it }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_contrast), getString(R.string.set_contrast_body),
            R.drawable.ic_sun, prefs.highContrast) {
            prefs.highContrast = it
            recreate()
            it
        }
        Ui.divider(card)
        Ui.switchRow(card, getString(R.string.set_motion), getString(R.string.set_motion_body),
            R.drawable.ic_sparkle_fill, prefs.reduceMotion) { prefs.reduceMotion = it; it }
    }

    private fun privacy(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_privacy))
        val card = Ui.card(c)
        Ui.linkRow(card, getString(R.string.set_local), getString(R.string.set_local_body), R.drawable.ic_shield_check, Ui.Tone.LIVE) {
            showDataExplanation()
        }
        // AdMob requires a way to revisit the consent choice, but only where
        // a consent form applies in the first place.
        if (Consent.privacyOptionsRequired(this)) {
            Ui.divider(card)
            Ui.linkRow(card, getString(R.string.settings_privacy_options), null, R.drawable.ic_sliders) {
                Consent.showPrivacyOptions(this)
            }
        }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.privacy_title), null, R.drawable.ic_file_text) {
            openDoc("privacy.txt", R.string.privacy_title)
        }
    }

    private fun help(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_help))
        val card = Ui.card(c)
        Ui.linkRow(card, getString(R.string.diag_title), getString(R.string.diag_row_body), R.drawable.ic_stethoscope) {
            DiagnosticsActivity.open(this)
        }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.compat_title), getString(R.string.compat_row_body), R.drawable.ic_seal_check) {
            CompatibilityActivity.open(this)
        }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.panels_tab_macros), getString(R.string.macros_subtitle), R.drawable.ic_magic_wand) {
            MacrosActivity.open(this)
        }
    }

    private fun about(c: ViewGroup) {
        Ui.section(c, getString(R.string.set_group_about))
        val card = Ui.card(c)
        Ui.linkRow(card, getString(R.string.settings_rate), null, R.drawable.ic_star) { openStoreListing() }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.terms_title), null, R.drawable.ic_file_text) { openDoc("terms.txt", R.string.terms_title) }
        Ui.divider(card)
        Ui.linkRow(card, getString(R.string.licences_title), null, R.drawable.ic_code) { openDoc("licences.txt", R.string.licences_title) }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        c.addView(android.widget.TextView(this).apply {
            setTextAppearance(R.style.Text_Small_Faint)
            text = getString(R.string.version_format, version)
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 8))
        })
    }

    // ---- Helpers ------------------------------------------------------------------

    /** A row showing the current choice that opens a picker sheet. */
    private fun <T> picker(
        parent: ViewGroup,
        title: Int,
        icon: Int,
        current: T,
        options: List<Pair<T, String>>,
        subtitle: String? = null,
        details: Map<T, String> = emptyMap(),
        onPick: (T) -> Unit
    ) {
        val value = options.firstOrNull { it.first == current }?.second.orEmpty()
        Ui.valueRow(parent, getString(title), value, icon = icon) {
            Sheets.choose(this, getString(title),
                options.map { (v, label) -> Sheets.Choice(v, label, details[v]) }, current, subtitle) {
                onPick(it)
                refresh()
            }
        }
    }

    private fun pickGesture(dir: Prefs.SwipeDirection) {
        ActionPicker.show(this, getString(directionLabel(dir)),
            setOf(Actions.Group.NAVIGATION, Actions.Group.SHORTCUTS, Actions.Group.MEDIA, Actions.Group.MOUSE),
            current = Actions.byId(Features.gesture(this, dir))?.action) { choice ->
            prefs.setGesture(dir, choice.named?.id)
            refresh()
        }
    }

    private fun directionLabel(d: Prefs.SwipeDirection) = when (d) {
        Prefs.SwipeDirection.UP -> R.string.set_swipe_up
        Prefs.SwipeDirection.DOWN -> R.string.set_swipe_down
        Prefs.SwipeDirection.LEFT -> R.string.set_swipe_left
        Prefs.SwipeDirection.RIGHT -> R.string.set_swipe_right
    }

    private fun directionIcon(d: Prefs.SwipeDirection) = when (d) {
        Prefs.SwipeDirection.UP -> R.drawable.ic_arrow_up
        Prefs.SwipeDirection.DOWN -> R.drawable.ic_arrow_down
        Prefs.SwipeDirection.LEFT -> R.drawable.ic_arrow_left
        Prefs.SwipeDirection.RIGHT -> R.drawable.ic_arrow_right
    }

    private fun requirePro(feature: Features.Pro): Boolean {
        if (Features.isPro(this)) return true
        ProActivity.open(this, feature)
        return false
    }

    /** What is stored, where, and what never leaves the phone. */
    private fun showDataExplanation() {
        val sheet = Sheet(this).title(getString(R.string.set_local)).subtitle(getString(R.string.set_local_sheet_body))
        listOf(
            R.drawable.ic_vault to R.string.set_data_snippets,
            R.drawable.ic_devices to R.string.set_data_devices,
            R.drawable.ic_layout to R.string.set_data_panels,
            R.drawable.ic_keyboard to R.string.set_data_typing,
            R.drawable.ic_pulse to R.string.set_data_log,
            R.drawable.ic_crown_simple_fill to R.string.set_data_ads
        ).forEach { (icon, text) ->
            Ui.row(sheet.content, getString(text), null, icon).apply {
                title.setTextAppearance(R.style.Text_Body)
                title.maxLines = 4
                root.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = -Ui.dp(context, 12) }
            }
        }
        sheet.primary(getString(R.string.done)) { true }
        sheet.show()
    }

    private fun sendProbe() {
        val s = service
        if (s == null || !s.isReady()) {
            Toast.makeText(this, R.string.probe_needs_connection, Toast.LENGTH_LONG).show()
            return
        }
        s.typeText(HidReports.PROBE) { _, _ -> }
        Toast.makeText(this, getString(R.string.probe_sent, HidReports.PROBE), Toast.LENGTH_LONG).show()
    }

    private fun restore() {
        app.billing.restore { answered ->
            val message = when {
                app.entitlements.isPro -> R.string.restore_pro
                app.entitlements.adsRemoved -> R.string.restore_ads_only
                answered -> R.string.restore_none
                else -> R.string.restore_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    /** Links to the listing rather than asking for a review: never a policy problem. */
    private fun openStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        if (runCatching { startActivity(market) }.isFailure) runCatching { startActivity(web) }
    }

    private fun multiplier(value: Float): String = String.format(Locale.getDefault(), "%.1f×", value)
    private fun percent(value: Float): String = String.format(Locale.getDefault(), "%d%%", (value * 100).toInt())

    private fun openDoc(asset: String, titleRes: Int) {
        startActivity(
            Intent(this, DocActivity::class.java)
                .putExtra(DocActivity.EXTRA_ASSET, asset)
                .putExtra(DocActivity.EXTRA_TITLE, titleRes)
        )
    }
}
