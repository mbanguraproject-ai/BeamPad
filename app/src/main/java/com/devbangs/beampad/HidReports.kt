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

    const val REPORT_ID = 1

    const val MOD_NONE: Byte = 0x00
    const val MOD_LEFT_SHIFT: Byte = 0x02

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
        0xC0.toByte()                   // End Collection
    )

    fun press(modifier: Byte, keyCode: Byte): ByteArray =
        byteArrayOf(modifier, 0, keyCode, 0, 0, 0, 0, 0)

    fun release(): ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

    /** Keyboard layout of the RECEIVING device. Only US is implemented. */
    enum class Layout { US }

    /**
     * Maps a character to (modifier, usage code), or null when unsupported.
     *
     * HID sends key POSITIONS, not characters: the receiving device applies its
     * own layout. These codes are correct only when the receiver uses US QWERTY.
     * Letters survive most Latin layouts; symbols do not.
     */
    fun encode(c: Char, layout: Layout = Layout.US): Pair<Byte, Byte>? = when (c) {
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
