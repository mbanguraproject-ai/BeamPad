package com.devbangs.beampad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * The air mouse (Pro): the phone's gyroscope moves the pointer, held like a
 * remote with its top edge towards the TV. Turning left and right spins
 * the phone about the axis through its screen, tilting up and down about
 * its width, so those two rates become the pointer's x and y.
 *
 * Runs only between [start] and [stop] (while a finger holds the pad), so
 * it cannot drift on its own, and nothing listens to the sensor otherwise.
 */
class AirPointer(context: Context, private val move: (dx: Int, dy: Int) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** False on phones without a gyroscope; the toggle is hidden there. */
    val available: Boolean get() = gyro != null

    /** Pointer speed multiplier (Settings speed, precision mode). */
    var gain = 1f

    private var running = false
    private var lastTimestamp = 0L
    private var carryX = 0f
    private var carryY = 0f

    fun start() {
        if (running || gyro == null) return
        running = true
        lastTimestamp = 0L
        carryX = 0f
        carryY = 0f
        sensors?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (lastTimestamp == 0L) {
            lastTimestamp = event.timestamp
            return
        }
        val seconds = (event.timestamp - lastTimestamp) / NANOS_PER_SECOND
        lastTimestamp = event.timestamp
        val yaw = still(event.values[2])
        val pitch = still(event.values[0])
        // Sub-pixel remainders are carried, so slow, careful pointing moves.
        val x = -yaw * seconds * PIXELS_PER_RADIAN * gain + carryX
        val y = -pitch * seconds * PIXELS_PER_RADIAN * gain + carryY
        val dx = x.toInt()
        val dy = y.toInt()
        carryX = x - dx
        carryY = y - dy
        if (dx != 0 || dy != 0) move(dx, dy)
    }

    /** Hand tremor and sensor noise below this are not movement. */
    private fun still(rate: Float): Float = if (abs(rate) < DEAD_ZONE) 0f else rate

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** About 470 px for a 30 degree turn at normal speed. */
        const val PIXELS_PER_RADIAN = 900f
        const val DEAD_ZONE = 0.03f
    }
}
