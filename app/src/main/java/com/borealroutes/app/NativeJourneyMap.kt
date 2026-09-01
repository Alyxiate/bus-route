package com.borealroutes.app

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val BOREAL_MAP_STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val BOREAL_MAP_STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun NativeJourneyMap(
    itinerary: Itinerary,
    modifier: Modifier = Modifier,
    focusedLeg: Leg? = null
) {
    val mapView = rememberBorealMapView()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val mapStyle = if (darkTheme) BOREAL_MAP_STYLE_DARK else BOREAL_MAP_STYLE_LIGHT
    val routeKey = remember(itinerary, focusedLeg, mapStyle) {
        JourneyLogic.itineraryKey(itinerary) + "|" + (focusedLeg?.let { "${it.startTime}-${it.endTime}" } ?: "all") + "|" + mapStyle
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                map.uiSettings.apply {
                    isCompassEnabled = true
                    isZoomGesturesEnabled = true
                    isScrollGesturesEnabled = true
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isAttributionEnabled = true
                    isLogoEnabled = true
                }
                val tagged = view.tag as? String
                if (tagged == routeKey && map.style != null) return@getMapAsync
                view.tag = routeKey
                map.setStyle(Style.Builder().fromUri(mapStyle)) { style ->
                    addJourneyLayers(style, itinerary, focusedLeg)
                    val points = mapPoints(itinerary, focusedLeg)
                    if (points.isNotEmpty()) {
                        if (points.size == 1) {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 14.0))
                        } else {
                            val builder = LatLngBounds.Builder()
                            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
                            runCatching { builder.build() }.getOrNull()?.let { bounds ->
                                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 54))
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun addJourneyLayers(style: Style, itinerary: Itinerary, focusedLeg: Leg?) {
    val legs = if (focusedLeg != null) listOf(focusedLeg) else itinerary.mergedLegs()
    legs.forEachIndexed { index, leg ->
        val coords = geometryFor(leg)
        if (coords.size < 2) return@forEachIndexed
        val sourceId = "boreal-leg-$index"
        val line = LineString.fromLngLats(coords.map { Point.fromLngLat(it.lon, it.lat) })
        style.addSource(GeoJsonSource(sourceId, line))

        val walk = leg.effectiveMode() in setOf("FOOT", "WALK")
        if (!walk) {
            style.addLayer(
                LineLayer("$sourceId-outline", sourceId).withProperties(
                    lineColor(Color.argb(230, 245, 245, 248)),
                    lineWidth(8f),
                    lineOpacity(0.92f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        val routeLine = LineLayer("$sourceId-main", sourceId).withProperties(
            lineColor(if (walk) Color.rgb(170, 166, 177) else RouteColours.android(leg)),
            lineWidth(if (walk) 4f else 5f),
            lineOpacity(1f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        if (walk) routeLine.setProperties(lineDasharray(arrayOf(1.3f, 1.8f)))
        style.addLayer(routeLine)
    }

    val stops = mutableListOf<Point>()
    val displayed = legs
    displayed.firstOrNull()?.from?.point?.let { stops += Point.fromLngLat(it.lon, it.lat) }
    displayed.forEach { it.to.point?.let { p -> stops += Point.fromLngLat(p.lon, p.lat) } }
    if (stops.isNotEmpty()) {
        val sourceId = "boreal-stops"
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(stops.map { org.maplibre.geojson.Feature.fromGeometry(it) })))
        style.addLayer(
            CircleLayer("boreal-stop-circles", sourceId).withProperties(
                circleRadius(5.5f),
                circleColor(Color.rgb(23, 22, 29)),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.5f)
            )
        )
    }
}

private fun geometryFor(leg: Leg): List<GeoPoint> {
    if (leg.geometry.size >= 2) return leg.geometry
    val a = leg.from.point
    val b = leg.to.point
    return if (a != null && b != null) listOf(a, b) else emptyList()
}

private fun mapPoints(itinerary: Itinerary, focusedLeg: Leg?): List<GeoPoint> {
    val legs = if (focusedLeg != null) listOf(focusedLeg) else itinerary.mergedLegs()
    return legs.flatMap(::geometryFor).filter { it.lat in -85.0..85.0 && it.lon in -180.0..180.0 }
}

@Composable
private fun rememberBorealMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false

        fun syncInitialState() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.onStart(); started = true
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                mapView.onResume(); resumed = true
            }
        }
        syncInitialState()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) runCatching { mapView.onPause() }
            if (started) runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }
    return mapView
}
