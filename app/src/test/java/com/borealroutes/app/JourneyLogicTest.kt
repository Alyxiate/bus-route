package com.borealroutes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class JourneyLogicTest {
    private fun leg(route: String, agency: String = "Arriva Herts and Essex") = Leg(
        rawMode = "BUS",
        from = Place("A", GeoPoint(51.0, -0.2)),
        to = Place("B", GeoPoint(51.1, -0.1)),
        startTime = OffsetDateTime.parse("2026-08-31T09:00:00+01:00"),
        endTime = OffsetDateTime.parse("2026-08-31T10:00:00+01:00"),
        routeShortName = route,
        agencyName = agency
    )

    @Test fun coachesAreSeparatedFromBus() {
        assertEquals("COACH", leg("RA1").effectiveMode())
        assertEquals("COACH", leg("725", "Centaur Coaches").effectiveMode())
        assertEquals("BUS", leg("725").effectiveMode())
    }

    @Test fun excludedCoachBrandsNeverPass() {
        assertTrue(ModeClassifier.excludedOperator(leg("NX1", "National Express")))
        assertTrue(ModeClassifier.excludedOperator(leg("F01", "FlixBus")))
        assertFalse(ModeClassifier.excludedOperator(leg("100", "Arriva Herts and Essex")))
    }

    @Test fun busIsTheOnlyDefaultMode() {
        assertEquals(listOf("BUS"), SearchOptions().selectedModes())
        assertEquals(listOf("BUS", "COACH", "RAIL"), SearchOptions(includeCoaches = true, includeTrains = true).selectedModes())
    }
}
