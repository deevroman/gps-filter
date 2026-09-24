package io.github.deevroman.gpsfilter

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes incoming, pre-filter location fixes to a GPX 1.1 file in app-specific storage. */
object GpxTrackRecorder {
    private const val PREFS_NAME = "gps_filter"
    private const val KEY_RECORDING = "gpx_recording"
    private const val KEY_FILE_PATH = "gpx_file_path"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isRecording(context: Context): Boolean = preferences(context).getBoolean(KEY_RECORDING, false)

    fun start(context: Context): Result<File> = runCatching {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "gpx",
        ).apply { mkdirs() }
        val file = File(directory, "gps-filter-${fileTimestamp(Date())}.gpx")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
                |<gpx version="1.1" creator="GPS Filter" xmlns="http://www.topografix.com/GPX/1/1">
                |  <trk><name>GPS Filter real coordinates</name><trkseg>
                |""".trimMargin(),
        )
        preferences(context).edit()
            .putBoolean(KEY_RECORDING, true)
            .putString(KEY_FILE_PATH, file.absolutePath)
            .apply()
        file
    }

    fun append(context: Context, point: FilterStorage.IncomingPoint) {
        if (!isRecording(context)) return
        val path = preferences(context).getString(KEY_FILE_PATH, null) ?: return
        val file = File(path)
        if (!file.exists()) {
            stop(context)
            return
        }
        runCatching {
            file.appendText(
                "  <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\"><time>${gpxTime(point.timestampMillis)}</time></trkpt>\n",
            )
        }.onFailure { stop(context) }
    }

    fun stop(context: Context): String? {
        val prefs = preferences(context)
        val path = prefs.getString(KEY_FILE_PATH, null)
        if (path != null) {
            runCatching { File(path).appendText("  </trkseg></trk>\n</gpx>\n") }
        }
        prefs.edit().putBoolean(KEY_RECORDING, false).remove(KEY_FILE_PATH).apply()
        return path
    }

    private fun fileTimestamp(date: Date): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date)

    private fun gpxTime(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMillis))
}
