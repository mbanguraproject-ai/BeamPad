package com.devbangs.beampad

import androidx.fragment.app.Fragment

/**
 * The built-in control surfaces on the Control tab. Custom panels sit
 * alongside these but are not modes: they are user data (see [PanelStore]).
 */
enum class ControlMode(val labelRes: Int, val iconRes: Int, val pro: Features.Pro?) {
    KEYBOARD(R.string.mode_keyboard, R.drawable.ic_keyboard, null),
    REMOTE(R.string.mode_remote, R.drawable.ic_arrows_out_cardinal, null),
    MEDIA(R.string.mode_media, R.drawable.ic_play_pause, null),
    TRACKPAD(R.string.mode_trackpad, R.drawable.ic_cursor, null),
    MOUSE(R.string.mode_mouse, R.drawable.ic_mouse_simple, null),
    PRESENTATION(R.string.mode_presentation, R.drawable.ic_presentation, Features.Pro.PRESENTATION);

    fun newFragment(): Fragment = when (this) {
        KEYBOARD -> KeyboardFragment()
        REMOTE -> RemoteFragment()
        MEDIA -> MediaFragment()
        TRACKPAD -> TrackpadFragment()
        MOUSE -> MouseFragment()
        PRESENTATION -> PresentationFragment()
    }
}
