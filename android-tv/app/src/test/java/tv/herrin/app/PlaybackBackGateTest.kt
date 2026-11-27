package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackBackGateTest {
    @Test fun physicalThenDispatcherConsumesOneTransition() {
        val gate = PlaybackBackGate()
        assertTrue(gate.accept(100,200))
        assertFalse(gate.accept(null,210))
        assertFalse(gate.accept(100,220))
    }
    @Test fun dispatcherThenPhysicalAlsoConsumesOneTransition() {
        val gate = PlaybackBackGate()
        assertTrue(gate.accept(null,200))
        assertFalse(gate.accept(100,210))
        assertFalse(gate.accept(100,350))
    }
    @Test fun twoDistinctRapidPhysicalPressesAreNotDropped() {
        val gate = PlaybackBackGate()
        assertTrue(gate.accept(100,200))
        assertTrue(gate.accept(110,220))
        assertFalse(gate.accept(null,230))
    }
    @Test fun laterDispatcherOnlyPressStillReturns() {
        val gate = PlaybackBackGate()
        assertTrue(gate.accept(null,200))
        assertFalse(gate.accept(null,210))
        assertTrue(gate.accept(null,400))
        assertFalse(gate.accept(300,410))
    }
    @Test fun backConsumesTheFirstLayerAndOnlyTheSecondDistinctPressExits() {
        val gate = PlaybackBackGate()
        var closed = 0
        var exits = 0
        fun press(key: Long?, at: Long) = gate.press(key, at, { closed++ }, { exits++ })
        press(10, 20)
        press(10, 30)
        press(null, 40)
        assertEquals(1, closed)
        assertEquals(0, exits)
        press(80, 110)
        assertEquals(1, exits)
        press(115, 130)
        assertEquals(1, exits)
    }
}
