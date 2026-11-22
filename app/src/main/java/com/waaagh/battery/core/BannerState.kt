package com.waaagh.battery.core

/** Display modes, highest priority first. */
enum class Mode { CALL, NOTIFICATION, BATTERY }

/**
 * Everything the renderer and the widget need.
 * [effectId] changes whenever a new shake effect starts, so the widget
 * restarts its animation even if the mode stays the same.
 */
data class DisplayState(val level: Int, val mode: Mode, val effectId: Int) {
    val spec: PaintSpec get() = PaintSpec(level.toFloat(), mode != Mode.BATTERY)
}

/** Shake frames: horizontal offsets in "shake units" (1 unit = a few px). */
object ShakePattern {
    /** New notification: two short jerks, 8 x 100 ms = 0.8 s. */
    val NOTIFY = intArrayOf(+1, -1, 0, 0, +1, -1, 0, 0)
    const val NOTIFY_FRAME_MS = 100L
    const val NOTIFY_DURATION_MS = 800L

    /** Incoming call: burst of jerks, then a pause; loops while ringing. */
    val CALL = intArrayOf(+1, -1, +1, -1, 0, 0, 0, 0, 0, 0)
    const val CALL_FRAME_MS = 80L
}

interface Scheduler {
    /** Run [block] after [delayMs]; returns a handle to cancel it. */
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}

fun interface Cancellable { fun cancel() }

/**
 * Event state machine: battery < notification < call.
 * All inputs are idempotent; the listener is told only about real changes.
 */
class BannerStateMachine(
    private val clock: () -> Long,
    private val scheduler: Scheduler,
    private val listener: (DisplayState) -> Unit,
) {
    var realLevel: Int = -1
        private set
    var testLevel: Int? = null
        private set
    var ringing = false
        private set

    private var notifyUntil = 0L
    private var notifyEnd: Cancellable? = null
    private var effectId = 0
    var current: DisplayState = DisplayState(0, Mode.BATTERY, 0)
        private set

    val level: Int get() = (testLevel ?: realLevel).coerceIn(0, 100)

    fun onBattery(levelPercent: Int) {
        realLevel = levelPercent.coerceIn(0, 100)
        publish()
    }

    /** Test mode: force a charge value; null = back to the real battery. */
    fun setTestLevel(levelPercent: Int?) {
        testLevel = levelPercent?.coerceIn(0, 100)
        publish()
    }

    /** A genuinely new notification (updates/removals must not call this). */
    fun onNewNotification() {
        if (ringing) return                       // call has priority, no second effect
        notifyEnd?.cancel()
        notifyUntil = clock() + ShakePattern.NOTIFY_DURATION_MS
        effectId++
        notifyEnd = scheduler.schedule(ShakePattern.NOTIFY_DURATION_MS) { publish() }
        publish()
    }

    fun onCallRinging() {
        if (ringing) return
        ringing = true
        notifyEnd?.cancel()                       // the call interrupts the notification effect
        notifyEnd = null
        notifyUntil = 0L
        effectId++
        publish()
    }

    /** Answered, rejected, missed or hung up: back to the battery at once. */
    fun onCallEnded() {
        if (!ringing) return
        ringing = false
        publish()
    }

    fun mode(): Mode = when {
        ringing -> Mode.CALL
        clock() < notifyUntil -> Mode.NOTIFICATION
        else -> Mode.BATTERY
    }

    /** Re-send the current state even if nothing changed (widget re-created etc). */
    fun republish() = listener(current)

    private fun publish() {
        val s = DisplayState(level, mode(), effectId)
        if (s != current) {
            current = s
            listener(s)
        }
    }
}
