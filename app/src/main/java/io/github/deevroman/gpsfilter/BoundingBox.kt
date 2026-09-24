package io.github.deevroman.gpsfilter

/** Geographic rectangle in WGS 84 decimal degrees. */
data class BoundingBox(
    val id: Long,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south < north) { "South border must be lower than north border" }
        require(west < east) { "West border must be left of east border" }
    }

    /** Borders are inclusive: a point exactly on a border is treated as private. */
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east
}
