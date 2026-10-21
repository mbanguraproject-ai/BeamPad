package com.devbangs.beampad

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * The phone's end of the watch remote (the wear module). The watch sends
 * remote action ids ("up", "vol_up"…); each one in [ALLOWED] goes to the
 * connected device like a press on the phone's remote, and every message is
 * answered with the connection's state for the watch's top line.
 *
 * Only messages from watches paired with this phone reach here, and only
 * the fixed list of remote keys is accepted: no text, macros or mouse.
 */
class WearRemoteService : WearableListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_KEY -> {
                val id = String(event.data, Charsets.UTF_8)
                if (id in ALLOWED) {
                    val action = Actions.byId(id)?.action
                    // On the main thread, as every other control sends.
                    if (action != null) main.post {
                        HidService.running?.takeIf { it.isReady() }?.engine?.perform(action)
                    }
                }
                reply(event.sourceNodeId)
            }
            PATH_HELLO -> reply(event.sourceNodeId)
        }
    }

    /** "1" or "0" (connected or not), then the state in words. */
    private fun reply(node: String) {
        val service = HidService.running
        val connected = service?.isReady() == true
        val text = (if (connected) "1" else "0") +
            (service?.shortStatus() ?: getString(R.string.wear_open_phone))
        runCatching {
            Wearable.getMessageClient(this).sendMessage(node, PATH_STATE, text.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        // Shared with the wear module's RemoteActivity.
        const val PATH_KEY = "/beampad/key"
        const val PATH_HELLO = "/beampad/hello"
        const val PATH_STATE = "/beampad/state"

        val ALLOWED = setOf(
            "up", "down", "left", "right", "ok", "back", "home",
            "play_pause", "vol_up", "vol_down", "mute"
        )
    }
}
