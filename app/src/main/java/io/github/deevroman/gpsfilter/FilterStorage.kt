package io.github.deevroman.gpsfilter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Small persistent store used by the UI and the foreground service. */
object FilterStorage {
    private const val PREFS_NAME = "gps_filter"
    private const val KEY_ZONES = "zones"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOG = "event_log"
    private const val KEY_SAFE_LATITUDE = "safe_latitude"
    private const val KEY_SAFE_LONGITUDE = "safe_longitude"
    private const val KEY_SAFE_ACCURACY = "safe_accuracy"
    private const val KEY_SAFE_TIME = "safe_time"
    private const val KEY_INCOMING_LATITUDE = "incoming_latitude"
    private const val KEY_INCOMING_LONGITUDE = "incoming_longitude"
    private const val KEY_INCOMING_TIME = "incoming_time"
    private const val KEY_LAST_POINT_FILTERED = "last_point_filtered"
    private const val KEY_FILTERED_ZONE_NAME = "filtered_zone_name"
    private const val KEY_SEED_VERSION = "seed_version"
    private const val KEY_TILE_URL = "tile_url"
    private const val KEY_TILE_ATTRIBUTION = "tile_attribution"
    private const val MAX_LOG_ENTRIES = 80
    private const val CURRENT_SEED_VERSION = 4

    data class SafePoint(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestampMillis: Long,
    )

    data class IncomingPoint(
        val latitude: Double,
        val longitude: Double,
        val timestampMillis: Long,
    )

    data class FilterResult(
        val isFiltered: Boolean,
        val zoneName: String?,
    )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun zones(context: Context): List<BoundingBox> = runCatching {
        val array = JSONArray(preferences(context).getString(KEY_ZONES, "[]"))
        val parsedZones = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BoundingBox(
                        id = item.getLong("id"),
                        name = item.getString("name"),
                        south = item.getDouble("south"),
                        west = item.getDouble("west"),
                        north = item.getDouble("north"),
                        east = item.getDouble("east"),
                    ),
                )
            }
        }
        deduplicateZones(parsedZones)
    }.getOrDefault(emptyList())

    fun saveZones(context: Context, zones: List<BoundingBox>) {
        val array = JSONArray()
        deduplicateZones(zones).forEach { zone ->
            array.put(
                JSONObject()
                    .put("id", zone.id)
                    .put("name", zone.name)
                    .put("south", zone.south)
                    .put("west", zone.west)
                    .put("north", zone.north)
                    .put("east", zone.east),
            )
        }
        preferences(context).edit().putString(KEY_ZONES, array.toString()).apply()
    }

    fun containsZoneName(zones: List<BoundingBox>, name: String): Boolean =
        zones.any { normalizedZoneName(it.name) == normalizedZoneName(name) }

    fun deduplicateZones(zones: List<BoundingBox>): List<BoundingBox> =
        zones.distinctBy { normalizedZoneName(it.name) }

    private fun normalizedZoneName(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Adds initial demo zones once, without restoring a zone deleted by the user. */
    fun seedInitialZones(context: Context): List<BoundingBox> {
        val prefs = preferences(context)
        var currentZones = zones(context)
        val seedVersion = prefs.getInt(KEY_SEED_VERSION, 0)
        if (seedVersion < 1 && currentZones.isEmpty()) {
            currentZones = listOf(
                BoundingBox(1, context.getString(R.string.zone_moscow_center), 55.7470, 37.6030, 55.7730, 37.6540),
                BoundingBox(2, context.getString(R.string.zone_home), 55.8310, 37.4550, 55.8380, 37.4690),
            )
        }
        val ladogaLake = ladogaLake(context)
        if (seedVersion < 2 && currentZones.none { it.id == ladogaLake.id }) {
            currentZones = currentZones + ladogaLake
        }
        val sheremetyevo = sheremetyevo(context)
        if (seedVersion < 3 && currentZones.none { it.id == sheremetyevo.id }) {
            currentZones = currentZones + sheremetyevo
        }
        if (seedVersion < CURRENT_SEED_VERSION) {
            currentZones = deduplicateZones(currentZones)
            saveZones(context, currentZones)
            prefs.edit().putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION).apply()
        }
        return currentZones
    }

    fun filteringEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)

    fun setFilteringEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun tileUrl(context: Context): String =
        preferences(context).getString(KEY_TILE_URL, DEFAULT_TILE_URL) ?: DEFAULT_TILE_URL

    fun tileAttribution(context: Context): String =
        preferences(context).getString(KEY_TILE_ATTRIBUTION, null)
            ?.takeUnless { it == LEGACY_DEFAULT_TILE_ATTRIBUTION }
            ?: defaultTileAttribution(context)

    fun defaultTileAttribution(context: Context): String =
        context.getString(R.string.default_osm_attribution)

    fun saveTileSource(context: Context, url: String, attribution: String) {
        preferences(context).edit().apply {
            putString(KEY_TILE_URL, url)
            if (url == DEFAULT_TILE_URL && attribution == defaultTileAttribution(context)) {
                remove(KEY_TILE_ATTRIBUTION)
            } else {
                putString(KEY_TILE_ATTRIBUTION, attribution)
            }
            apply()
        }
    }

    fun eventLog(context: Context): List<String> = runCatching {
        val array = JSONArray(preferences(context).getString(KEY_LOG, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    fun appendEvent(context: Context, event: String) {
        val entries = (eventLog(context) + event).takeLast(MAX_LOG_ENTRIES)
        val array = JSONArray()
        entries.forEach(array::put)
        preferences(context).edit().putString(KEY_LOG, array.toString()).apply()
        context.sendBroadcast(
            android.content.Intent(GpsFilterService.ACTION_STATE_CHANGED)
                .setPackage(context.packageName),
        )
    }

    fun lastSafePoint(context: Context): SafePoint? {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_SAFE_LATITUDE) || !prefs.contains(KEY_SAFE_LONGITUDE)) return null
        return SafePoint(
            latitude = Double.fromBits(prefs.getLong(KEY_SAFE_LATITUDE, 0L)),
            longitude = Double.fromBits(prefs.getLong(KEY_SAFE_LONGITUDE, 0L)),
            accuracyMeters = Float.fromBits(prefs.getInt(KEY_SAFE_ACCURACY, 0)),
            timestampMillis = prefs.getLong(KEY_SAFE_TIME, 0L),
        )
    }

    fun saveLastSafePoint(context: Context, point: SafePoint) {
        preferences(context).edit()
            .putLong(KEY_SAFE_LATITUDE, point.latitude.toBits())
            .putLong(KEY_SAFE_LONGITUDE, point.longitude.toBits())
            .putInt(KEY_SAFE_ACCURACY, point.accuracyMeters.toBits())
            .putLong(KEY_SAFE_TIME, point.timestampMillis)
            .apply()
    }

    fun incomingPoint(context: Context): IncomingPoint? {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_INCOMING_LATITUDE) || !prefs.contains(KEY_INCOMING_LONGITUDE)) return null
        return IncomingPoint(
            latitude = Double.fromBits(prefs.getLong(KEY_INCOMING_LATITUDE, 0L)),
            longitude = Double.fromBits(prefs.getLong(KEY_INCOMING_LONGITUDE, 0L)),
            timestampMillis = prefs.getLong(KEY_INCOMING_TIME, 0L),
        )
    }

    fun saveIncomingPoint(context: Context, point: IncomingPoint) {
        preferences(context).edit()
            .putLong(KEY_INCOMING_LATITUDE, point.latitude.toBits())
            .putLong(KEY_INCOMING_LONGITUDE, point.longitude.toBits())
            .putLong(KEY_INCOMING_TIME, point.timestampMillis)
            .apply()
    }

    fun filterResult(context: Context): FilterResult = FilterResult(
        isFiltered = preferences(context).getBoolean(KEY_LAST_POINT_FILTERED, false),
        zoneName = preferences(context).getString(KEY_FILTERED_ZONE_NAME, null),
    )

    fun saveFilterResult(context: Context, isFiltered: Boolean, zoneName: String? = null) {
        preferences(context).edit()
            .putBoolean(KEY_LAST_POINT_FILTERED, isFiltered)
            .putString(KEY_FILTERED_ZONE_NAME, if (isFiltered) zoneName else null)
            .apply()
    }

    private fun ladogaLake(context: Context) = BoundingBox(
        id = 3,
        name = context.getString(R.string.zone_ladoga_lake),
        south = 60.018549,
        west = 31.114999,
        north = 60.167474,
        east = 31.605264,
    )

    private fun sheremetyevo(context: Context) = BoundingBox(
        id = 4,
        name = context.getString(R.string.zone_sheremetyevo),
        south = 55.960323,
        west = 37.385142,
        north = 55.986979,
        east = 37.442777,
    )

    const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val LEGACY_DEFAULT_TILE_ATTRIBUTION = "© OpenStreetMap contributors"
}
