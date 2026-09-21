package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawAiDenoiseSheet(
    state: AiDenoiseState,
    onStateChange: (AiDenoiseState) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI Denoise")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable AI Denoise")
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { onStateChange(state.copy(enabled = it)) },
                )
            }
            DenoiseSlider("Strength", state.strength) {
                onStateChange(state.copy(strength = it))
            }
            DenoiseSlider("Luminance", state.luminance) {
                onStateChange(state.copy(luminance = it))
            }
            DenoiseSlider("Color", state.color) {
                onStateChange(state.copy(color = it))
            }
            DenoiseSlider("Detail Preservation", state.detail) {
                onStateChange(state.copy(detail = it))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onApply) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun DenoiseSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..100f,
        )
    }
}
