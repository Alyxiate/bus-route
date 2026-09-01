package com.borealroutes.app

import java.time.OffsetDateTime
import java.time.Duration

data class GeoPoint(val lat: Double, val lon: Double)

data class Place(
    val name: String,
    val point: GeoPoint? = null,
    val subtitle: String = ""
)

data class WalkingStep(
    val distanceMetres: Double = 0.0,
    val relativeDirection: String = "CONTINUE",
    val streetName: String = ""
)

data class Leg(
    val rawMode: String,
    val from: Place,
    val to: Place,
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val routeShortName: String = "",
    val routeLongName: String = "",
    val lineName: String = "",
    val agencyName: String = "",
    val agencyId: String = "",
    val headsign: String = "",
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val distanceMetres: Double = 0.0,
    val geometry: List<GeoPoint> = emptyList(),
    val intermediateStops: List<Place> = emptyList(),
    val walkingSteps: List<WalkingStep> = emptyList(),
    val interlineWithPreviousLeg: Boolean = false
) {
    val durationMinutes: Long
        get() = Duration.between(startTime, endTime).toMinutes().coerceAtLeast(0)

    val serviceLabel: String
        get() = routeShortName.ifBlank {
            lineName.ifBlank { routeLongName.ifBlank { effectiveMode() } }
        }

    fun effectiveMode(): String = ModeClassifier.effectiveMode(this)
}

data class Itinerary(
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val legs: List<Leg>,
    val rawJson: String = ""
) {
    val durationMinutes: Long
        get() = Duration.between(startTime, endTime).toMinutes().coerceAtLeast(0)

    val transitLegs: List<Leg>
        get() = mergedLegs().filterNot { it.effectiveMode() in setOf("FOOT", "WALK") }

    val changes: Int
        get() = (transitLegs.size - 1).coerceAtLeast(0)

    val walkingMinutes: Long
        get() = legs.filter { it.effectiveMode() in setOf("FOOT", "WALK") }.sumOf { it.durationMinutes }

    fun mergedLegs(): List<Leg> = JourneyLogic.mergeConsecutiveSameServiceLegs(legs)
}

enum class TimeMode { LEAVE_AT, ARRIVE_BY }
enum class SortPreference { FASTEST, FEWEST_CHANGES, LEAST_WALKING }

data class SearchOptions(
    val includeCoaches: Boolean = false,
    val includeTrains: Boolean = false,
    val includeFerries: Boolean = false,
    val includePlanes: Boolean = false,
    val viaText: String = "",
    val viaPlace: Place? = null,
    val avoidTerms: String = "",
    val dayTrip: Boolean = false,
    val breakMinutes: Int = 0,
    val sortPreference: SortPreference = SortPreference.FASTEST
) {
    fun selectedModes(): List<String> = buildList {
        add("BUS")
        if (includeCoaches) add("COACH")
        if (includeTrains) add("RAIL")
        if (includeFerries) add("FERRY")
        if (includePlanes) add("AIR")
    }
}

data class SearchRequest(
    val fromText: String,
    val toText: String,
    val fromPlace: Place? = null,
    val toPlace: Place? = null,
    val dateIso: String,
    val time: String,
    val timeMode: TimeMode = TimeMode.LEAVE_AT,
    val options: SearchOptions = SearchOptions()
)

data class SearchProgress(
    val message: String,
    val journeys: List<Itinerary> = emptyList(),
    val complete: Boolean = false,
    val rawScanned: Int = 0,
    val slicesDone: Int = 0
)
