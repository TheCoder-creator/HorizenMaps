package com.horizen.maps.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNavigationEngineTest {
    @Test
    fun distanceBetweenSamePointIsZero() {
        assertEquals(0.0, CarNavigationEngine.distanceMeters(37.0, -122.0, 37.0, -122.0), 0.01)
    }

    @Test
    fun distanceIsSymmetric() {
        val forward = CarNavigationEngine.distanceMeters(37.3382, -121.8863, 37.7749, -122.4194)
        val backward = CarNavigationEngine.distanceMeters(37.7749, -122.4194, 37.3382, -121.8863)
        assertEquals(forward, backward, 0.01)
        assertTrue(forward > 0)
    }

    @Test
    fun maneuverTextCoversCommonDirections() {
        assertEquals("Turn left", CarNavigationEngine.instruction("turn", "left"))
        assertEquals("Turn right", CarNavigationEngine.instruction("turn", "right"))
        assertEquals("Make a U-turn", CarNavigationEngine.instruction("uturn", "left"))
        assertEquals("You have arrived", CarNavigationEngine.instruction("arrive", ""))
    }
}
