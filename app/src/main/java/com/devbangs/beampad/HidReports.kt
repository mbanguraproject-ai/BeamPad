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
    const val CC_SCAN_NEXT = 0x00B5      // fast forward / next
    const val CC_SCAN_PREV = 0x00B6      // rewind / previous
    const val CC_HOME = 0x0223           // AC Home
    const val CC_BACK = 0x0224           // AC Back
    const val CC_MENU = 0x0040           // Menu
    const val CC_FORWARD = 0x0225        // AC Forward
    const val CC_SEARCH = 0x0221         // AC Search: opens the TV's search
    const val CC_POWER = 0x0030          // Power
    const val CC_SLEEP = 0x0032          // Sleep
    const val CC_CHANNEL_UP = 0x009C     // Channel Increment
    const val CC_CHANNEL_DOWN = 0x009D   // Channel Decrement
    const val CC_FAST_FORWARD = 0x00B3   // Fast Forward (seek, not next track)
    const val CC_REWIND = 0x00B4         // Rewind (seek, not previous track)
    const val CC_STOP = 0x00B7           // Stop
    const val CC_CLOSED_CAPTION = 0x0061 // Closed Caption: subtitles toggle

    // Deliberately absent: input/source switching has no HID usage at all
    // (it is IR, CEC or vendor protocol), and channel up/down only works on
    // a real tuner, so it is dead on the streaming boxes most users have.

    const val BUTTON_NONE: Byte = 0x00
    const val BUTTON_LEFT: Byte = 0x01
    const val BUTTON_RIGHT: Byte = 0x02
    const val BUTTON_MIDDLE: Byte = 0x04

    const val MOD_NONE: Byte = 0x00
    const val MOD_LEFT_CTRL: Byte = 0x01
    const val MOD_LEFT_SHIFT: Byte = 0x02
    const val MOD_LEFT_ALT: Byte = 0x04
    const val MOD_LEFT_META: Byte = 0x08     // Windows / Command / Search key
    const val MOD_RIGHT_ALT: Byte = 0x40   // AltGr, needed by continental layouts

    // Common control keys, by HID usage code.
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESC: Byte = 0x29
    const val KEY_BACKSPACE: Byte = 0x2A
    const val KEY_TAB: Byte = 0x2B
    const val KEY_SPACE: Byte = 0x2C
    const val KEY_A: Byte = 0x04
    const val KEY_B: Byte = 0x05
    const val KEY_C: Byte = 0x06
    const val KEY_D: Byte = 0x07
    const val KEY_F: Byte = 0x09
    const val KEY_L: Byte = 0x0F
    const val KEY_V: Byte = 0x19
    const val KEY_X: Byte = 0x1B
    const val KEY_Z: Byte = 0x1D
    const val KEY_MINUS: Byte = 0x2D
    const val KEY_EQUALS: Byte = 0x2E
    const val KEY_COMMA: Byte = 0x36
    const val KEY_PERIOD: Byte = 0x37
    const val KEY_F4: Byte = 0x3D
    const val KEY_F5: Byte = 0x3E
    const val KEY_HOME: Byte = 0x4A
    const val KEY_PAGE_UP: Byte = 0x4B
    const val KEY_DELETE: Byte = 0x4C
    const val KEY_END: Byte = 0x4D
    const val KEY_PAGE_DOWN: Byte = 0x4E
    const val KEY_RIGHT: Byte = 0x4F
    const val KEY_LEFT: Byte = 0x50
    const val KEY_DOWN: Byte = 0x51
    const val KEY_UP: Byte = 0x52

    /** F1 to F12 are usages 0x3A to 0x45, inside the descriptor's 0-0x65 range. */
    fun functionKey(n: Int): Byte {
        require(n in 1..12) { "function key out of range: $n" }
        return (0x3A + n - 1).toByte()
    }

    /**
     * True when [c] can be typed with [layout]. Used to keep live typing's
     * picture of the remote text in step with what was actually sent.
     */
    fun canType(c: Char, layout: Layout = Layout.US): Boolean = encode(c, layout) != null

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
        UK("UK QWERTY"),
        DE("German QWERTZ"),
        FR("French AZERTY"),
        ES("Spanish QWERTY")
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

    private const val SHIFT = MOD_LEFT_SHIFT
    private const val ALTGR = MOD_RIGHT_ALT
    private const val NONE = MOD_NONE

    private fun key(mod: Byte, usage: Int): Pair<Byte, Byte> = mod to usage.toByte()

    /**
     * German QWERTZ. Every symbol is listed: the US mapping is wrong for
     * almost all of them on this layout. Dead keys (^ ` ´) are left out, so
     * they are reported as untypable instead of typing something else.
     */
    private val DE_KEYS: Map<Char, Pair<Byte, Byte>> = mapOf(
        'ä' to key(NONE, 0x34), 'Ä' to key(SHIFT, 0x34),
        'ö' to key(NONE, 0x33), 'Ö' to key(SHIFT, 0x33),
        'ü' to key(NONE, 0x2F), 'Ü' to key(SHIFT, 0x2F),
        'ß' to key(NONE, 0x2D), '?' to key(SHIFT, 0x2D), '\\' to key(ALTGR, 0x2D),
        '!' to key(SHIFT, 0x1E), '"' to key(SHIFT, 0x1F), '§' to key(SHIFT, 0x20),
        '$' to key(SHIFT, 0x21), '%' to key(SHIFT, 0x22), '&' to key(SHIFT, 0x23),
        '/' to key(SHIFT, 0x24), '(' to key(SHIFT, 0x25), ')' to key(SHIFT, 0x26),
        '=' to key(SHIFT, 0x27),
        '²' to key(ALTGR, 0x1F), '³' to key(ALTGR, 0x20),
        '{' to key(ALTGR, 0x24), '[' to key(ALTGR, 0x25), ']' to key(ALTGR, 0x26), '}' to key(ALTGR, 0x27),
        '+' to key(NONE, 0x30), '*' to key(SHIFT, 0x30), '~' to key(ALTGR, 0x30),
        '#' to key(NONE, 0x32), '\'' to key(SHIFT, 0x32),
        '°' to key(SHIFT, 0x35),
        ',' to key(NONE, 0x36), ';' to key(SHIFT, 0x36),
        '.' to key(NONE, 0x37), ':' to key(SHIFT, 0x37),
        '-' to key(NONE, 0x38), '_' to key(SHIFT, 0x38),
        '<' to key(NONE, 0x64), '>' to key(SHIFT, 0x64), '|' to key(ALTGR, 0x64),
        '@' to key(ALTGR, 0x14), '€' to key(ALTGR, 0x08), 'µ' to key(ALTGR, 0x10)
    )

    /** French AZERTY: digits need Shift, M sits where US has the semicolon. */
    private val FR_KEYS: Map<Char, Pair<Byte, Byte>> = mapOf(
        '&' to key(NONE, 0x1E), '1' to key(SHIFT, 0x1E),
        'é' to key(NONE, 0x1F), '2' to key(SHIFT, 0x1F),
        '"' to key(NONE, 0x20), '3' to key(SHIFT, 0x20), '#' to key(ALTGR, 0x20),
        '\'' to key(NONE, 0x21), '4' to key(SHIFT, 0x21), '{' to key(ALTGR, 0x21),
        '(' to key(NONE, 0x22), '5' to key(SHIFT, 0x22), '[' to key(ALTGR, 0x22),
        '-' to key(NONE, 0x23), '6' to key(SHIFT, 0x23), '|' to key(ALTGR, 0x23),
        'è' to key(NONE, 0x24), '7' to key(SHIFT, 0x24),
        '_' to key(NONE, 0x25), '8' to key(SHIFT, 0x25), '\\' to key(ALTGR, 0x25),
        'ç' to key(NONE, 0x26), '9' to key(SHIFT, 0x26),
        'à' to key(NONE, 0x27), '0' to key(SHIFT, 0x27), '@' to key(ALTGR, 0x27),
        ')' to key(NONE, 0x2D), '°' to key(SHIFT, 0x2D), ']' to key(ALTGR, 0x2D),
        '=' to key(NONE, 0x2E), '+' to key(SHIFT, 0x2E), '}' to key(ALTGR, 0x2E),
        '$' to key(NONE, 0x30), '£' to key(SHIFT, 0x30),
        '*' to key(NONE, 0x32), 'µ' to key(SHIFT, 0x32),
        'm' to key(NONE, 0x33), 'M' to key(SHIFT, 0x33),
        'ù' to key(NONE, 0x34), '%' to key(SHIFT, 0x34),
        '²' to key(NONE, 0x35),
        ',' to key(NONE, 0x10), '?' to key(SHIFT, 0x10),
        ';' to key(NONE, 0x36), '.' to key(SHIFT, 0x36),
        ':' to key(NONE, 0x37), '/' to key(SHIFT, 0x37),
        '!' to key(NONE, 0x38), '§' to key(SHIFT, 0x38),
        '<' to key(NONE, 0x64), '>' to key(SHIFT, 0x64),
        '€' to key(ALTGR, 0x08)
    )

    /** Spanish (Spain) QWERTY. */
    private val ES_KEYS: Map<Char, Pair<Byte, Byte>> = mapOf(
        '!' to key(SHIFT, 0x1E), '|' to key(ALTGR, 0x1E),
        '"' to key(SHIFT, 0x1F), '@' to key(ALTGR, 0x1F),
        '·' to key(SHIFT, 0x20), '#' to key(ALTGR, 0x20),
        '$' to key(SHIFT, 0x21), '%' to key(SHIFT, 0x22),
        '&' to key(SHIFT, 0x23), '¬' to key(ALTGR, 0x23),
        '/' to key(SHIFT, 0x24), '(' to key(SHIFT, 0x25), ')' to key(SHIFT, 0x26),
        '=' to key(SHIFT, 0x27),
        '\'' to key(NONE, 0x2D), '?' to key(SHIFT, 0x2D),
        '¡' to key(NONE, 0x2E), '¿' to key(SHIFT, 0x2E),
        '[' to key(ALTGR, 0x2F),
        '+' to key(NONE, 0x30), '*' to key(SHIFT, 0x30), ']' to key(ALTGR, 0x30),
        'ñ' to key(NONE, 0x33), 'Ñ' to key(SHIFT, 0x33),
        '{' to key(ALTGR, 0x34),
        'ç' to key(NONE, 0x32), 'Ç' to key(SHIFT, 0x32), '}' to key(ALTGR, 0x32),
        'º' to key(NONE, 0x35), 'ª' to key(SHIFT, 0x35), '\\' to key(ALTGR, 0x35),
        ',' to key(NONE, 0x36), ';' to key(SHIFT, 0x36),
        '.' to key(NONE, 0x37), ':' to key(SHIFT, 0x37),
        '-' to key(NONE, 0x38), '_' to key(SHIFT, 0x38),
        '<' to key(NONE, 0x64), '>' to key(SHIFT, 0x64),
        '€' to key(ALTGR, 0x08)
    )

    /** Letters that sit on a different key than on US QWERTY: typed letter to the US key. */
    private val LETTER_MOVES: Map<Layout, Map<Char, Char>> = mapOf(
        Layout.DE to mapOf('z' to 'y', 'y' to 'z'),
        Layout.FR to mapOf('a' to 'q', 'q' to 'a', 'z' to 'w', 'w' to 'z')
    )

    /**
     * Continental layouts: the table first, then only what is genuinely
     * shared with US (letters, moved where needed, plus digits where they
     * are unshifted, space, Enter and Tab). Anything else is untypable
     * rather than typed as the wrong symbol.
     */
    private fun encodeContinental(c: Char, layout: Layout, table: Map<Char, Pair<Byte, Byte>>): Pair<Byte, Byte>? {
        table[c]?.let { return it }
        if (c == ' ' || c == '\n' || c == '\t') return encodeUs(c)
        val lower = c.lowercaseChar()
        if (lower in 'a'..'z') {
            val on = LETTER_MOVES[layout]?.get(lower) ?: lower
            return (if (c in 'A'..'Z') SHIFT else NONE) to (0x04 + (on - 'a')).toByte()
        }
        if (c in '0'..'9' && layout != Layout.FR) return encodeUs(c)
        return null
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
    fun encode(c: Char, layout: Layout = Layout.US): Pair<Byte, Byte>? = when (layout) {
        Layout.US -> encodeUs(c)
        Layout.UK -> UK_OVERRIDES[c] ?: encodeUs(c)
        Layout.DE -> encodeContinental(c, layout, DE_KEYS)
        Layout.FR -> encodeContinental(c, layout, FR_KEYS)
        Layout.ES -> encodeContinental(c, layout, ES_KEYS)
    }

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
