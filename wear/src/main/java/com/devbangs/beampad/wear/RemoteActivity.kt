package com.devbangs.beampad.wear

import android.app.Activity
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * The watch remote: arrows, OK, Back, Home, Mute and Play/pause, with the
 * crown for volume. Each press is a message to the BeamPad app on the
 * phone, which sends it to the TV or computer it is connected to and
 * answers with the connection's state for the line at the top.
 *
 * The phone app does the Bluetooth; the watch only needs to reach the
 * phone. Messages carry the remote's action ids ("up", "vol_up"…), which
 * the phone accepts only from a fixed list.
 */
class RemoteActivity : Activity() {

    private lateinit var status: TextView
    private val messages by lazy { Wearable.getMessageClient(this) }

    /** Crown travel not yet turned into a volume step. */
    private var crown = 0f

    private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == PATH_STATE) runOnUiThread { showState(event.data) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        status = findViewById(R.id.status)

        mapOf(
            R.id.up to "up", R.id.down to "down", R.id.left to "left", R.id.right to "right",
            R.id.ok to "ok", R.id.back to "back", R.id.home to "home",
            R.id.mute to "mute", R.id.playPause to "play_pause"
        ).forEach { (id, action) ->
            findViewById<View>(id).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                send(PATH_KEY, action)
            }
        }

        // The crown: volume up and down, one step per notch of travel.
        val root = findViewById<View>(R.id.root)
        root.setOnGenericMotionListener { _, event -> onCrown(event) }
        root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        messages.addListener(listener)
        // Asks for the state, so the top line is right before the first press.
        send(PATH_HELLO, "")
    }

    override fun onPause() {
        super.onPause()
        messages.removeListener(listener)
    }

    private fun onCrown(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) return false
        // Clockwise is negative on this axis: turn it so clockwise is louder.
        crown += -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        while (crown >= CROWN_STEP) {
            crown -= CROWN_STEP
            send(PATH_KEY, "vol_up")
        }
        while (crown <= -CROWN_STEP) {
            crown += CROWN_STEP
            send(PATH_KEY, "vol_down")
        }
        return true
    }

    /** To the phone nearest the watch: the one it is paired with. */
    private fun send(path: String, payload: String) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                if (phone == null) {
                    status.setText(R.string.no_phone)
                    return@addOnSuccessListener
                }
                messages.sendMessage(phone.id, path, payload.toByteArray(Charsets.UTF_8))
                    .addOnFailureListener { status.setText(R.string.phone_app) }
            }
            .addOnFailureListener { status.setText(R.string.no_phone) }
    }

    /** "1" or "0" (connected or not), then the phone's words for the state. */
    private fun showState(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        status.text = if (text.length > 1) text.substring(1) else getString(R.string.phone_app)
        status.alpha = if (text.startsWith("1")) 1f else 0.7f
    }

    private companion object {
        // Shared with the phone app's WearRemoteService.
        const val PATH_KEY = "/beampad/key"
        const val PATH_HELLO = "/beampad/hello"
        const val PATH_STATE = "/beampad/state"

        /** Crown travel per volume step. */
        const val CROWN_STEP = 1f
    }
}
