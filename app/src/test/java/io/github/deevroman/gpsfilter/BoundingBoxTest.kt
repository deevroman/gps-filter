package io.github.deevroman.gpsfilter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundingBoxTest {
    private val zone = BoundingBox(
        id = 1,
        name = "Test zone",
        south = 55.70,
        west = 37.50,
        north = 55.80,
        east = 37.60,
    )

    @Test
    fun `point inside bbox is blocked`() {
        assertTrue(zone.contains(55.75, 37.55))
    }

    @Test
    fun `borders are treated as private`() {
        assertTrue(zone.contains(55.70, 37.50))
        assertTrue(zone.contains(55.80, 37.60))
    }

    @Test
    fun `point outside bbox is safe`() {
        assertFalse(zone.contains(55.81, 37.55))
        assertFalse(zone.contains(55.75, 37.61))
    }

    @Test
    fun `zones with names differing only by case or spaces are deduplicated`() {
        val duplicate = zone.copy(id = 2, name = "  test ZONE ")

        val uniqueZones = FilterStorage.deduplicateZones(listOf(zone, duplicate))

        assertEquals(listOf(zone), uniqueZones)
    }
}
