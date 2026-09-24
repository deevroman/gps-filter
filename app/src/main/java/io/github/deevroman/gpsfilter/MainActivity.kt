package io.github.deevroman.gpsfilter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.deevroman.gpsfilter.ui.theme.GPSFilterTheme
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GPSFilterTheme { GpsFilterScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsFilterScreen() {
    val context = LocalContext.current.applicationContext
    val initialZones = remember { FilterStorage.seedInitialZones(context) }
    val zones = remember { mutableStateListOf<BoundingBox>().apply { addAll(initialZones) } }
    val log = remember {
        mutableStateListOf<String>().apply {
            val storedLog = FilterStorage.eventLog(context)
            if (storedLog.isEmpty()) {
                FilterStorage.appendEvent(context, context.getString(R.string.event_app_started, eventTime()))
                FilterStorage.appendEvent(context, context.getString(R.string.event_zones_loaded, eventTime(), initialZones.size))
            }
            addAll(FilterStorage.eventLog(context))
        }
    }
    var filteringEnabled by rememberSaveable { mutableStateOf(FilterStorage.filteringEnabled(context)) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showMapSettings by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var tileUrl by remember { mutableStateOf(FilterStorage.tileUrl(context)) }
    var tileAttribution by remember { mutableStateOf(FilterStorage.tileAttribution(context)) }
    var incomingPoint by remember { mutableStateOf(FilterStorage.incomingPoint(context)) }
    var filterResult by remember { mutableStateOf(FilterStorage.filterResult(context)) }
    var gpxRecording by remember { mutableStateOf(GpxTrackRecorder.isRecording(context)) }
    var importingGeoJson by remember { mutableStateOf(false) }
    var geoJsonImportError by remember { mutableStateOf<String?>(null) }

    fun refreshState() {
        filteringEnabled = FilterStorage.filteringEnabled(context)
        incomingPoint = FilterStorage.incomingPoint(context)
        filterResult = FilterStorage.filterResult(context)
        gpxRecording = GpxTrackRecorder.isRecording(context)
        log.clear()
        log.addAll(FilterStorage.eventLog(context))
    }

    fun appendUiEvent(message: String) {
        FilterStorage.appendEvent(context, "${eventTime()}  $message")
        refreshState()
    }

    fun appendUiEvent(@StringRes messageRes: Int, vararg formatArgs: Any) =
        appendUiEvent(context.getString(messageRes, *formatArgs))

    fun startFiltering() {
        FilterStorage.setFilteringEnabled(context, true)
        filteringEnabled = true
        appendUiEvent(R.string.event_start_mock_gps_requested)
        GpsFilterService.start(context)
    }

    fun toggleGpxRecording() {
        if (gpxRecording) {
            GpxTrackRecorder.stop(context)
            appendUiEvent(R.string.event_gpx_stopped)
        } else {
            GpxTrackRecorder.start(context)
                .onSuccess { appendUiEvent(R.string.event_gpx_started) }
                .onFailure { appendUiEvent(R.string.event_gpx_start_failed) }
        }
    }

    fun importGeoJson(url: String) {
        importingGeoJson = true
        geoJsonImportError = null
        Thread {
            val result = GeoJsonImporter.importFromUrl(context, url)
            Handler(Looper.getMainLooper()).post {
                importingGeoJson = false
                result.onSuccess { importedZones ->
                    zones.addAll(importedZones)
                    FilterStorage.saveZones(context, zones)
                    appendUiEvent(R.string.event_geojson_imported, importedZones.size)
                    showImportDialog = false
                }.onFailure { exception ->
                    geoJsonImportError = exception.message ?: context.getString(R.string.error_geojson_import)
                }
            }
        }.start()
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startFiltering()
        } else {
            appendUiEvent(R.string.event_location_permission_required)
        }
    }

    DisposableEffect(context) {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) = refreshState()
        }
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(GpsFilterService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(stateReceiver) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterControl(
                        filteringEnabled = filteringEnabled,
                        onToggle = {
                            if (filteringEnabled) {
                                filteringEnabled = false
                                GpsFilterService.stop(context)
                            } else if (hasFineLocationPermission(context)) {
                                startFiltering()
                            } else {
                                permissionLauncher.launch(runtimePermissions())
                            }
                        },
                        onOpenDeveloperSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { appendUiEvent(R.string.event_developer_settings_failed) }
                        },
                    )
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CurrentLocationStatus(
                        filteringEnabled = filteringEnabled,
                        incomingPoint = incomingPoint,
                        filterResult = filterResult,
                    )
                }
            }
            item {
                ZoneMap(
                    zones = zones,
                    tileUrl = tileUrl,
                    tileAttribution = tileAttribution,
                    incomingPoint = incomingPoint,
                )
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.zones_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            items(zones, key = { it.id }) { zone ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ZoneRow(
                        zone = zone,
                        onDelete = {
                            zones.remove(zone)
                            FilterStorage.saveZones(context, zones)
                            appendUiEvent(R.string.event_zone_deleted, zone.name)
                        },
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_geojson))
                    }
                    OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_bbox))
                    }
                }
            }
            item { Box(modifier = Modifier.padding(horizontal = 16.dp)) { EventLog(log = log) } }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Button(onClick = ::toggleGpxRecording, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (gpxRecording) R.string.stop_gpx_recording else R.string.start_gpx_recording))
                    }
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(
                        onClick = { showMapSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.configure_map)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddBoundingBoxDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, south, west, north, east ->
                zones.add(BoundingBox(System.currentTimeMillis(), name, south, west, north, east))
                FilterStorage.saveZones(context, zones)
                appendUiEvent(R.string.event_zone_added, name)
                showAddDialog = false
            },
        )
    }

    if (showMapSettings) {
        MapSettingsDialog(
            initialTileUrl = tileUrl,
            initialAttribution = tileAttribution,
            onDismiss = { showMapSettings = false },
            onSave = { newTileUrl, newAttribution ->
                tileUrl = newTileUrl
                tileAttribution = newAttribution
                FilterStorage.saveTileSource(context, newTileUrl, newAttribution)
                appendUiEvent(R.string.event_tile_source_changed)
                showMapSettings = false
            },
        )
    }

    if (showImportDialog) {
        GeoJsonImportDialog(
            isImporting = importingGeoJson,
            error = geoJsonImportError,
            onDismiss = { if (!importingGeoJson) showImportDialog = false },
            onImport = ::importGeoJson,
        )
    }
}

@Composable
private fun CurrentLocationStatus(
    filteringEnabled: Boolean,
    incomingPoint: FilterStorage.IncomingPoint?,
    filterResult: FilterStorage.FilterResult,
) {
    val filtered = filteringEnabled && filterResult.isFiltered && incomingPoint != null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (filtered) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when {
                incomingPoint == null -> {
                    Text(stringResource(R.string.waiting_for_location), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.location_will_appear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                filtered -> {
                    Text(stringResource(R.string.coordinates_filtered), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            R.string.filtered_location,
                            filterResult.zoneName ?: stringResource(R.string.unnamed_zone),
                            formatCoordinate(incomingPoint.latitude),
                            formatCoordinate(incomingPoint.longitude),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                else -> {
                    Text(
                        stringResource(if (filteringEnabled) R.string.coordinates_outside_zones else R.string.filtering_disabled),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterControl(
    filteringEnabled: Boolean,
    onToggle: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (filteringEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.filtering), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Switch(checked = filteringEnabled, onCheckedChange = { onToggle() })
            }
            OutlinedButton(onClick = onOpenDeveloperSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.configure_mock_gps))
            }
        }
    }
}

@Composable
private fun ZoneMap(
    zones: List<BoundingBox>,
    tileUrl: String,
    tileAttribution: String,
    incomingPoint: FilterStorage.IncomingPoint?,
) {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        OsmMap(
            zones = zones,
            tileUrl = tileUrl,
            tileAttribution = tileAttribution,
            incomingPoint = incomingPoint,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
            shape = RoundedCornerShape(topStart = 6.dp),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text(
                text = tileAttribution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ZoneRow(zone: BoundingBox, onDelete: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(zone.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.zone_coordinates,
                        formatCoordinate(zone.south),
                        formatCoordinate(zone.west),
                        formatCoordinate(zone.north),
                        formatCoordinate(zone.east),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun EventLog(log: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.debug_log), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .2f))
            log.takeLast(8).forEach { entry ->
                Text(
                    entry,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AddBoundingBoxDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double, Double, Double, Double) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var south by rememberSaveable { mutableStateOf("") }
    var west by rememberSaveable { mutableStateOf("") }
    var north by rememberSaveable { mutableStateOf("") }
    var east by rememberSaveable { mutableStateOf("") }
    val southValue = south.toDoubleOrNull()
    val westValue = west.toDoubleOrNull()
    val northValue = north.toDoubleOrNull()
    val eastValue = east.toDoubleOrNull()
    val valid = name.isNotBlank() && southValue != null && westValue != null && northValue != null && eastValue != null &&
        southValue < northValue && westValue < eastValue
    MovableDialog(
        title = stringResource(R.string.new_bbox),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bbox_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField(stringResource(R.string.south), south, { south = it }, Modifier.weight(1f))
                    CoordinateField(stringResource(R.string.west), west, { west = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField(stringResource(R.string.north), north, { north = it }, Modifier.weight(1f))
                    CoordinateField(stringResource(R.string.east), east, { east = it }, Modifier.weight(1f))
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            TextButton(enabled = valid, onClick = { onAdd(name.trim(), southValue!!, westValue!!, northValue!!, eastValue!!) }) {
                Text(stringResource(R.string.add))
            }
        },
    )
}

@Composable
private fun MapSettingsDialog(
    initialTileUrl: String,
    initialAttribution: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var tileUrl by rememberSaveable { mutableStateOf(initialTileUrl) }
    var attribution by rememberSaveable { mutableStateOf(initialAttribution) }
    val normalizedUrl = tileUrl.trim()
    val validTemplate = normalizedUrl.startsWith("https://") &&
        normalizedUrl.contains("{z}") && normalizedUrl.contains("{x}") && normalizedUrl.contains("{y}")
    MovableDialog(
        title = stringResource(R.string.map_tiles),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.tile_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tileUrl,
                    onValueChange = { tileUrl = it },
                    label = { Text(stringResource(R.string.tile_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (!validTemplate) Text(stringResource(R.string.tile_url_example))
                    },
                )
                OutlinedTextField(
                    value = attribution,
                    onValueChange = { attribution = it },
                    label = { Text(stringResource(R.string.attribution)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.attribution_hint)) },
                )
            }
        },
        actions = {
            TextButton(
                onClick = { onSave(FilterStorage.DEFAULT_TILE_URL, FilterStorage.DEFAULT_TILE_ATTRIBUTION) },
            ) { Text(stringResource(R.string.osm)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            TextButton(
                enabled = validTemplate && attribution.isNotBlank(),
                onClick = { onSave(normalizedUrl, attribution.trim()) },
            ) { Text(stringResource(R.string.save)) }
        },
    )
}

@Composable
private fun GeoJsonImportDialog(
    isImporting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val validUrl = url.trim().startsWith("https://")
    MovableDialog(
        title = stringResource(R.string.geojson_import_title),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.geojson_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting,
                    minLines = 3,
                    maxLines = 6,
                    supportingText = {
                        if (error != null) Text(error)
                    },
                )
                OutlinedButton(
                    onClick = { url = DEFAULT_GEOJSON_URL },
                    enabled = !isImporting,
                ) { Text(stringResource(R.string.default_geojson_url)) }
            }
        },
        actions = {
            TextButton(enabled = !isImporting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            TextButton(
                enabled = validUrl && !isImporting,
                onClick = { onImport(url.trim()) },
            ) { Text(stringResource(if (isImporting) R.string.loading else R.string.import_action)) }
        },
    )
}

private const val DEFAULT_GEOJSON_URL = "https://raw.githubusercontent.com/deevroman/gps-filter/master/misc/ru-spoofing.geojson"

@Composable
private fun MovableDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) },
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offset += dragAmount
                                }
                            }
                            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        content = content,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoordinateField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

@Preview(showBackground = true)
@Composable
private fun GpsFilterPreview() {
    GPSFilterTheme(dynamicColor = false) { GpsFilterScreen() }
}
