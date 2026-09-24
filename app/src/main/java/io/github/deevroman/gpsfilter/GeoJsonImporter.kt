package io.github.deevroman.gpsfilter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Imports rectangular zones from GeoJSON Features, Polygon geometries, or GeoJSON bbox fields. */
object GeoJsonImporter {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    fun importFromUrl(context: Context, url: String): Result<List<BoundingBox>> = runCatching {
        val normalizedUrl = normalizeGitHubUrl(url)
        require(normalizedUrl.startsWith("https://")) { context.getString(R.string.geojson_https_only) }
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/geo+json, application/json")
            setRequestProperty("User-Agent", "GPS-Filter/1.0")
        }
        try {
            val responseCode = connection.responseCode
            require(responseCode in 200..299) { context.getString(R.string.geojson_http_error, responseCode) }
            val payload = connection.inputStream.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= MAX_RESPONSE_BYTES) { context.getString(R.string.geojson_too_large) }
                bytes.toString(Charsets.UTF_8)
            }
            parseZones(context, JSONObject(payload))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseZones(context: Context, root: JSONObject): List<BoundingBox> {
        val objects = when (root.optString("type")) {
            "FeatureCollection" -> List(root.getJSONArray("features").length()) { index ->
                root.getJSONArray("features").getJSONObject(index)
            }
            else -> listOf(root)
        }
        val zones = objects.mapIndexedNotNull { index, value ->
            val bounds = value.optJSONArray("bbox")?.let(::boundsFromBbox) ?: boundsFromGeometry(value)
            bounds?.let { (west, south, east, north) ->
                BoundingBox(
                    id = System.currentTimeMillis() + index,
                    name = featureName(context, value, index + 1),
                    south = south,
                    west = west,
                    north = north,
                    east = east,
                )
            }
        }
        require(zones.isNotEmpty()) { context.getString(R.string.geojson_no_zones) }
        return zones
    }

    private fun featureName(context: Context, feature: JSONObject, index: Int): String {
        val properties = feature.optJSONObject("properties")
        return properties?.optString("name")?.takeIf { it.isNotBlank() }
            ?: properties?.optString("title")?.takeIf { it.isNotBlank() }
            ?: feature.optString("id").takeIf { it.isNotBlank() }
            ?: context.getString(R.string.geojson_imported_zone, index)
    }

    private fun boundsFromBbox(bbox: JSONArray): Bounds? {
        if (bbox.length() < 4) return null
        val west = bbox.optDouble(0, Double.NaN)
        val south = bbox.optDouble(1, Double.NaN)
        val east = bbox.optDouble(2, Double.NaN)
        val north = bbox.optDouble(3, Double.NaN)
        return boundsOrNull(west, south, east, north)
    }

    private fun boundsFromGeometry(feature: JSONObject): Bounds? {
        val geometry = if (feature.optString("type") == "Feature") feature.optJSONObject("geometry") else feature
        val coordinates = geometry?.optJSONArray("coordinates") ?: return null
        val values = mutableListOf<Pair<Double, Double>>()
        collectCoordinates(coordinates, values)
        if (values.isEmpty()) return null
        return boundsOrNull(
            west = values.minOf { it.first },
            south = values.minOf { it.second },
            east = values.maxOf { it.first },
            north = values.maxOf { it.second },
        )
    }

    private fun collectCoordinates(value: Any, target: MutableList<Pair<Double, Double>>) {
        if (value !is JSONArray) return
        val longitude = value.optDouble(0, Double.NaN)
        val latitude = value.optDouble(1, Double.NaN)
        if (!longitude.isNaN() && !latitude.isNaN() && value.length() >= 2 && value.opt(0) !is JSONArray) {
            target += longitude to latitude
            return
        }
        for (index in 0 until value.length()) collectCoordinates(value.opt(index), target)
    }

    private fun boundsOrNull(west: Double, south: Double, east: Double, north: Double): Bounds? =
        if (west.isNaN() || south.isNaN() || east.isNaN() || north.isNaN() || west >= east || south >= north) {
            null
        } else {
            Bounds(west, south, east, north)
        }

    private fun normalizeGitHubUrl(url: String): String {
        val trimmed = url.trim()
        val match = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$").matchEntire(trimmed)
        return if (match != null) {
            "https://raw.githubusercontent.com/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}/${match.groupValues[4]}"
        } else {
            trimmed
        }
    }

    private data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double)
}
