package com.borealroutes.app

object ModeClassifier {
    private val coachServiceCodes = listOf("RA1", "RA2", "LGW", "OXF")
    private val railOperators = listOf(
        "thameslink", "southern", "southeastern", "south western railway",
        "great northern", "great western railway", "gwr", "avanti west coast",
        "crosscountry", "chiltern railways", "lner", "northern", "scotrail",
        "transpennine express", "east midlands railway", "west midlands railway",
        "transport for wales", "tfw rail", "c2c", "greater anglia", "elizabeth line",
        "london underground", "london overground", "merseyrail", "heathrow express",
        "gatwick express", "stansted express", "dlr"
    )

    fun effectiveMode(leg: Leg): String {
        val raw = leg.rawMode.uppercase()
        if (raw in setOf("RAIL", "TRAIN")) return "RAIL"
        if (raw in setOf("TRAM", "SUBWAY", "METRO", "LIGHTRAIL")) return raw
        if (raw in setOf("FOOT", "WALK")) return "WALK"
        if (raw in setOf("BUS", "COACH") && isKnownCoachService(leg)) return "COACH"
        val agency = agencyText(leg)
        if (railOperators.any { agency.contains(it) }) return "RAIL"
        return raw
    }

    fun isKnownCoachService(leg: Leg): Boolean {
        val route = listOf(leg.routeShortName, leg.lineName, leg.routeLongName)
            .filter { it.isNotBlank() }.joinToString(" | ").uppercase()
        val agency = agencyText(leg)
        val knownCode = coachServiceCodes.any { code ->
            Regex("(^|[^A-Z0-9])${Regex.escape(code)}([^A-Z0-9]|$)").containsMatchIn(route)
        }
        return knownCode || agency.contains("centaur coaches")
    }

    fun excludedOperator(leg: Leg): Boolean {
        val text = (agencyText(leg) + " " + leg.serviceLabel).lowercase()
        return text.contains("flixbus") || text.contains("national express")
    }

    private fun agencyText(leg: Leg): String =
        (leg.agencyName.ifBlank { leg.agencyId }).trim().lowercase()
}

object JourneyLogic {
    fun normalizedServiceId(leg: Leg): String = leg.serviceLabel.trim().uppercase()
    fun normalizedOperatorId(leg: Leg): String = leg.agencyName.ifBlank { leg.agencyId }.trim().lowercase()

    fun sameThroughService(a: Leg, b: Leg): Boolean {
        val ma = a.effectiveMode()
        val mb = b.effectiveMode()
        if (ma !in setOf("BUS", "COACH") || mb !in setOf("BUS", "COACH") || ma != mb) return false
        val sa = normalizedServiceId(a)
        val sb = normalizedServiceId(b)
        if (sa.isBlank() || sb.isBlank() || sa != sb) return false
        val oa = normalizedOperatorId(a)
        val ob = normalizedOperatorId(b)
        return oa.isBlank() || ob.isBlank() || oa == ob
    }

    fun trivialWalkBetweenSameStop(walk: Leg): Boolean {
        if (walk.effectiveMode() !in setOf("FOOT", "WALK")) return false
        if (walk.durationMinutes > 5) return false
        val a = normalizedStopName(walk.from.name)
        val b = normalizedStopName(walk.to.name)
        if (a.isNotBlank() && a == b) return true
        val p1 = walk.from.point ?: return false
        val p2 = walk.to.point ?: return false
        return haversineMetres(p1, p2) <= 120.0
    }

    fun mergeConsecutiveSameServiceLegs(source: List<Leg>): List<Leg> {
        if (source.isEmpty()) return emptyList()
        val out = mutableListOf<Leg>()
        var i = 0
        while (i < source.size) {
            val current = source[i]
            val previous = out.lastOrNull()
            if (previous != null && sameThroughService(previous, current)) {
                out[out.lastIndex] = previous.copy(
                    to = current.to,
                    endTime = current.endTime,
                    headsign = current.headsign.ifBlank { previous.headsign },
                    routeLongName = previous.routeLongName.ifBlank { current.routeLongName },
                    geometry = mergeGeometry(previous.geometry, current.geometry),
                    intermediateStops = previous.intermediateStops + current.intermediateStops
                )
                i++
                continue
            }
            if (current.effectiveMode() in setOf("FOOT", "WALK") && trivialWalkBetweenSameStop(current) &&
                previous != null && i + 1 < source.size && sameThroughService(previous, source[i + 1])) {
                val next = source[i + 1]
                out[out.lastIndex] = previous.copy(
                    to = next.to,
                    endTime = next.endTime,
                    headsign = next.headsign.ifBlank { previous.headsign },
                    routeLongName = previous.routeLongName.ifBlank { next.routeLongName },
                    geometry = mergeGeometry(previous.geometry, next.geometry),
                    intermediateStops = previous.intermediateStops + next.intermediateStops
                )
                i += 2
                continue
            }
            out += current
            i++
        }
        return out
    }

    fun itineraryKey(it: Itinerary): String = it.legs.joinToString(">") { leg ->
        listOf(
            leg.effectiveMode(), leg.serviceLabel, leg.from.name, leg.to.name,
            leg.startTime.toString(), leg.endTime.toString()
        ).joinToString("|")
    }

    fun transitPatternKey(it: Itinerary): String {
        val parts = it.legs.filterNot { it.effectiveMode() in setOf("FOOT", "WALK") }.map { leg ->
            listOf(
                leg.effectiveMode(), leg.serviceLabel,
                leg.agencyName.ifBlank { leg.agencyId }, leg.from.name, leg.to.name,
                leg.startTime.toString(), leg.endTime.toString()
            ).joinToString("|")
        }
        return if (parts.isEmpty()) itineraryKey(it) else parts.joinToString(">")
    }

    fun isSane(it: Itinerary): Boolean {
        if (it.legs.isEmpty() || it.endTime.isBefore(it.startTime)) return false
        if (java.time.Duration.between(it.startTime, it.endTime).toHours() > 36) return false
        var prev: java.time.OffsetDateTime? = null
        for (leg in it.legs) {
            if (leg.endTime.isBefore(leg.startTime)) return false
            if (prev != null) {
                val gap = java.time.Duration.between(prev, leg.startTime).toMinutes()
                if (gap > 8 * 60 || gap < -2) return false
            }
            prev = leg.endTime
        }
        return true
    }

    fun itineraryUsesOnlySelectedModes(it: Itinerary, modes: List<String>): Boolean {
        val allowed = modes.map { if (it.uppercase() == "TRAIN") "RAIL" else it.uppercase() }.toSet()
        return it.legs.all { leg ->
            when (val mode = leg.effectiveMode()) {
                "FOOT", "WALK" -> true
                "TRAIN" -> allowed.contains("RAIL")
                else -> allowed.contains(mode)
            }
        }
    }

    fun passesAvoid(it: Itinerary, avoid: String): Boolean {
        val terms = avoid.lowercase().split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val text = it.legs.joinToString(" ") { leg ->
            listOf(
                leg.routeShortName, leg.routeLongName, leg.lineName, leg.headsign,
                leg.agencyName, leg.agencyId, leg.from.name, leg.to.name
            ).joinToString(" ")
        }.lowercase()
        return terms.none { text.contains(it) }
    }

    fun hasLongWalk(it: Itinerary): Boolean = it.legs.any { leg ->
        leg.effectiveMode() in setOf("FOOT", "WALK") && leg.durationMinutes >= 20
    }

    fun compare(a: Itinerary, b: Itinerary, pref: SortPreference, arriveBy: Boolean): Int {
        if (pref == SortPreference.FEWEST_CHANGES) {
            val c = a.transitLegs.size.compareTo(b.transitLegs.size)
            if (c != 0) return c
            val e = a.endTime.compareTo(b.endTime)
            if (e != 0) return e
            return a.walkingMinutes.compareTo(b.walkingMinutes)
        }
        if (pref == SortPreference.LEAST_WALKING) {
            val w = a.walkingMinutes.compareTo(b.walkingMinutes)
            if (w != 0) return w
            val e = a.endTime.compareTo(b.endTime)
            if (e != 0) return e
            return a.transitLegs.size.compareTo(b.transitLegs.size)
        }
        return if (arriveBy) {
            val s = b.startTime.compareTo(a.startTime)
            if (s != 0) s else a.durationMinutes.compareTo(b.durationMinutes)
        } else {
            val e = a.endTime.compareTo(b.endTime)
            if (e != 0) e else a.durationMinutes.compareTo(b.durationMinutes)
        }
    }

    private fun normalizedStopName(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\b(bus stop|bus station|station|stop)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun haversineMetres(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = Math.toRadians(b.lat - a.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val h = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(h)))
    }

    private fun mergeGeometry(a: List<GeoPoint>, b: List<GeoPoint>): List<GeoPoint> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = a.toMutableList()
        val firstB = b.first()
        val lastA = out.last()
        if (kotlin.math.abs(lastA.lat - firstB.lat) < 1e-7 && kotlin.math.abs(lastA.lon - firstB.lon) < 1e-7) {
            out += b.drop(1)
        } else out += b
        return out
    }
}
