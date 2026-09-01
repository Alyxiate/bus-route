package com.borealroutes.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class PhotonClient(private val http: OkHttpClient) {
    suspend fun suggest(query: String, limit: Int = 8): List<Place> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", "en")
            .addQueryParameter("q", query.trim() + ", United Kingdom")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", "Boreal/5.0 Android").build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Location search returned ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: JSONArray()
            buildList {
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    val geometry = feature.optJSONObject("geometry") ?: continue
                    val coords = geometry.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue
                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val p = feature.optJSONObject("properties") ?: JSONObject()
                    val name = firstNonBlank(
                        p.optString("name"), p.optString("street"), p.optString("city"),
                        p.optString("county"), "Location"
                    )
                    val city = firstNonBlank(
                        p.optString("city"), p.optString("town"), p.optString("village"),
                        p.optString("municipality"), p.optString("county")
                    )
                    val state = p.optString("state")
                    val sub = listOf(city, state)
                        .filter { it.isNotBlank() && !it.equals(name, true) }
                        .distinct().joinToString(", ")
                    add(Place(name, GeoPoint(lat, lon), sub))
                }
            }
        }
    }

    suspend fun geocode(query: String): Place =
        suggest(query, 1).firstOrNull() ?: throw IOException("Could not find $query")

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}

data class TransitousPage(val itineraries: List<Itinerary>, val rawCount: Int)

class TransitousClient(private val http: OkHttpClient) {
    suspend fun plan(
        from: GeoPoint,
        to: GeoPoint,
        queryTime: String,
        modes: List<String>,
        arriveBy: Boolean,
        searchWindowSeconds: Int,
        radius: Int? = null
    ): TransitousPage = withContext(Dispatchers.IO) {
        val builder = "https://api.transitous.org/api/v6/plan".toHttpUrl().newBuilder()
            .addQueryParameter("fromPlace", "${from.lat},${from.lon}")
            .addQueryParameter("toPlace", "${to.lat},${to.lon}")
            .addQueryParameter("time", queryTime)
            .addQueryParameter("transitModes", modes.joinToString(","))
            .addQueryParameter("detailedLegs", "true")
            .addQueryParameter("timetableView", "true")
            .addQueryParameter("numItineraries", "32")
            .addQueryParameter("maxItineraries", "256")
            .addQueryParameter("maxTransfers", "60")
            .addQueryParameter("searchWindow", searchWindowSeconds.toString())
            .addQueryParameter("algorithm", "RAPTOR")
            .addQueryParameter("timeout", "30")
            .addQueryParameter("slowDirect", "true")
            .addQueryParameter("fastestSlowDirectFactor", "0")
            .addQueryParameter("numLegAlternatives", "0")
            .addQueryParameter("arriveBy", arriveBy.toString())
            .addQueryParameter("useRoutedTransfers", "true")
            .addQueryParameter("detailedTransfers", "true")
            .addQueryParameter("pedestrianProfile", "FOOT")
            .addQueryParameter("preTransitModes", "WALK")
            .addQueryParameter("postTransitModes", "WALK")
            .addQueryParameter("directModes", "")
            .addQueryParameter("maxMatchingDistance", "100")
        if (radius != null) {
            builder.addQueryParameter("radius", radius.toString())
        } else {
            builder.addQueryParameter("maxPreTransitTime", (20 * 60).toString())
            builder.addQueryParameter("maxPostTransitTime", (20 * 60).toString())
        }
        val url = builder.build()
        val req = Request.Builder().url(url).header("User-Agent", "Boreal/5.0 Android").build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Routing service returned ${response.code}")
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            val arr = root.optJSONArray("itineraries") ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val raw = arr.optJSONObject(i) ?: continue
                    parseItinerary(raw)?.let(::add)
                }
            }
            TransitousPage(parsed, arr.length())
        }
    }

    fun parseSavedItinerary(raw: String): Itinerary? = runCatching { parseItinerary(JSONObject(raw)) }.getOrNull()

    private fun parseItinerary(o: JSONObject): Itinerary? {
        val legsArray = o.optJSONArray("legs") ?: return null
        val legs = buildList {
            for (i in 0 until legsArray.length()) {
                parseLeg(legsArray.optJSONObject(i) ?: continue)?.let(::add)
            }
        }
        if (legs.isEmpty()) return null
        val start = parseTime(o.optString("startTime")) ?: legs.first().startTime
        val end = parseTime(o.optString("endTime")) ?: legs.last().endTime
        return Itinerary(start, end, legs, o.toString())
    }

    private fun parseLeg(o: JSONObject): Leg? {
        val start = parseTime(o.optString("startTime")) ?: return null
        val end = parseTime(o.optString("endTime")) ?: return null
        val from = parsePlace(o.optJSONObject("from"))
        val to = parsePlace(o.optJSONObject("to"))
        val agencyObj = o.optJSONObject("agency")
        val agencyName = firstNonBlank(o.optString("agencyName"), agencyObj?.optString("name").orEmpty())
        val agencyId = firstNonBlank(o.optString("agencyId"), agencyObj?.optString("id").orEmpty())
        val geometry = parseGeometry(o, from, to)
        val stops = parsePlaces(o.optJSONArray("intermediateStops") ?: o.optJSONArray("stops"))
        val steps = parseSteps(o.optJSONArray("steps"))
        return Leg(
            rawMode = o.optString("mode", "BUS"),
            from = from,
            to = to,
            startTime = start,
            endTime = end,
            routeShortName = o.optString("routeShortName"),
            routeLongName = o.optString("routeLongName"),
            lineName = o.optString("lineName"),
            agencyName = agencyName,
            agencyId = agencyId,
            headsign = o.optString("headsign"),
            routeColor = normalizeHex(firstNonBlank(
                o.optString("routeColor"), o.optJSONObject("route")?.optString("color").orEmpty()
            )),
            routeTextColor = normalizeHex(firstNonBlank(
                o.optString("routeTextColor"), o.optJSONObject("route")?.optString("textColor").orEmpty()
            )),
            distanceMetres = o.optDouble("distance", 0.0),
            geometry = geometry,
            intermediateStops = stops,
            walkingSteps = steps,
            interlineWithPreviousLeg = o.optBoolean("interlineWithPreviousLeg", false)
        )
    }

    private fun parsePlace(o: JSONObject?): Place {
        if (o == null) return Place("Stop unavailable")
        val stop = o.optJSONObject("stop")
        val name = firstNonBlank(o.optString("name"), stop?.optString("name").orEmpty(), "Stop unavailable")
        val lat = finiteNumber(o, "lat") ?: stop?.let { finiteNumber(it, "lat") }
        val lon = finiteNumber(o, "lon") ?: stop?.let { finiteNumber(it, "lon") }
            ?: finiteNumber(o, "lng") ?: stop?.let { finiteNumber(it, "lng") }
        return Place(name, if (lat != null && lon != null) GeoPoint(lat, lon) else null)
    }

    private fun parsePlaces(arr: JSONArray?): List<Place> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(parsePlace(it)) }
            }
        }
    }

    private fun parseSteps(arr: JSONArray?): List<WalkingStep> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                add(WalkingStep(
                    distanceMetres = s.optDouble("distance", 0.0),
                    relativeDirection = s.optString("relativeDirection", "CONTINUE"),
                    streetName = s.optString("streetName")
                ))
            }
        }
    }

    private fun parseGeometry(o: JSONObject, from: Place, to: Place): List<GeoPoint> {
        val geometryAny = o.opt("legGeometry") ?: o.opt("geometry")
        var encoded = ""
        var array: JSONArray? = null
        var geoJsonOrder = false
        when (geometryAny) {
            is String -> encoded = geometryAny
            is JSONObject -> {
                val points = geometryAny.opt("points")
                if (points is String) encoded = points
                if (points is JSONArray) array = points
                if (array == null) {
                    array = geometryAny.optJSONArray("coordinates")
                    geoJsonOrder = array != null
                }
                if (encoded.isBlank()) encoded = geometryAny.optString("polyline")
            }
        }
        if (encoded.isBlank()) encoded = o.optString("polyline")
        val decoded = Polyline.decodeMotis(encoded)
        if (decoded.size >= 2) return decoded
        if (array != null) {
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until array.length()) {
                val p = array.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                val a = p.optDouble(0, Double.NaN)
                val b = p.optDouble(1, Double.NaN)
                if (!a.isFinite() || !b.isFinite()) continue
                // GeoJSON coordinates are [lon, lat]. Some MOTIS point arrays are [lat, lon].
                // Prefer the explicit source order, then use a UK-aware heuristic for ambiguous arrays.
                val point = when {
                    geoJsonOrder && kotlin.math.abs(b) <= 90 && kotlin.math.abs(a) <= 180 -> GeoPoint(b, a)
                    a in 48.0..62.0 && b in -12.0..5.0 -> GeoPoint(a, b)
                    b in 48.0..62.0 && a in -12.0..5.0 -> GeoPoint(b, a)
                    kotlin.math.abs(a) <= 90 && kotlin.math.abs(b) <= 180 -> GeoPoint(a, b)
                    kotlin.math.abs(b) <= 90 && kotlin.math.abs(a) <= 180 -> GeoPoint(b, a)
                    else -> null
                }
                if (point != null) points += point
            }
            if (points.size >= 2) return points
        }
        return listOfNotNull(from.point, to.point)
    }

    private fun parseTime(value: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(value) }.getOrNull()

    private fun finiteNumber(o: JSONObject, key: String): Double? {
        if (!o.has(key)) return null
        val v = o.optDouble(key, Double.NaN)
        return v.takeIf { it.isFinite() }
    }

    private fun normalizeHex(value: String): String? {
        var s = value.trim().removePrefix("#")
        if (s.matches(Regex("[0-9a-fA-F]{3}"))) s = s.map { "$it$it" }.joinToString("")
        return if (s.matches(Regex("[0-9a-fA-F]{6}"))) "#${s.uppercase()}" else null
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}

object Network {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(55, TimeUnit.SECONDS)
            .build()
    }
    val photon by lazy { PhotonClient(http) }
    val transitous by lazy { TransitousClient(http) }
}
