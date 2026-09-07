package com.devbangs.beampad

/**
 * HID keyboard report descriptor and character encoding.
 *
 * Report payload (Report ID 1) is 8 bytes:
 *   [0] modifier bitmask
 *   [1] reserved
 *   [2..7] up to six simultaneous key usage codes
 */
object HidReports {

    const val REPORT_ID = 1          // keyboard
    const val REPORT_ID_MOUSE = 2    // mouse
    const val REPORT_ID_CONSUMER = 3 // volume and media keys

    // Consumer Control usage codes (HID usage page 0x0C)
    const val CC_VOLUME_UP = 0x00E9
    const val CC_VOLUME_DOWN = 0x00EA
    const val CC_MUTE = 0x00E2
    const val CC_PLAY_PAUSE = 0x00CD

    const val BUTTON_NONE: Byte = 0x00
    const val BUTTON_LEFT: Byte = 0x01
    const val BUTTON_RIGHT: Byte = 0x02

    const val MOD_NONE: Byte = 0x00
    const val MOD_LEFT_SHIFT: Byte = 0x02
    const val MOD_RIGHT_ALT: Byte = 0x40   // AltGr, needed by continental layouts

    // Common control keys, by HID usage code.
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESC: Byte = 0x29
    const val KEY_BACKSPACE: Byte = 0x2A
    const val KEY_TAB: Byte = 0x2B
    const val KEY_SPACE: Byte = 0x2C
    const val KEY_RIGHT: Byte = 0x4F
    const val KEY_LEFT: Byte = 0x50
    const val KEY_DOWN: Byte = 0x51
    const val KEY_UP: Byte = 0x52

    val KEYBOARD_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                     // Usage Page (Generic Desktop)
        0x09, 0x06,                     // Usage (Keyboard)
        0xA1.toByte(), 0x01,            // Collection (Application)
        0x85.toByte(), 0x01,            //   Report ID (1)
        0x05, 0x07,                     //   Usage Page (Keyboard)
        0x19, 0xE0.toByte(),            //   Usage Min (LeftControl)
        0x29, 0xE7.toByte(),            //   Usage Max (Right GUI)
        0x15, 0x00,                     //   Logical Min (0)
        0x25, 0x01,                     //   Logical Max (1)
        0x75, 0x01,                     //   Report Size (1)
        0x95.toByte(), 0x08,            //   Report Count (8)
        0x81.toByte(), 0x02,            //   Input (Data,Var,Abs) - modifiers
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x75, 0x08,                     //   Report Size (8)
        0x81.toByte(), 0x03,            //   Input (Const) - reserved
        0x95.toByte(), 0x06,            //   Report Count (6)
        0x75, 0x08,                     //   Report Size (8)
        0x15, 0x00,                     //   Logical Min (0)
        0x25, 0x65,                     //   Logical Max (101)
        0x05, 0x07,                     //   Usage Page (Keyboard)
        0x19, 0x00,                     //   Usage Min (0)
        0x29, 0x65,                     //   Usage Max (101)
        0x81.toByte(), 0x00,            //   Input (Data,Array)
        0xC0.toByte(),                  // End Collection

        // --- Mouse, Report ID 2 ---
        0x05, 0x01,                     // Usage Page (Generic Desktop)
        0x09, 0x02,                     // Usage (Mouse)
        0xA1.toByte(), 0x01,            // Collection (Application)
        0x85.toByte(), 0x02,            //   Report ID (2)
        0x09, 0x01,                     //   Usage (Pointer)
        0xA1.toByte(), 0x00,            //   Collection (Physical)
        0x05, 0x09,                     //     Usage Page (Button)
        0x19, 0x01,                     //     Usage Min (Button 1)
        0x29, 0x03,                     //     Usage Max (Button 3)
        0x15, 0x00,                     //     Logical Min (0)
        0x25, 0x01,                     //     Logical Max (1)
        0x95.toByte(), 0x03,            //     Report Count (3)
        0x75, 0x01,                     //     Report Size (1)
        0x81.toByte(), 0x02,            //     Input (Data,Var,Abs) - buttons
        0x95.toByte(), 0x01,            //     Report Count (1)
        0x75, 0x05,                     //     Report Size (5)
        0x81.toByte(), 0x03,            //     Input (Const) - padding
        0x05, 0x01,                     //     Usage Page (Generic Desktop)
        0x09, 0x30,                     //     Usage (X)
        0x09, 0x31,                     //     Usage (Y)
        0x09, 0x38,                     //     Usage (Wheel)
        0x15, 0x81.toByte(),            //     Logical Min (-127)
        0x25, 0x7F,                     //     Logical Max (127)
        0x75, 0x08,                     //     Report Size (8)
        0x95.toByte(), 0x03,            //     Report Count (3)
        0x81.toByte(), 0x06,            //     Input (Data,Var,Rel) - X,Y,wheel
        0xC0.toByte(),                  //   End Collection
        0xC0.toByte(),                  // End Collection

        // --- Consumer Control, Report ID 3 ---
        // Volume lives on the consumer page, not the keyboard page, so it
        // needs its own collection.
        0x05, 0x0C,                     // Usage Page (Consumer)
        0x09, 0x01,                     // Usage (Consumer Control)
        0xA1.toByte(), 0x01,            // Collection (Application)
        0x85.toByte(), 0x03,            //   Report ID (3)
        0x15, 0x00,                     //   Logical Min (0)
        0x26, 0xFF.toByte(), 0x03,      //   Logical Max (1023)
        0x19, 0x00,                     //   Usage Min (0)
        0x2A, 0xFF.toByte(), 0x03,      //   Usage Max (1023)
        0x75, 0x10,                     //   Report Size (16)
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x81.toByte(), 0x00,            //   Input (Data,Array)
        0xC0.toByte()                   // End Collection
    )

    /** Consumer report: one 16-bit usage code, little endian. */
    fun consumer(usage: Int): ByteArray =
        byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())

    fun consumerRelease(): ByteArray = byteArrayOf(0, 0)

    /** Mouse report: buttons, then relative X, Y and wheel, each -127..127. */
    fun mouse(buttons: Byte, dx: Int, dy: Int, wheel: Int = 0): ByteArray =
        byteArrayOf(
            buttons,
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )

    fun press(modifier: Byte, keyCode: Byte): ByteArray =
        byteArrayOf(modifier, 0, keyCode, 0, 0, 0, 0, 0)

    fun release(): ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

    /**
     * Keyboard layout of the RECEIVING device.
     *
     * Only layouts with a verified table belong here. Adding an entry whose
     * mapping has not been tested against real hardware is worse than omitting
     * it: the user selects it, trusts it, and gets silent corruption.
     */
    enum class Layout(val label: String) {
        US("US QWERTY"),
        UK("UK QWERTY")
    }

    /**
     * Per-layout differences from US. Anything absent falls through to the US
     * mapping, which is correct for letters and most punctuation on layouts
     * that share the QWERTY letter block.
     */
    private val UK_OVERRIDES: Map<Char, Pair<Byte, Byte>> = mapOf(
        '@' to (MOD_LEFT_SHIFT to 0x34.toByte()),   // apostrophe key
        '"' to (MOD_LEFT_SHIFT to 0x1F.toByte()),   // the 2 key
        '#' to (MOD_NONE to 0x32.toByte()),         // non-US hash
        '~' to (MOD_LEFT_SHIFT to 0x32.toByte()),
        '\\' to (MOD_NONE to 0x64.toByte()),        // non-US backslash
        '|' to (MOD_LEFT_SHIFT to 0x64.toByte())
    )

    private fun overridesFor(layout: Layout): Map<Char, Pair<Byte, Byte>> =
        when (layout) {
            Layout.US -> emptyMap()
            Layout.UK -> UK_OVERRIDES
        }

    /** String used to check what the receiver actually produces. */
    const val PROBE = "@#2$~|"


    /**
     * Maps a character to (modifier, usage code), or null when unsupported.
     *
     * HID sends key POSITIONS, not characters: the receiving device applies its
     * own layout. These codes are correct only when the receiver uses US QWERTY.
     * Letters survive most Latin layouts; symbols do not.
     */
    fun encode(c: Char, layout: Layout = Layout.US): Pair<Byte, Byte>? =
        overridesFor(layout)[c] ?: encodeUs(c)

    private fun encodeUs(c: Char): Pair<Byte, Byte>? = when (c) {
        in 'a'..'z' -> MOD_NONE to (0x04 + (c - 'a')).toByte()
        in 'A'..'Z' -> MOD_LEFT_SHIFT to (0x04 + (c - 'A')).toByte()
        in '1'..'9' -> MOD_NONE to (0x1E + (c - '1')).toByte()
        '0' -> MOD_NONE to 0x27
        ' ' -> MOD_NONE to KEY_SPACE
        '\n' -> MOD_NONE to KEY_ENTER
        '\t' -> MOD_NONE to KEY_TAB
        '-' -> MOD_NONE to 0x2D
        '_' -> MOD_LEFT_SHIFT to 0x2D
        '=' -> MOD_NONE to 0x2E
        '+' -> MOD_LEFT_SHIFT to 0x2E
        '[' -> MOD_NONE to 0x2F
        '{' -> MOD_LEFT_SHIFT to 0x2F
        ']' -> MOD_NONE to 0x30
        '}' -> MOD_LEFT_SHIFT to 0x30
        '\\' -> MOD_NONE to 0x31
        '|' -> MOD_LEFT_SHIFT to 0x31
        ';' -> MOD_NONE to 0x33
        ':' -> MOD_LEFT_SHIFT to 0x33
        '\'' -> MOD_NONE to 0x34
        '"' -> MOD_LEFT_SHIFT to 0x34
        '`' -> MOD_NONE to 0x35
        '~' -> MOD_LEFT_SHIFT to 0x35
        ',' -> MOD_NONE to 0x36
        '<' -> MOD_LEFT_SHIFT to 0x36
        '.' -> MOD_NONE to 0x37
        '>' -> MOD_LEFT_SHIFT to 0x37
        '/' -> MOD_NONE to 0x38
        '?' -> MOD_LEFT_SHIFT to 0x38
        '!' -> MOD_LEFT_SHIFT to 0x1E
        '@' -> MOD_LEFT_SHIFT to 0x1F
        '#' -> MOD_LEFT_SHIFT to 0x20
        '$' -> MOD_LEFT_SHIFT to 0x21
        '%' -> MOD_LEFT_SHIFT to 0x22
        '^' -> MOD_LEFT_SHIFT to 0x23
        '&' -> MOD_LEFT_SHIFT to 0x24
        '*' -> MOD_LEFT_SHIFT to 0x25
        '(' -> MOD_LEFT_SHIFT to 0x26
        ')' -> MOD_LEFT_SHIFT to 0x27
        else -> null
    }
}
