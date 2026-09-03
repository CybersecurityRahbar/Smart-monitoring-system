package com.smarttraffic.app.features.calibration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.data.analysis.FileCalibrationStore
import com.smarttraffic.app.domain.analysis.CalibrationBuilder
import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.HomographyEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CalibrationPointState(
    val pixel: Pair<Double, Double>? = null,
    val groundX: String = "",
    val groundY: String = "",
)

@Composable
fun CalibrationLabScreen(
    paddingValues: PaddingValues,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var calibrationId by remember { mutableStateOf("road-camera-1") }
    var thresholdText by remember { mutableStateOf("0.25") }
    var imageError by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val points = remember { mutableStateListOf<CalibrationPointState>().apply { repeat(8) { add(CalibrationPointState()) } } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageError = null
        saveMessage = null
        runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "Unable to open selected image" }
                requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode selected image" }
            }
        }.onSuccess { decoded ->
            bitmap = decoded
            selectedPointIndex = null
            repeat(points.size) { points[it] = CalibrationPointState() }
        }.onFailure { imageError = it.message ?: "Unable to load image" }
    }

    Surface(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Camera Calibration Lab", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Create a real image-to-ground homography from measured road points. Speed remains blocked until a saved profile passes the quality gates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AddPhotoAlternate, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Reference image", style = MaterialTheme.typography.titleMedium)
                        }
                        Button(onClick = { picker.launch(arrayOf("image/*")) }) {
                            Text(if (bitmap == null) "Choose image" else "Replace image")
                        }
                        bitmap?.let { image ->
                            Text(
                                "Source: ${image.width} × ${image.height} pixels",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        imageError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                bitmap?.let { image ->
                    CalibrationImage(
                        bitmap = image,
                        points = points,
                        selectedPointIndex = selectedPointIndex,
                        onTap = { x, y ->
                            val target = selectedPointIndex
                                ?: points.indexOfFirst { it.pixel == null }.takeIf { it >= 0 }
                            if (target != null) {
                                points[target] = points[target].copy(pixel = x to y)
                                selectedPointIndex = if (points.any { it.pixel == null }) {
                                    points.indexOfFirst { it.pixel == null }
                                } else null
                                saveMessage = null
                            }
                        },
                    )
                } ?: EmptyCalibrationImage()
            }

            item {
                Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Profile", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = calibrationId,
                            onValueChange = { calibrationId = it.take(64) },
                            label = { Text("Calibration ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = thresholdText,
                            onValueChange = { thresholdText = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                            label = { Text("Maximum target reprojection error (m)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Tap four or more known road points. Record their real ground coordinates in metres using one consistent road coordinate system (for example, lane width and measured distance markers).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(points) { index, point ->
                CalibrationPointRow(
                    index = index,
                    point = point,
                    selected = selectedPointIndex == index,
                    onSelect = { selectedPointIndex = index },
                    onGroundXChanged = { value -> points[index] = point.copy(groundX = value) },
                    onGroundYChanged = { value -> points[index] = point.copy(groundY = value) },
                    onClear = {
                        points[index] = CalibrationPointState()
                        if (selectedPointIndex == index) selectedPointIndex = null
                    },
                )
            }

            item {
                val validPointCount = points.count { it.pixel != null && it.groundX.toDoubleOrNull()?.isFinite() == true && it.groundY.toDoubleOrNull()?.isFinite() == true }
                Button(
                    enabled = bitmap != null && validPointCount >= 4 && !saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val image = bitmap ?: return@Button
                        val reprojectionThreshold = thresholdText.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it > 0.0 }
                            ?: run {
                                saveMessage = "Enter a positive reprojection threshold."
                                return@Button
                            }
                        val selected = points.filter {
                            it.pixel != null && it.groundX.toDoubleOrNull()?.isFinite() == true && it.groundY.toDoubleOrNull()?.isFinite() == true
                        }
                        if (selected.size < 4) {
                            saveMessage = "At least four complete correspondence points are required."
                            return@Button
                        }

                        saving = true
                        saveMessage = null
                        scope.launch {
                            runCatching {
                                val store = FileCalibrationStore(context)
                                val previousVersion = withContext(Dispatchers.IO) {
                                    store.get(calibrationId.trim())?.version ?: 0
                                }
                                CalibrationBuilder.build(
                                    id = calibrationId.trim(),
                                    imageWidth = image.width,
                                    imageHeight = image.height,
                                    imagePoints = selected.map { HomographyEstimator.Point(it.pixel!!.first, it.pixel.second) },
                                    groundPointsMeters = selected.map { HomographyEstimator.Point(it.groundX.toDouble(), it.groundY.toDouble()) },
                                    version = previousVersion + 1,
                                    reprojectionThresholdMeters = reprojectionThreshold,
                                    iterations = 1000,
                                ).also { profile ->
                                    withContext(Dispatchers.IO) { store.save(profile) }
                                }
                            }.onSuccess { profile ->
                                saveMessage = "Saved ${profile.id} v${profile.version}: ${profile.homographyInlierCount ?: 0} inliers, ratio ${"%.3f".format(profile.homographyInlierRatio ?: 0.0)}, mean target reprojection error ${"%.3f m".format(profile.reprojectionErrorTargetUnits ?: Double.NaN)}"
                            }.onFailure {
                                saveMessage = it.message ?: "Calibration failed quality gates."
                            }
                            saving = false
                        }
                    },
                ) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (saving) "Validating and saving…" else "Build and save validated calibration")
                }

                saveMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.startsWith("Saved ")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("How this is used", style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "The saved profile contains the camera image dimensions, 3×3 homography, version, measured reprojection error and inlier ratio. The Analysis Lab can select it; the analysis pipeline still checks that the source dimensions and calibration quality match before publishing physical speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalibrationImage(
    bitmap: Bitmap,
    points: List<CalibrationPointState>,
    selectedPointIndex: Int?,
    onTap: (Double, Double) -> Unit,
) {
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Calibration reference image",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, selectedPointIndex) {
                    detectTapGestures { position ->
                        val x = (position.x / size.width.toFloat() * bitmap.width).coerceIn(0f, bitmap.width.toFloat()).toDouble()
                        val y = (position.y / size.height.toFloat() * bitmap.height).coerceIn(0f, bitmap.height.toFloat()).toDouble()
                        onTap(x, y)
                    }
                },
        ) {
            points.forEachIndexed { index, point ->
                point.pixel?.let { (x, y) ->
                    val px = (x / bitmap.width) * size.width
                    val py = (y / bitmap.height) * size.height
                    drawCircle(
                        color = if (index == selectedPointIndex) Color.Yellow else Color.Red,
                        radius = 9f,
                        center = Offset(px.toFloat(), py.toFloat()),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3f,
                        center = Offset(px.toFloat(), py.toFloat()),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationPointRow(
    index: Int,
    point: CalibrationPointState,
    selected: Boolean,
    onSelect: () -> Unit,
    onGroundXChanged: (String) -> Unit,
    onGroundYChanged: (String) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        onClick = onSelect,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Point ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClear, enabled = point.pixel != null || point.groundX.isNotBlank() || point.groundY.isNotBlank()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear point")
                }
            }
            Text(
                point.pixel?.let { "Image pixel: (${"%.1f".format(it.first)}, ${"%.1f".format(it.second)})" } ?: "Tap the image to set the image coordinate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = point.groundX,
                    onValueChange = onGroundXChanged,
                    label = { Text("Ground X (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = point.groundY,
                    onValueChange = onGroundYChanged,
                    label = { Text("Ground Y (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyCalibrationImage() {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            Text("Choose a camera frame before selecting calibration points.")
        }
    }
}
