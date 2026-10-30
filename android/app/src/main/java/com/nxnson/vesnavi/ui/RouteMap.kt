package com.nxnson.vesnavi.ui

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.navcore.Coordinate
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** OpenFreeMap: free OpenStreetMap vector tiles, no key. The style carries its own attribution. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private val ACCENT = AndroidColor.parseColor("#1C9E8F")
private val PARKING = AndroidColor.parseColor("#007AFF")

private fun Coordinate.point(): Point = Point.fromLngLat(longitude, latitude)
private fun Coordinate.latLng() = LatLng(latitude, longitude)
private val empty: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

/** Approximate circle of `radius` metres as a 32-point polygon (parking accuracy). */
private fun circle(center: Coordinate, radius: Double): Polygon {
    val dLat = radius / 111_320.0
    val dLon = radius / (111_320.0 * cos(Math.toRadians(center.latitude)))
    val ring = (0..32).map { i ->
        val a = 2 * Math.PI * i / 32
        Point.fromLngLat(center.longitude + dLon * cos(a), center.latitude + dLat * sin(a))
    }
    return Polygon.fromLngLats(listOf(ring))
}

/**
 * MapLibre map with the route, destination, parking spot and the rider's position.
 * `recenter` changes when the rider taps the location button; `bottomPaddingPx` keeps fitted
 * routes clear of the bottom card.
 */
@Composable
fun RouteMap(model: AppModel, recenter: Int, bottomPaddingPx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isTiltGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource("parkingAccuracy"))
                s.addSource(GeoJsonSource("route"))
                s.addSource(GeoJsonSource("dest"))
                s.addSource(GeoJsonSource("parking"))
                s.addSource(GeoJsonSource("me"))
                s.addLayer(FillLayer("parkingAccuracy", "parkingAccuracy").withProperties(PropertyFactory.fillColor(PARKING), PropertyFactory.fillOpacity(0.15f)))
                s.addLayer(
                    LineLayer("route", "route").withProperties(
                        PropertyFactory.lineColor(ACCENT), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                s.addLayer(dot("dest", ACCENT, 9f))
                s.addLayer(dot("parking", PARKING, 9f))
                s.addLayer(dot("me", AndroidColor.parseColor("#0A84FF"), 8f))
                style = s
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    // Data layers follow the model.
    val route = model.route
    val destination = model.destination
    val parking = model.places.places.parking
    val phase = model.phase
    val sim = model.simulatedPosition
    val gps = model.location.lastLocation
    val me = sim ?: gps?.let { Coordinate(it.latitude, it.longitude) }

    LaunchedEffect(style, route) {
        val src = style?.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        val line = route?.polyline?.takeIf { it.size >= 2 }
        if (line == null) src.setGeoJson(empty) else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(line.map { it.point() })))
    }
    LaunchedEffect(style, destination) {
        val src = style?.getSourceAs<GeoJsonSource>("dest") ?: return@LaunchedEffect
        val d = destination
        if (d == null) src.setGeoJson(empty) else src.setGeoJson(Feature.fromGeometry(d.coordinate.point()))
    }
    LaunchedEffect(style, parking, phase) {
        val s = style ?: return@LaunchedEffect
        val p = parking?.takeIf { phase != AppModel.Phase.NAVIGATING }
        s.getSourceAs<GeoJsonSource>("parking")?.let { if (p == null) it.setGeoJson(empty) else it.setGeoJson(Feature.fromGeometry(p.coordinate.point())) }
        s.getSourceAs<GeoJsonSource>("parkingAccuracy")?.let {
            val acc = p?.accuracy
            if (p == null || acc == null || acc <= 10) it.setGeoJson(empty) else it.setGeoJson(Feature.fromGeometry(circle(p.coordinate, acc)))
        }
    }
    LaunchedEffect(style, me) {
        val src = style?.getSourceAs<GeoJsonSource>("me") ?: return@LaunchedEffect
        if (me == null) src.setGeoJson(empty) else src.setGeoJson(Feature.fromGeometry(me.point()))
    }

    // Camera: user on launch / recenter, whole route in preview, follow while navigating.
    LaunchedEffect(map, recenter, gps != null, phase) {
        val m = map ?: return@LaunchedEffect
        val here = gps ?: return@LaunchedEffect
        if (centeredOnce && recenter == 0 && phase == AppModel.Phase.IDLE) {
            // Back from navigating: flat, north-up map like before.
            if (m.cameraPosition.tilt > 0) {
                m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(here.latitude, here.longitude)).zoom(15.0).tilt(0.0).bearing(0.0).build()))
            }
            return@LaunchedEffect
        }
        if (centeredOnce && recenter == 0) return@LaunchedEffect
        centeredOnce = true
        val flat = CameraPosition.Builder().target(LatLng(here.latitude, here.longitude)).zoom(15.0).tilt(0.0).bearing(0.0).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(flat))
    }
    LaunchedEffect(map, phase, route) {
        val m = map ?: return@LaunchedEffect
        val r = route ?: return@LaunchedEffect
        if (phase != AppModel.Phase.PREVIEW) return@LaunchedEffect
        val pad = with(density) { 48.dp.toPx() }.toInt()
        runCatching {
            val bounds = LatLngBounds.Builder().includes(r.polyline.map { it.latLng() }).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad * 3, pad, bottomPaddingPx + pad))
        }
    }
    LaunchedEffect(map, phase, me) {
        val m = map ?: return@LaunchedEffect
        val p = me ?: return@LaunchedEffect
        if (phase != AppModel.Phase.NAVIGATING) return@LaunchedEffect
        val bearing = gps?.takeIf { sim == null && it.hasBearing() && it.speed > 2 }?.bearing?.toDouble() ?: m.cameraPosition.bearing
        val position = CameraPosition.Builder().target(p.latLng()).zoom(16.5).tilt(45.0).bearing(bearing).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 900)
    }
}

private fun dot(id: String, color: Int, radius: Float) = CircleLayer(id, id).withProperties(
    PropertyFactory.circleColor(color),
    PropertyFactory.circleRadius(radius),
    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
    PropertyFactory.circleStrokeWidth(3f),
)
