package com.waaagh.battery.core

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StateMachineTest {
    private var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private val seen = mutableListOf<DisplayState>()
    private lateinit var sm: BannerStateMachine

    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val t = (now + delayMs) to block
            tasks += t
            return Cancellable { tasks.remove(t) }
        }
    }

    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    @Before fun setUp() {
        now = 1000
        tasks.clear(); seen.clear()
        sm = BannerStateMachine({ now }, scheduler) { seen += it }
    }

    @Test fun batteryOnly() {
        sm.onBattery(42)
        assertEquals(DisplayState(42, Mode.BATTERY, 0), sm.current)
    }

    @Test fun notificationLasts800msThenBattery() {
        sm.onBattery(50)
        sm.onNewNotification()
        assertEquals(Mode.NOTIFICATION, sm.current.mode)
        advance(799)
        assertEquals(Mode.NOTIFICATION, sm.current.mode)
        advance(1)
        assertEquals(Mode.BATTERY, sm.current.mode)
        assertEquals(50, sm.current.level)
    }

    @Test fun batteryChangesDuringEffectAreKept() {
        sm.onBattery(50)
        sm.onNewNotification()
        sm.onBattery(49)
        assertEquals(DisplayState(49, Mode.NOTIFICATION, 1), sm.current)
        advance(800)
        assertEquals(DisplayState(49, Mode.BATTERY, 1), sm.current)
    }

    @Test fun secondNotificationRestartsEffect() {
        sm.onBattery(50)
        sm.onNewNotification()
        advance(500)
        sm.onNewNotification()
        assertEquals(2, sm.current.effectId)
        advance(500)
        assertEquals(Mode.NOTIFICATION, sm.current.mode)
        advance(300)
        assertEquals(Mode.BATTERY, sm.current.mode)
    }

    @Test fun callBeatsNotificationAndEndsAtOnce() {
        sm.onBattery(80)
        sm.onNewNotification()
        advance(300)
        sm.onCallRinging()
        assertEquals(Mode.CALL, sm.current.mode)
        sm.onNewNotification()                 // dialer / anything while ringing
        advance(5000)
        assertEquals(Mode.CALL, sm.current.mode)
        sm.onCallEnded()
        assertEquals(DisplayState(80, Mode.BATTERY, 2), sm.current)
        advance(2000)
        assertEquals(Mode.BATTERY, sm.current.mode)
    }

    /** Stage 4 of the spec. */
    @Test fun stage4Scenario() {
        sm.setTestLevel(5)
        sm.onNewNotification()
        advance(400)
        sm.onCallRinging()
        sm.setTestLevel(6)
        sm.onCallEnded()
        val s = sm.current
        assertEquals(Mode.BATTERY, s.mode)
        assertEquals(6, s.level)
        assertEquals(0.6f, s.spec.fill(0), 1e-6f)
        assertEquals(0f, s.spec.fill(1), 1e-6f)
    }

    @Test fun duplicateCallEventsAreIdempotent() {
        sm.onBattery(30)
        sm.onCallRinging(); sm.onCallRinging()
        sm.onCallEnded(); sm.onCallEnded()
        assertEquals(listOf(Mode.BATTERY, Mode.CALL, Mode.BATTERY), seen.map { it.mode })
    }

    @Test fun testLevelOverridesAndReleases() {
        sm.onBattery(77)
        sm.setTestLevel(10)
        assertEquals(10, sm.current.level)
        sm.onBattery(76)
        assertEquals(10, sm.current.level)
        sm.setTestLevel(null)
        assertEquals(76, sm.current.level)
    }
}
