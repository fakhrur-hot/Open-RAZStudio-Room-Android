package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.JpegRefineDebug
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.JpegRefineEngine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawAiDenoiseSheet(
    source: Bitmap,
    scaleModeLabel: String,
    onScale: suspend (Bitmap, Int, Int) -> Bitmap,
    onDone: (Bitmap) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    var longSide by remember { mutableStateOf(DENOISE_LONG_SIDES[0]) }
    var skipTiles by remember { mutableStateOf(true) }
    var strength by remember { mutableStateOf(50f) }
    var busy by remember { mutableStateOf(false) }
    if (!confirmed) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Resize before AI Denoise") },
            text = {
                Text(
                    "The editor image is ${source.width}×${source.height}. " +
                        "AI Denoise resizes the long side to 1006, 1226, or 1556 px " +
                        "using $scaleModeLabel before processing. The full-size editor image is not sent to the model.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmed = true }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Cancel") }
            },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI Denoise", style = MaterialTheme.typography.titleMedium)
            Text("Strength ${strength.toInt()}")
            Slider(
                value = strength,
                onValueChange = { strength = it },
                valueRange = 0f..100f,
                enabled = !busy,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Max Resolution", fontWeight = FontWeight.SemiBold)
                    DENOISE_LONG_SIDES.forEach { side ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = longSide == side,
                                onClick = { longSide = side },
                                enabled = !busy,
                            )
                            Text("$side px")
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Skip", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = skipTiles,
                            onClick = { skipTiles = true },
                            enabled = !busy,
                        )
                        Text("Skip clean tiles")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !skipTiles,
                            onClick = { skipTiles = false },
                            enabled = !busy,
                        )
                        Text("Never skip (longer)")
                    }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Text("${JpegRefineDebug.tilesDone}/${JpegRefineDebug.tilesTotal}")
                }
            } else {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val (tw, th) = fitLongSide(source.width, source.height, longSide)
                            val work = withContext(Dispatchers.Default) {
                                val scaled = onScale(source, tw, th)
                                val editor = if (scaled.width == tw && scaled.height == th) {
                                    scaled.copy(Bitmap.Config.ARGB_8888, true)
                                } else {
                                    Bitmap.createScaledBitmap(source, tw, th, true)
                                }
                                if (scaled !== source && scaled !== editor) scaled.recycle()
                                android.util.Log.i(
                                    "JpegRefine",
                                    "denoise plate ${editor.width}x${editor.height} requested ${tw}x${th} from ${source.width}x${source.height}",
                                )
                                JpegRefineEngine(context).refineExport(
                                    editor,
                                    amount = (strength / 100f) * BAKED_MAX,
                                    sourceKey = "ai-denoise|${editor.width}x${editor.height}|$scaleModeLabel|skip=$skipTiles",
                                    skipTiles = skipTiles,
                                )
                                editor
                            }
                            onDone(work)
                            onClose()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Run") }
            }
        }
    }
}

private val DENOISE_LONG_SIDES = intArrayOf(1006, 1226, 1556)
/** Slider 100 still mixes at most this much of the model. */
private const val BAKED_MAX = 0.70f

private fun fitLongSide(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
    val long = max(width, height).coerceAtLeast(1)
    if (long == longSide) return width to height
    val scale = longSide.toFloat() / long
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
