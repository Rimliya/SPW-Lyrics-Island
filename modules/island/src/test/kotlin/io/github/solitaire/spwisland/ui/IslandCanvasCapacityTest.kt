package io.github.solitaire.spwisland.ui

import kotlin.test.*

class IslandCanvasCapacityTest {
    @Test fun growsImmediatelyAndWaitsForSettledShrink() {
        val capacity = IslandCanvasCapacity(100)
        capacity.update(1_000, 600, 0, false)
        capacity.update(600, 200, 1_000, false)
        assertEquals(600, capacity.height)
        capacity.update(600, 200, 1_100, true)
        capacity.update(600, 200, 1_199, true)
        assertEquals(600, capacity.height)
        capacity.update(600, 200, 1_200, true)
        assertEquals(600, capacity.width)
        assertEquals(200, capacity.height)
    }

    @Test fun animationAndGrowthCancelPendingShrink() {
        val capacity = IslandCanvasCapacity(100)
        capacity.update(1_000, 600, 0, true)
        capacity.update(600, 200, 10, true)
        capacity.update(600, 200, 110, false)
        capacity.update(600, 200, 200, true)
        assertEquals(600, capacity.height)
        capacity.update(1_200, 600, 250, true)
        assertEquals(1_200, capacity.width)
        capacity.update(600, 200, 300, true)
        capacity.update(600, 200, 399, true)
        assertEquals(600, capacity.height)
        capacity.update(600, 200, 400, true)
        assertEquals(200, capacity.height)
    }

    @Test fun smallFluctuationsKeepCapacityAndResetReleasesIt() {
        val capacity = IslandCanvasCapacity(100)
        capacity.update(1_000, 600, 0, true)
        capacity.update(980, 580, 10_000, true)
        capacity.update(980, 580, 20_000, true)
        assertEquals(1_000, capacity.width)
        assertEquals(600, capacity.height)
        capacity.reset()
        capacity.update(300, 100, 20_001, true)
        assertEquals(300, capacity.width)
        assertEquals(100, capacity.height)
    }
}
