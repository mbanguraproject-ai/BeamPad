package com.devbangs.beampad

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Everything that turns intent into HID reports. The connection
 * (HidService) supplies a [Sink]; screens, panels, macros and gestures all
 * go through here, so ordering, cancellation and progress behave the same
 * wherever a control lives.
 *
 * Reports go out on one thread so keys arrive in order. Text is typed one
 * character per queue slot rather than as one long task, so a key pressed
 * while a long snippet is going out runs between characters instead of
 * waiting for the whole string. Pointer movement and the wheel bypass the
 * queue (queued behind typing they would lag the finger) and go through
 * [MotionPump] instead.
 */
class InputEngine(
    private val sink: Sink,
    private val layout: () -> HidReports.Layout,
    private val findMacro: (String) -> Macro?,
    /** Shortest gap between pointer reports, in nanoseconds; read per report. */
    private val pointerIntervalNs: () -> Long = { DEFAULT_POINTER_INTERVAL_NS }
) {

    /** Sends one report. False when nothing is connected or the stack refused it. */
    fun interface Sink {
        fun send(reportId: Int, report: ByteArray): Boolean
    }

    /** A text send or macro in flight. [cancel] stops it at the next step. */
    class Job {
        @Volatile var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    /**
     * How a text send ended. [interrupted] means the connection dropped part
     * way, so the caller can keep the unsent text rather than clear it:
     * [consumed] characters of it were dealt with (sent or skipped), and
     * everything from there on never reached the device.
     */
    data class TypeResult(
        val sent: Int,
        val skipped: Int,
        val interrupted: Boolean,
        val cancelled: Boolean,
        val consumed: Int
    ) {
        /** Everything went out, or was knowingly skipped. */
        val complete: Boolean get() = !interrupted && !cancelled
    }

    private val exec = Executors.newSingleThreadExecutor { work ->
        Thread({
            // Keys are felt as much as the pointer: ahead of background work.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            work.run()
        }, "beampad-keys")
    }
    private val main = Handler(Looper.getMainLooper())

    /** True once anything reached the host; the review prompt only follows real use. */
    @Volatile var sentSomething = false
        private set

    fun resetSession() {
        sentSomething = false
    }

    val keyboard = KeyboardEngine()
    val mouse = MouseEngine()
    private val pump = MotionPump()
    val gamepad = GamepadEngine()
    val consumer = ConsumerEngine()
    val macros = MacroEngine()

    /** Runs any [Action] in order with everything else. */
    fun perform(action: Action) {
        when (action) {
            is Action.Key -> keyboard.tap(action.usage, action.modifiers)
            is Action.Consumer -> consumer.press(action.usage)
            is Action.Text -> keyboard.type(action.text) { }
            is Action.Click ->
                if (action.double) mouse.doubleClick(action.button) else mouse.click(action.button)
            is Action.Scroll -> mouse.scroll(action.amount)
            is Action.Delay -> Unit
            is Action.RunMacro -> findMacro(action.macroId)?.let { macros.run(it, null) }
        }
    }

    /** Stops any text send or macro, and lets go of held mouse buttons. */
    fun cancelAll() {
        keyboard.cancelTyping()
        macros.cancel()
        mouse.releaseAll()
        gamepad.reset()
    }

    fun shutdown() {
        cancelAll()
        pump.stop()
        gamepad.stop()
        exec.shutdown()
    }

    private fun pause(millis: Long) = runCatching { Thread.sleep(millis) }

    /**
     * Queues work on the report thread. After [shutdown] (the service is
     * going away) the work is dropped instead of throwing on that thread.
     */
    private fun queue(work: Runnable) {
        runCatching { exec.execute(work) }
    }

    // ------------------------------------------------------------------ keys

    inner class KeyboardEngine {

        private val typing = mutableSetOf<Job>()

        /**
         * Text sends run one at a time, in the order they were asked for.
         * Each character is still its own queue slot, so a key tapped
         * meanwhile goes out between characters; but a second text never
         * interleaves with the first (two quick Sends, or a live-typing
         * correction behind the text it corrects, used to come out jumbled).
         */
        private val waitingTexts = ArrayDeque<Runnable>()
        private var textRunning = false

        private fun startText(start: Runnable) {
            synchronized(waitingTexts) {
                if (textRunning) {
                    waitingTexts.addLast(start)
                    return
                }
                textRunning = true
            }
            queue(start)
        }

        private fun textFinished() {
            val next = synchronized(waitingTexts) {
                waitingTexts.removeFirstOrNull().also { if (it == null) textRunning = false }
            }
            next?.let { queue(it) }
        }

        /** Press and release one key with [modifiers] held. Usage 0 sends the modifiers alone. */
        fun tap(usage: Int, modifiers: Int = 0) {
            queue { tapNow(usage, modifiers) }
        }

        /** On the report thread only. */
        internal fun tapNow(usage: Int, modifiers: Int): Boolean {
            if (!sink.send(HidReports.REPORT_ID, HidReports.press(modifiers.toByte(), usage.toByte()))) {
                return false
            }
            sentSomething = true
            pause(KEY_DELAY_MS)
            sink.send(HidReports.REPORT_ID, HidReports.release())
            pause(KEY_DELAY_MS)
            return true
        }

        /** Characters [text] would actually produce with the current layout. */
        fun typeable(text: String): String = text.filter { HidReports.canType(it, layout()) }

        /**
         * Types [text]. [progress] gets (sent so far, total) and [done] the
         * outcome, both on the main thread.
         */
        fun type(
            text: String,
            progress: ((Int, Int) -> Unit)? = null,
            done: (TypeResult) -> Unit
        ): Job = typeAfter(0, text, progress, done)

        /**
         * Live typing's edit: [backspaces] Backspaces, then [text], as one
         * ordered step behind any text still going out.
         */
        fun replace(backspaces: Int, text: String, done: (TypeResult) -> Unit = {}): Job =
            typeAfter(backspaces, text, null, done)

        private fun typeAfter(
            backspaces: Int,
            text: String,
            progress: ((Int, Int) -> Unit)?,
            done: (TypeResult) -> Unit
        ): Job {
            val job = Job()
            synchronized(typing) { typing += job }
            val total = text.length
            var erased = 0
            var index = 0
            var sent = 0
            var skipped = 0

            fun finish(interrupted: Boolean, consumed: Int) {
                synchronized(typing) { typing -= job }
                val result = TypeResult(sent, skipped, interrupted, job.cancelled, consumed)
                main.post { done(result) }
                textFinished()
            }

            fun step() {
                if (job.cancelled) return finish(interrupted = false, consumed = index)
                if (erased < backspaces) {
                    if (!tapNow(HidReports.KEY_BACKSPACE.toInt(), 0)) {
                        return finish(interrupted = true, consumed = 0)
                    }
                    erased++
                    queue { step() }
                    return
                }
                if (index >= total) return finish(interrupted = false, consumed = total)
                val c = text[index]
                val enc = HidReports.encode(c, layout())
                if (enc == null) {
                    skipped++
                } else if (tapNow(enc.second.toInt(), enc.first.toInt())) {
                    sent++
                } else {
                    return finish(interrupted = true, consumed = index)
                }
                index++
                if (progress != null && (index % PROGRESS_EVERY == 0 || index == total)) {
                    val s = index
                    main.post { progress(s, total) }
                }
                queue { step() }
            }

            startText { step() }
            return job
        }

        /** Synchronous typing for macros, which are already off the main thread. */
        internal fun typeNow(text: String, job: Job): Boolean {
            for (c in text) {
                if (job.cancelled) return true
                val enc = HidReports.encode(c, layout()) ?: continue
                if (!tapNow(enc.second.toInt(), enc.first.toInt())) return false
            }
            return true
        }

        val isTyping: Boolean
            get() = synchronized(typing) { typing.isNotEmpty() }

        fun cancelTyping() {
            synchronized(typing) { typing.forEach { it.cancel() } }
        }
    }

    // ----------------------------------------------------------------- mouse

    inner class MouseEngine {

        /** Buttons held down, for dragging. Every report carries them. */
        @Volatile var held = 0
            private set

        /**
         * The button of a click in progress, between its down and up
         * reports, so pointer reports sent meanwhile keep it down rather
         * than cutting the click short.
         */
        @Volatile private var clicking = 0

        /** Buttons every report must carry right now. */
        internal val buttons: Int get() = held or clicking

        internal fun report(buttons: Int, dx: Int, dy: Int, wheel: Int): Boolean =
            sink.send(
                HidReports.REPORT_ID_MOUSE,
                HidReports.mouse(buttons.toByte(), dx, dy, wheel)
            ).also { if (it) sentSomething = true }

        /**
         * Relative movement. Returns at once: [MotionPump] merges it with any
         * movement not yet sent and sends it off the touch thread, so a
         * fast flick travels the whole way and a busy link never backs up.
         */
        fun move(dx: Int, dy: Int) {
            if (dx != 0 || dy != 0) pump.add(dx, dy, 0)
        }

        /** Wheel notches, merged and paced with the pointer. */
        fun scroll(amount: Int) {
            if (amount != 0) pump.add(0, 0, amount)
        }

        /** The wheel, sent now and in order. On the report thread only (macros). */
        internal fun scrollNow(amount: Int) {
            var left = amount
            while (left != 0) {
                val step = left.coerceIn(-127, 127)
                if (!report(buttons, 0, 0, step)) return
                left -= step
            }
        }

        fun click(button: Int) {
            queue { clickNow(button) }
        }

        fun doubleClick(button: Int) {
            queue {
                if (clickNow(button)) {
                    pause(DOUBLE_CLICK_GAP_MS)
                    clickNow(button)
                }
            }
        }

        internal fun clickNow(button: Int): Boolean {
            clicking = button
            if (!report(held or button, 0, 0, 0)) {
                clicking = 0
                return false
            }
            pause(KEY_DELAY_MS)
            clicking = 0
            report(held, 0, 0, 0)
            return true
        }

        /** Holds [button] down until [release]: the start of a drag. */
        fun press(button: Int) {
            held = held or button
            queue { report(held, 0, 0, 0) }
        }

        fun release(button: Int) {
            held = held and button.inv()
            queue { report(held, 0, 0, 0) }
        }

        fun isHeld(button: Int): Boolean = held and button != 0

        fun releaseAll() {
            if (held == 0) return
            held = 0
            queue { report(0, 0, 0, 0) }
        }
    }

    // ---------------------------------------------------------------- motion

    /**
     * Pointer and wheel sender, on its own high-priority thread.
     *
     * Touch screens report 120 to 240 times a second and a gyroscope about
     * as often; a Bluetooth link to a TV or a projector takes far fewer
     * reports than that. Sending one report per touch event queued them up
     * in the Bluetooth stack, which is what makes a pointer lag behind the
     * finger, rubber-band, and on weak hosts drop the link. Here movement
     * is added up as it arrives and sent as one report at most every
     * [pointerIntervalNs]: the first movement after a pause goes out at
     * once, and nothing ever waits behind stale movement. The binder call
     * also leaves the UI thread, so drawing stays smooth while pointing.
     */
    private inner class MotionPump : Runnable {
        private val lock = ReentrantLock()
        private val moved = lock.newCondition()
        private var dx = 0
        private var dy = 0
        private var wheel = 0
        private var thread: Thread? = null
        private var stopped = false
        private var lastSentNs = 0L

        fun add(x: Int, y: Int, w: Int) {
            lock.withLock {
                if (stopped) return
                // A link that stalls must not release a huge jump later.
                dx = (dx + x).coerceIn(-MAX_BACKLOG, MAX_BACKLOG)
                dy = (dy + y).coerceIn(-MAX_BACKLOG, MAX_BACKLOG)
                wheel = (wheel + w).coerceIn(-MAX_BACKLOG, MAX_BACKLOG)
                if (thread == null) {
                    thread = Thread(this, "beampad-pointer").apply {
                        isDaemon = true
                        start()
                    }
                }
                moved.signal()
            }
        }

        fun stop() {
            lock.withLock {
                stopped = true
                dx = 0
                dy = 0
                wheel = 0
                moved.signal()
            }
        }

        override fun run() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            try {
                loop()
            } catch (_: InterruptedException) {
                // Only on shutdown.
            }
        }

        private fun loop() {
            while (true) {
                lock.withLock {
                    while (!stopped && dx == 0 && dy == 0 && wheel == 0) moved.await()
                    if (stopped) return
                }
                // Pace: whatever arrives while waiting joins this report.
                val interval = runCatching { pointerIntervalNs() }.getOrDefault(DEFAULT_POINTER_INTERVAL_NS)
                val wait = lastSentNs + interval - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                var x = 0
                var y = 0
                var w = 0
                lock.withLock {
                    if (stopped) return
                    x = dx.coerceIn(-127, 127)
                    y = dy.coerceIn(-127, 127)
                    w = wheel.coerceIn(-127, 127)
                    dx -= x
                    dy -= y
                    wheel -= w
                }
                if (x == 0 && y == 0 && w == 0) continue
                lastSentNs = System.nanoTime()
                val sent = runCatching { mouse.report(mouse.buttons, x, y, w) }.getOrDefault(false)
                if (!sent) {
                    // Nothing connected: drop it rather than replay it later.
                    lock.withLock {
                        dx = 0
                        dy = 0
                        wheel = 0
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- gamepad

    /**
     * The gamepad (only registered when the user turns it on). Holds the
     * pad's current state and sends the latest of it on its own thread, at
     * the pointer's pace, so a stick moving fast never queues reports. A
     * button or D-pad tap shorter than one report still goes out: what was
     * pressed since the last report is added to the next one, and the
     * release follows in the report after.
     */
    inner class GamepadEngine : Runnable {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var buttons = 0
        private var latched = 0
        private var hat = HidReports.HAT_CENTER
        private var latchedHat = HidReports.HAT_CENTER
        private var lx = 0
        private var ly = 0
        private var rx = 0
        private var ry = 0
        private var dirty = false
        private var stopped = false
        private var thread: Thread? = null
        private var lastSentNs = 0L

        fun press(button: Int) = update {
            buttons = buttons or button
            latched = latched or button
        }

        fun release(button: Int) = update { buttons = buttons and button.inv() }

        /** The D-pad: one of HidReports.HAT_*, or HAT_CENTER when let go. */
        fun hat(direction: Int) = update {
            hat = direction
            if (direction != HidReports.HAT_CENTER) latchedHat = direction
        }

        /** A stick at -127..127 on each axis; [right] for the right stick. */
        fun stick(right: Boolean, x: Int, y: Int) = update {
            if (right) {
                rx = x
                ry = y
            } else {
                lx = x
                ly = y
            }
        }

        /**
         * Everything let go: leaving the gamepad, or the link dropping. Sends
         * nothing when the pad is already at rest, so a device paired without
         * the gamepad never receives a gamepad report.
         */
        fun reset() {
            lock.withLock {
                if (stopped) return
                val atRest = buttons == 0 && latched == 0 &&
                    hat == HidReports.HAT_CENTER && latchedHat == HidReports.HAT_CENTER &&
                    lx == 0 && ly == 0 && rx == 0 && ry == 0
                if (atRest) return
                buttons = 0
                latched = 0
                hat = HidReports.HAT_CENTER
                latchedHat = HidReports.HAT_CENTER
                lx = 0
                ly = 0
                rx = 0
                ry = 0
                wake()
            }
        }

        fun stop() {
            lock.withLock {
                stopped = true
                changed.signal()
            }
        }

        private fun update(change: () -> Unit) {
            lock.withLock {
                if (stopped) return
                change()
                wake()
            }
        }

        /** Under the lock: marks the state for sending and wakes the sender. */
        private fun wake() {
            dirty = true
            if (thread == null) {
                thread = Thread(this, "beampad-gamepad").apply {
                    isDaemon = true
                    start()
                }
            }
            changed.signal()
        }

        override fun run() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            try {
                loop()
            } catch (_: InterruptedException) {
                // Only on shutdown.
            }
        }

        private fun loop() {
            while (true) {
                lock.withLock {
                    while (!stopped && !dirty) changed.await()
                    if (stopped) return
                }
                val interval = runCatching { pointerIntervalNs() }.getOrDefault(DEFAULT_POINTER_INTERVAL_NS)
                val wait = lastSentNs + interval - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                var report = ByteArray(0)
                lock.withLock {
                    if (stopped) return
                    val sendButtons = buttons or latched
                    val sendHat = if (hat == HidReports.HAT_CENTER) latchedHat else hat
                    report = HidReports.gamepad(sendButtons, sendHat, lx, ly, rx, ry)
                    // A tap already let go goes out once more, released.
                    dirty = sendButtons != buttons || sendHat != hat
                    latched = 0
                    latchedHat = HidReports.HAT_CENTER
                }
                lastSentNs = System.nanoTime()
                val sent = runCatching { sink.send(HidReports.REPORT_ID_GAMEPAD, report) }.getOrDefault(false)
                if (sent) sentSomething = true
            }
        }
    }

    // ------------------------------------------------------------- consumer

    inner class ConsumerEngine {

        /** Media, volume, power and the like. A release always follows or the key sticks. */
        fun press(usage: Int) {
            queue { pressNow(usage) }
        }

        internal fun pressNow(usage: Int): Boolean {
            if (!sink.send(HidReports.REPORT_ID_CONSUMER, HidReports.consumer(usage))) return false
            sentSomething = true
            pause(KEY_DELAY_MS)
            sink.send(HidReports.REPORT_ID_CONSUMER, HidReports.consumerRelease())
            return true
        }
    }

    // ---------------------------------------------------------------- macros

    enum class MacroResult { DONE, CANCELLED, DISCONNECTED, BUSY }

    interface MacroListener {
        /** Before step [index] of [total] runs. Main thread. */
        fun onStep(index: Int, total: Int)

        /** Once, at the end. Main thread. */
        fun onFinished(result: MacroResult)
    }

    inner class MacroEngine {

        @Volatile private var current: Job? = null

        @Volatile var runningName: String? = null
            private set

        val isRunning: Boolean get() = current != null

        /**
         * Runs [macro] on its own thread, each step through the report queue
         * so other controls still interleave. One macro at a time: a second
         * request while one runs is refused with [MacroResult.BUSY].
         */
        fun run(macro: Macro, listener: MacroListener?): Job? {
            if (current != null) {
                listener?.let { l -> main.post { l.onFinished(MacroResult.BUSY) } }
                return null
            }
            val job = Job()
            current = job
            runningName = macro.name
            val steps = expand(macro.steps, depth = 0)

            thread(name = "beampad-macro", isDaemon = true) {
                var result = MacroResult.DONE
                for ((i, step) in steps.withIndex()) {
                    if (job.cancelled) {
                        result = MacroResult.CANCELLED
                        break
                    }
                    listener?.let { l -> main.post { l.onStep(i, steps.size) } }
                    val ok = when (step) {
                        is Action.Delay -> {
                            sleepCancellable(step.millis, job)
                            true
                        }
                        else -> runOnQueue(step, job)
                    }
                    if (!ok) {
                        result = MacroResult.DISCONNECTED
                        break
                    }
                }
                if (job.cancelled && result == MacroResult.DONE) result = MacroResult.CANCELLED
                current = null
                runningName = null
                listener?.let { l -> main.post { l.onFinished(result) } }
            }
            return job
        }

        fun cancel() {
            current?.cancel()
        }

        /** Nested macros are inlined, to a fixed depth so a macro that calls itself ends. */
        private fun expand(steps: List<Action>, depth: Int): List<Action> = steps.flatMap { step ->
            if (step is Action.RunMacro) {
                val inner = findMacro(step.macroId)
                if (inner == null || depth >= MAX_MACRO_DEPTH) emptyList()
                else expand(inner.steps, depth + 1)
            } else {
                listOf(step)
            }
        }

        private fun runOnQueue(step: Action, job: Job): Boolean =
            runCatching {
                exec.submit(Callable {
                    when (step) {
                        is Action.Key -> keyboard.tapNow(step.usage, step.modifiers)
                        is Action.Consumer -> consumer.pressNow(step.usage)
                        is Action.Text -> keyboard.typeNow(step.text, job)
                        is Action.Click -> {
                            val first = mouse.clickNow(step.button)
                            if (first && step.double) {
                                pause(DOUBLE_CLICK_GAP_MS)
                                mouse.clickNow(step.button)
                            } else {
                                first
                            }
                        }
                        is Action.Scroll -> {
                            mouse.scrollNow(step.amount)
                            true
                        }
                        is Action.Delay, is Action.RunMacro -> true
                    }
                }).get()
            }.getOrDefault(false)

        private fun sleepCancellable(millis: Long, job: Job) {
            var left = millis
            while (left > 0 && !job.cancelled) {
                val slice = minOf(left, 50L)
                pause(slice)
                left -= slice
            }
        }
    }

    companion object {
        /** Gap between reports. Slower stacks drop keys sent back-to-back. */
        const val KEY_DELAY_MS = 12L

        /** 90 reports a second: smooth on phones, TVs and PCs alike. */
        const val DEFAULT_POINTER_INTERVAL_NS = 11_000_000L

        /** Most pointer travel held back while the link is busy (about 4 full reports). */
        private const val MAX_BACKLOG = 508
        private const val DOUBLE_CLICK_GAP_MS = 60L
        private const val PROGRESS_EVERY = 8
        private const val MAX_MACRO_DEPTH = 3
    }
}
