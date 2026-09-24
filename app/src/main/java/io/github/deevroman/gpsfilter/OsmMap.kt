package io.github.deevroman.gpsfilter

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Native MapLibre map with a configurable raster tile source and bbox layers. */
@Composable
fun OsmMap(
    zones: List<BoundingBox>,
    tileUrl: String,
    tileAttribution: String,
    incomingPoint: FilterStorage.IncomingPoint?,
    focusedZone: BoundingBox?,
    focusAllZones: Boolean,
    focusRequest: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zoneSnapshot = zones.toList()
    val styleJson = remember(zoneSnapshot, tileUrl, tileAttribution, incomingPoint) {
        mapStyleJson(zoneSnapshot, tileUrl, tileAttribution, incomingPoint)
    }
    val cameraSignature = remember(zoneSnapshot, tileUrl, incomingPoint != null) {
        "$tileUrl|$zoneSnapshot|has-incoming=${incomingPoint != null}"
    }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var positionedForSignature by remember { mutableStateOf<String?>(null) }
    var handledFocusRequest by remember { mutableStateOf(-1) }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                getMapAsync { nativeMap ->
                    nativeMap.uiSettings.isAttributionEnabled = false
                    nativeMap.uiSettings.isLogoEnabled = false
                    nativeMap.uiSettings.isRotateGesturesEnabled = false
                    nativeMap.uiSettings.isTiltGesturesEnabled = false
                    map = nativeMap
                }
            }
        },
        update = {
            map?.setStyle(Style.Builder().fromJson(styleJson)) {
                if (handledFocusRequest != focusRequest && focusedZone != null) {
                    moveCameraToZone(map!!, focusedZone)
                    handledFocusRequest = focusRequest
                } else if (handledFocusRequest != focusRequest && focusAllZones) {
                    moveCameraToAllZones(map!!, zoneSnapshot, incomingPoint)
                    handledFocusRequest = focusRequest
                } else if (positionedForSignature != cameraSignature) {
                    moveCameraToDefaultView(map!!, zoneSnapshot, incomingPoint)
                    positionedForSignature = cameraSignature
                }
            }
        },
    )
}

private fun moveCameraToZone(map: MapLibreMap, zone: BoundingBox) {
    val bounds = LatLngBounds.Builder()
        .include(LatLng(zone.south, zone.west))
        .include(LatLng(zone.north, zone.east))
        .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
}

private fun moveCameraToAllZones(
    map: MapLibreMap,
    zones: List<BoundingBox>,
    incomingPoint: FilterStorage.IncomingPoint?,
) {
    if (zones.isEmpty()) {
        moveCameraToDefaultView(map, zones, incomingPoint)
        return
    }
    val bounds = LatLngBounds.Builder().apply {
        incomingPoint?.let { include(LatLng(it.latitude, it.longitude)) }
        zones.forEach { zone ->
            include(LatLng(zone.south, zone.west))
            include(LatLng(zone.north, zone.east))
        }
    }.build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
}

private fun moveCameraToDefaultView(
    map: MapLibreMap,
    zones: List<BoundingBox>,
    incomingPoint: FilterStorage.IncomingPoint?,
) {
    if (zones.isEmpty()) {
        val center = incomingPoint?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(59.94, 30.31)
        val zoom = if (incomingPoint == null) 7.0 else 14.0
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
        return
    }
    val bounds = LatLngBounds.Builder().apply {
        if (incomingPoint != null) {
            include(LatLng(incomingPoint.latitude, incomingPoint.longitude))
            zones.minByOrNull { distanceToBoxMeters(incomingPoint, it) }?.let { zone ->
                include(LatLng(zone.south, zone.west))
                include(LatLng(zone.north, zone.east))
            }
        } else {
            zones.forEach { zone ->
                include(LatLng(zone.south, zone.west))
                include(LatLng(zone.north, zone.east))
            }
        }
    }.build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
}

private fun distanceToBoxMeters(point: FilterStorage.IncomingPoint, zone: BoundingBox): Double {
    val closestLatitude = point.latitude.coerceIn(zone.south, zone.north)
    val closestLongitude = point.longitude.coerceIn(zone.west, zone.east)
    val latitudeDelta = Math.toRadians(closestLatitude - point.latitude)
    val longitudeDelta = Math.toRadians(closestLongitude - point.longitude)
    val startLatitude = Math.toRadians(point.latitude)
    val endLatitude = Math.toRadians(closestLatitude)
    val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(startLatitude) * cos(endLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return 6_371_000.0 * 2 * asin(sqrt(haversine))
}

private fun mapStyleJson(
    zones: List<BoundingBox>,
    tileUrl: String,
    tileAttribution: String,
    incomingPoint: FilterStorage.IncomingPoint?,
): String {
    val features = JSONArray()
    zones.forEach { zone ->
        val ring = JSONArray()
        ring.put(JSONArray().put(zone.west).put(zone.south))
        ring.put(JSONArray().put(zone.east).put(zone.south))
        ring.put(JSONArray().put(zone.east).put(zone.north))
        ring.put(JSONArray().put(zone.west).put(zone.north))
        ring.put(JSONArray().put(zone.west).put(zone.south))
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))),
        )
    }
    val incomingFeatures = JSONArray()
    incomingPoint?.let { point ->
        incomingFeatures.put(
            JSONObject()
                .put("type", "Feature")
                .put(
                    "geometry",
                    JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(point.longitude).put(point.latitude)),
                ),
        )
    }
    val sources = JSONObject()
        .put(
            "tiles",
            JSONObject()
                .put("type", "raster")
                .put("tiles", JSONArray().put(tileUrl))
                .put("tileSize", 256)
                .put("attribution", tileAttribution),
        )
        .put(
            "zones",
            JSONObject()
                .put("type", "geojson")
                .put("data", JSONObject().put("type", "FeatureCollection").put("features", features)),
        )
        .put(
            "incoming-point",
            JSONObject()
                .put("type", "geojson")
                .put("data", JSONObject().put("type", "FeatureCollection").put("features", incomingFeatures)),
        )
    val layers = JSONArray()
        .put(JSONObject().put("id", "tiles").put("type", "raster").put("source", "tiles"))
        .put(
            JSONObject()
                .put("id", "zones-outline")
                .put("type", "line")
                .put("source", "zones")
                .put("paint", JSONObject().put("line-color", "#b3261e").put("line-width", 2.0)),
        )
        .put(
            JSONObject()
                .put("id", "incoming-point")
                .put("type", "circle")
                .put("source", "incoming-point")
                .put(
                    "paint",
                    JSONObject()
                        .put("circle-radius", 7.0)
                        .put("circle-color", "#1a73e8")
                        .put("circle-stroke-color", "#ffffff")
                        .put("circle-stroke-width", 2.0),
                ),
        )
    return JSONObject()
        .put("version", 8)
        .put("name", "GPS Filter")
        .put("sources", sources)
        .put("layers", layers)
        .toString()
}
