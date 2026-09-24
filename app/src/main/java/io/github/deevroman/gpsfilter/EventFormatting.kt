package io.github.deevroman.gpsfilter

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun eventTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
