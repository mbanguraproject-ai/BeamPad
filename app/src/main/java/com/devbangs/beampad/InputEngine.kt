package com.devbangs.beampad

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Everything that turns intent into HID reports. The connection
 * (HidService) supplies a [Sink]; screens, panels, macros and gestures all
 * go through here, so ordering, cancellation and progress behave the same
 * wherever a control lives.
 *
 * Reports go out on one thread so keys arrive in order. Text is typed one
 * character per queue slot rather than as one long task, so a key pressed
 * while a long snippet is going out runs between characters instead of
 * waiting for the whole string. Pointer movement bypasses the queue: queued
 * behind typing it would lag the finger.
 */
class InputEngine(
    private val sink: Sink,
    private val layout: () -> HidReports.Layout,
    private val findMacro: (String) -> Macro?
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
     * way, so the caller can keep the unsent text rather than clear it.
     */
    data class TypeResult(
        val sent: Int,
        val skipped: Int,
        val interrupted: Boolean,
        val cancelled: Boolean
    )

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** True once anything reached the host; the review prompt only follows real use. */
    @Volatile var sentSomething = false
        private set

    fun resetSession() {
        sentSomething = false
    }

    val keyboard = KeyboardEngine()
    val mouse = MouseEngine()
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
    }

    fun shutdown() {
        cancelAll()
        exec.shutdown()
    }

    private fun pause(millis: Long) = runCatching { Thread.sleep(millis) }

    // ------------------------------------------------------------------ keys

    inner class KeyboardEngine {

        private val typing = mutableSetOf<Job>()

        /** Press and release one key with [modifiers] held. Usage 0 sends the modifiers alone. */
        fun tap(usage: Int, modifiers: Int = 0) {
            exec.execute { tapNow(usage, modifiers) }
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
        ): Job {
            val job = Job()
            synchronized(typing) { typing += job }
            val total = text.length
            var index = 0
            var sent = 0
            var skipped = 0

            fun finish(interrupted: Boolean) {
                synchronized(typing) { typing -= job }
                val result = TypeResult(sent, skipped, interrupted, job.cancelled)
                main.post { done(result) }
            }

            fun step() {
                if (job.cancelled) return finish(interrupted = false)
                if (index >= total) return finish(interrupted = false)
                val c = text[index++]
                val enc = HidReports.encode(c, layout())
                if (enc == null) {
                    skipped++
                } else if (tapNow(enc.second.toInt(), enc.first.toInt())) {
                    sent++
                } else {
                    return finish(interrupted = true)
                }
                if (progress != null && (index % PROGRESS_EVERY == 0 || index == total)) {
                    val s = index
                    main.post { progress(s, total) }
                }
                exec.execute { step() }
            }

            exec.execute { step() }
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

        private fun report(buttons: Int, dx: Int, dy: Int, wheel: Int): Boolean =
            sink.send(
                HidReports.REPORT_ID_MOUSE,
                HidReports.mouse(buttons.toByte(), dx, dy, wheel)
            ).also { if (it) sentSomething = true }

        /**
         * Relative movement, sent at once from the touch thread. Deltas beyond
         * the report's -127..127 are split rather than clipped, so a fast
         * flick travels the whole way.
         */
        fun move(dx: Int, dy: Int) {
            var x = dx
            var y = dy
            while (x != 0 || y != 0) {
                val sx = x.coerceIn(-127, 127)
                val sy = y.coerceIn(-127, 127)
                if (!report(held, sx, sy, 0)) return
                x -= sx
                y -= sy
            }
        }

        fun scroll(amount: Int) {
            var left = amount
            while (left != 0) {
                val step = left.coerceIn(-127, 127)
                if (!report(held, 0, 0, step)) return
                left -= step
            }
        }

        fun click(button: Int) {
            exec.execute { clickNow(button) }
        }

        fun doubleClick(button: Int) {
            exec.execute {
                if (clickNow(button)) {
                    pause(DOUBLE_CLICK_GAP_MS)
                    clickNow(button)
                }
            }
        }

        internal fun clickNow(button: Int): Boolean {
            if (!report(held or button, 0, 0, 0)) return false
            pause(KEY_DELAY_MS)
            report(held, 0, 0, 0)
            return true
        }

        /** Holds [button] down until [release]: the start of a drag. */
        fun press(button: Int) {
            held = held or button
            exec.execute { report(held, 0, 0, 0) }
        }

        fun release(button: Int) {
            held = held and button.inv()
            exec.execute { report(held, 0, 0, 0) }
        }

        fun isHeld(button: Int): Boolean = held and button != 0

        fun releaseAll() {
            if (held == 0) return
            held = 0
            exec.execute { report(0, 0, 0, 0) }
        }
    }

    // ------------------------------------------------------------- consumer

    inner class ConsumerEngine {

        /** Media, volume, power and the like. A release always follows or the key sticks. */
        fun press(usage: Int) {
            exec.execute { pressNow(usage) }
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
                            mouse.scroll(step.amount)
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
        private const val DOUBLE_CLICK_GAP_MS = 60L
        private const val PROGRESS_EVERY = 8
        private const val MAX_MACRO_DEPTH = 3
    }
}
