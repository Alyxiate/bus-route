package com.borealroutes.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RoutingEngine(
    private val photon: PhotonClient = Network.photon,
    private val transitous: TransitousClient = Network.transitous
) {
    private val london = ZoneId.of("Europe/London")
    private val longWalkRadii = listOf(5_000, 15_000, 50_000, 150_000, 400_000, 1_000_000)

    suspend fun search(request: SearchRequest, progress: suspend (SearchProgress) -> Unit): List<Itinerary> {
        val from = request.fromPlace?.takeIf { it.point != null } ?: photon.geocode(request.fromText)
        val to = request.toPlace?.takeIf { it.point != null } ?: photon.geocode(request.toText)
        val via = request.options.viaPlace?.takeIf { it.point != null } ?: request.options.viaText
            .takeIf { it.isNotBlank() }?.let { photon.geocode(it) }
        val fromPoint = from.point ?: error("Could not determine the start location")
        val toPoint = to.point ?: error("Could not determine the destination")
        val modes = request.options.selectedModes()
        val date = LocalDate.parse(request.dateIso)
        val time = LocalTime.parse(request.time)
        val base = ZonedDateTime.of(date, time, london)
        val arriveBy = request.timeMode == TimeMode.ARRIVE_BY

        if (!request.options.dayTrip) {
            val found = if (via == null) {
                searchDirect(
                    fromPoint, toPoint, modes, base, date, arriveBy,
                    request.options.avoidTerms, "Journey", progress
                )
            } else {
                searchVia(
                    fromPoint, toPoint, via.point ?: error("Could not determine the Via point"),
                    modes, base, date, arriveBy, request.options.avoidTerms, progress
                )
            }
            return found.sortedWith { a, b ->
                JourneyLogic.compare(a, b, request.options.sortPreference, arriveBy)
            }
        }

        val outbound = if (via == null) {
            searchDirect(fromPoint, toPoint, modes, base, date, false, request.options.avoidTerms, "Outbound", progress)
        } else {
            searchVia(fromPoint, toPoint, via.point ?: error("Could not determine Via point"), modes, base, date, false, request.options.avoidTerms, progress)
        }
        if (outbound.isEmpty()) return emptyList()
        val bestOut = outbound.minWithOrNull { a, b -> JourneyLogic.compare(a, b, request.options.sortPreference, false) }!!
        val returnStart = bestOut.endTime.plusMinutes(request.options.breakMinutes.toLong())
        val returnBase = returnStart.atZoneSameInstant(london)
        val returns = searchDirect(
            toPoint, fromPoint, modes, returnBase, date, false,
            request.options.avoidTerms, "Return", progress
        )
        val bestReturn = returns.minWithOrNull { a, b -> JourneyLogic.compare(a, b, request.options.sortPreference, false) }
        return listOfNotNull(bestOut, bestReturn)
    }

    private suspend fun searchVia(
        from: GeoPoint,
        to: GeoPoint,
        via: GeoPoint,
        modes: List<String>,
        base: ZonedDateTime,
        selectedDate: LocalDate,
        arriveBy: Boolean,
        avoid: String,
        progress: suspend (SearchProgress) -> Unit
    ): List<Itinerary> {
        return if (!arriveBy) {
            progress(SearchProgress("Finding journeys to the Via point…"))
            val lefts = searchDirect(from, via, modes, base, selectedDate, false, avoid, "To Via", {})
                .take(32)
            val combined = ConcurrentHashMap<String, Itinerary>()
            val done = AtomicInteger(0)
            coroutineScope {
                val semaphore = Semaphore(4)
                lefts.map { left ->
                    async {
                        semaphore.withPermit {
                            val rights = anchoredSearch(via, to, modes, left.endTime, false, selectedDate, avoid)
                            for (right in rights) {
                                combine(left, right)?.let { candidate ->
                                    if (insideSelectedDate(candidate, selectedDate, false)) {
                                        val key = JourneyLogic.transitPatternKey(candidate)
                                        combined.compute(key) { _, old ->
                                            if (old == null || JourneyLogic.compare(candidate, old, SortPreference.FASTEST, false) < 0) candidate else old
                                        }
                                    }
                                }
                            }
                            val n = done.incrementAndGet()
                            val values = combined.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, false) }
                            progress(SearchProgress("Via search: $n/${lefts.size} anchors checked", values))
                        }
                    }
                }.awaitAll()
            }
            combined.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, false) }
        } else {
            progress(SearchProgress("Finding journeys from the Via point…"))
            val rights = searchDirect(via, to, modes, base, selectedDate, true, avoid, "From Via", {}).take(32)
            val combined = ConcurrentHashMap<String, Itinerary>()
            val done = AtomicInteger(0)
            coroutineScope {
                val semaphore = Semaphore(4)
                rights.map { right ->
                    async {
                        semaphore.withPermit {
                            val lefts = anchoredSearch(from, via, modes, right.startTime, true, selectedDate, avoid)
                            for (left in lefts) {
                                combine(left, right)?.let { candidate ->
                                    if (insideSelectedDate(candidate, selectedDate, true)) {
                                        val key = JourneyLogic.transitPatternKey(candidate)
                                        combined.compute(key) { _, old ->
                                            if (old == null || JourneyLogic.compare(candidate, old, SortPreference.FASTEST, true) < 0) candidate else old
                                        }
                                    }
                                }
                            }
                            val n = done.incrementAndGet()
                            val values = combined.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, true) }
                            progress(SearchProgress("Via search: $n/${rights.size} anchors checked", values))
                        }
                    }
                }.awaitAll()
            }
            combined.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, true) }
        }
    }

    private suspend fun anchoredSearch(
        from: GeoPoint,
        to: GeoPoint,
        modes: List<String>,
        anchor: OffsetDateTime,
        arriveBy: Boolean,
        selectedDate: LocalDate,
        avoid: String
    ): List<Itinerary> {
        for (radius in longWalkRadii) {
            val page = runCatching {
                transitous.plan(
                    from, to, anchor.toString(), modes, arriveBy,
                    searchWindowSeconds = 4 * 60 * 60, radius = radius
                )
            }.getOrNull() ?: continue
            val filtered = page.itineraries.asSequence()
                .filter { isAllowed(it, modes, selectedDate, arriveBy, avoid, allowLongWalk = true) }
                .distinctBy { JourneyLogic.transitPatternKey(it) }
                .sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, arriveBy) }
                .take(32).toList()
            if (filtered.isNotEmpty()) return filtered
        }
        return emptyList()
    }

    private suspend fun searchDirect(
        from: GeoPoint,
        to: GeoPoint,
        modes: List<String>,
        base: ZonedDateTime,
        selectedDate: LocalDate,
        arriveBy: Boolean,
        avoid: String,
        prefix: String,
        progress: suspend (SearchProgress) -> Unit
    ): List<Itinerary> {
        val shared = ConcurrentHashMap<String, Itinerary>()
        val rawScanned = AtomicInteger(0)
        val slicesDone = AtomicInteger(0)

        suspend fun scan(radius: Int?, stepMinutes: Int, allowLongWalk: Boolean) {
            val slices = buildSlices(base, selectedDate, arriveBy, stepMinutes)
            val semaphore = Semaphore(6)
            coroutineScope {
                slices.map { slice ->
                    async {
                        semaphore.withPermit {
                            val page = runCatching {
                                transitous.plan(
                                    from, to, slice.queryTime.toOffsetDateTime().toString(), modes,
                                    arriveBy, stepMinutes * 60, radius
                                )
                            }.getOrNull()
                            if (page != null) {
                                rawScanned.addAndGet(page.rawCount)
                                for (itinerary in page.itineraries) {
                                    if (!insideSlice(itinerary, slice, arriveBy)) continue
                                    if (!isAllowed(itinerary, modes, selectedDate, arriveBy, avoid, allowLongWalk)) continue
                                    val key = JourneyLogic.transitPatternKey(itinerary)
                                    shared.compute(key) { _, old ->
                                        if (old == null || JourneyLogic.compare(itinerary, old, SortPreference.FASTEST, arriveBy) < 0) itinerary else old
                                    }
                                }
                            }
                            val done = slicesDone.incrementAndGet()
                            val current = shared.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, arriveBy) }
                            if (current.isNotEmpty() || done % 3 == 0) {
                                progress(SearchProgress(
                                    message = if (current.isNotEmpty())
                                        "$prefix: ${current.size} distinct option${if (current.size == 1) "" else "s"} found — still sweeping the selected day nationwide…"
                                    else "$prefix: checking every $stepMinutes-minute timetable window nationwide…",
                                    journeys = current,
                                    complete = false,
                                    rawScanned = rawScanned.get(),
                                    slicesDone = done
                                ))
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        scan(radius = null, stepMinutes = 60, allowLongWalk = false)
        if (shared.isEmpty()) {
            for (radius in longWalkRadii) {
                progress(SearchProgress("$prefix: no short-walk route found; trying nearby-stop walking radius ${radius / 1000.0} km…"))
                scan(radius, 120, allowLongWalk = true)
                if (shared.isEmpty()) scan(radius, 60, allowLongWalk = true)
                if (shared.isNotEmpty()) break
            }
        }

        val final = shared.values.sortedWith { a, b -> JourneyLogic.compare(a, b, SortPreference.FASTEST, arriveBy) }
        progress(SearchProgress(
            message = if (final.isEmpty()) "$prefix: no selected-mode route found on the selected day."
            else "$prefix: selected-day nationwide sweep complete — ${final.size} valid journey option${if (final.size == 1) "" else "s"} found.",
            journeys = final,
            complete = true,
            rawScanned = rawScanned.get(),
            slicesDone = slicesDone.get()
        ))
        return final
    }

    private fun isAllowed(
        it: Itinerary,
        modes: List<String>,
        selectedDate: LocalDate,
        arriveBy: Boolean,
        avoid: String,
        allowLongWalk: Boolean
    ): Boolean {
        if (!JourneyLogic.isSane(it)) return false
        if (!insideSelectedDate(it, selectedDate, arriveBy)) return false
        if (!JourneyLogic.itineraryUsesOnlySelectedModes(it, modes)) return false
        if (it.legs.any(ModeClassifier::excludedOperator)) return false
        if (!JourneyLogic.passesAvoid(it, avoid)) return false
        if (!allowLongWalk && JourneyLogic.hasLongWalk(it)) return false
        return true
    }

    private fun insideSelectedDate(it: Itinerary, date: LocalDate, arriveBy: Boolean): Boolean {
        val time = if (arriveBy) it.endTime else it.startTime
        return time.atZoneSameInstant(london).toLocalDate() == date
    }

    private fun insideSlice(it: Itinerary, slice: Slice, arriveBy: Boolean): Boolean {
        val t = (if (arriveBy) it.endTime else it.startTime).toInstant()
        return !t.isBefore(slice.start.toInstant()) && t.isBefore(slice.end.toInstant())
    }

    private fun buildSlices(base: ZonedDateTime, date: LocalDate, arriveBy: Boolean, stepMinutes: Int): List<Slice> {
        val dayStart = date.atStartOfDay(london)
        val dayEnd = date.plusDays(1).atStartOfDay(london)
        val step = stepMinutes.toLong()
        val out = mutableListOf<Slice>()
        if (!arriveBy) {
            var cursor = if (base.isAfter(dayStart)) base else dayStart
            while (cursor.isBefore(dayEnd)) {
                val end = minOf(cursor.plusMinutes(step), dayEnd)
                out += Slice(cursor, end, cursor)
                cursor = cursor.plusMinutes(step)
            }
        } else {
            var cursor = if (base.isBefore(dayEnd)) base else dayEnd
            while (cursor.isAfter(dayStart)) {
                val start = maxOf(cursor.minusMinutes(step), dayStart)
                out += Slice(start, cursor, cursor.minusSeconds(1))
                cursor = cursor.minusMinutes(step)
            }
        }
        return out
    }

    private fun combine(a: Itinerary, b: Itinerary): Itinerary? {
        val legs = a.legs + b.legs
        val candidate = Itinerary(legs.first().startTime, legs.last().endTime, legs)
        return candidate.takeIf { JourneyLogic.isSane(it) }
    }

    private data class Slice(
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val queryTime: ZonedDateTime
    )
}
