package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

/** Open edition ABI stub. The LUT and LUT Adj tabs are permanently disabled. */
@Composable
internal fun RawLutTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    subjectMaskReady: Boolean = false,
    showPicker: Boolean = true,
    showFinishing: Boolean = true,
    onSaveEditAsLut: (suspend (String) -> String?)? = null,
) = Unit
