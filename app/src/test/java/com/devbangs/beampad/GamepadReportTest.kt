package com.devbangs.beampad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gamepad report must be exactly what its descriptor declares, or a
 * device misreads every field after the first mismatch; and the D-pad's
 * held directions must give the right hat, diagonals included.
 */
class GamepadReportTest {

    @Test
    fun reportMatchesDescriptor() {
        val all = HidReports.descriptor(gamepad = true)
        val pad = all.copyOfRange(HidReports.descriptor(gamepad = false).size, all.size)
        var i = 0
        var size = 0
        var count = 0
        var bits = 0
        while (i < pad.size) {
            val prefix = pad[i].toInt() and 0xFF
            val length = if (prefix and 0x03 == 3) 4 else prefix and 0x03
            var data = 0
            for (k in 0 until length) data = data or ((pad[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            when (prefix and 0xFC) {
                0x74 -> size = data              // Report Size
                0x94 -> count = data             // Report Count
                0x80 -> bits += size * count     // Input
            }
            i += 1 + length
        }
        val report = HidReports.gamepad(0, HidReports.HAT_CENTER, 0, 0, 0, 0, 0, 0)
        assertEquals(report.size * 8, bits)
    }

    @Test
    fun triggersAndSticksLandInPlace() {
        val report = HidReports.gamepad(HidReports.PAD_A or HidReports.PAD_R3, HidReports.HAT_DOWN_LEFT,
            -127, 127, 5, -5, 255, 128)
        assertEquals(0x01, report[0].toInt() and 0xFF)
        assertEquals(0x40, report[1].toInt() and 0xFF)
        assertEquals(HidReports.HAT_DOWN_LEFT, report[2].toInt())
        assertEquals(-127, report[3].toInt())
        assertEquals(127, report[4].toInt())
        assertEquals(255, report[7].toInt() and 0xFF)
        assertEquals(128, report[8].toInt() and 0xFF)
    }

    @Test
    fun dpadDirectionsGiveHats() {
        val up = HidReports.DPAD_UP
        val right = HidReports.DPAD_RIGHT
        val down = HidReports.DPAD_DOWN
        val left = HidReports.DPAD_LEFT
        assertEquals(HidReports.HAT_CENTER, HidReports.hat(0))
        assertEquals(HidReports.HAT_UP, HidReports.hat(up))
        assertEquals(HidReports.HAT_UP_RIGHT, HidReports.hat(up or right))
        assertEquals(HidReports.HAT_RIGHT, HidReports.hat(right))
        assertEquals(HidReports.HAT_DOWN_RIGHT, HidReports.hat(down or right))
        assertEquals(HidReports.HAT_DOWN, HidReports.hat(down))
        assertEquals(HidReports.HAT_DOWN_LEFT, HidReports.hat(down or left))
        assertEquals(HidReports.HAT_LEFT, HidReports.hat(left))
        assertEquals(HidReports.HAT_UP_LEFT, HidReports.hat(up or left))
        // Opposites cancel, leaving the other axis.
        assertEquals(HidReports.HAT_CENTER, HidReports.hat(up or down))
        assertEquals(HidReports.HAT_LEFT, HidReports.hat(up or down or left))
    }
}
