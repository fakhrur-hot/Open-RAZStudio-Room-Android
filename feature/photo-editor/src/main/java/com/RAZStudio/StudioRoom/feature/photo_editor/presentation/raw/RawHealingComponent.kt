/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.graphics.Bitmap
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RawHealingComponent {
    val healRadiusPx: StateFlow<Float>
    val healActive: StateFlow<Boolean>
    val isHealing: StateFlow<Boolean>
    val healCount: StateFlow<Int>
    val healedOverlay: StateFlow<Bitmap?>
    val healDirty: StateFlow<Boolean>
    /** healCount captured when the Heal tab became active this visit —
     *  drives the "Heal ×N" Apply label and Cancel's restore target. */
    val healCountPreEdit: StateFlow<Int>

    fun setHealRadius(radius: Float)
    fun setHealActive(active: Boolean)
    fun setIsHealing(healing: Boolean)
    fun setHealedOverlay(bitmap: Bitmap?)
    fun setHealCount(count: Int)
    fun setHealDirty(dirty: Boolean)

    fun undoHeal()
    fun applyHeal()
    fun cancelHeal()
}

class RawHealingComponentImpl(
    componentContext: ComponentContext
) : RawHealingComponent, ComponentContext by componentContext {
    
    private val _healRadiusPx = MutableStateFlow(60f)
    override val healRadiusPx = _healRadiusPx.asStateFlow()

    private val _healActive = MutableStateFlow(false)
    override val healActive = _healActive.asStateFlow()

    private val _isHealing = MutableStateFlow(false)
    override val isHealing = _isHealing.asStateFlow()

    private val _healCount = MutableStateFlow(0)
    override val healCount = _healCount.asStateFlow()

    private val _healedOverlay = MutableStateFlow<Bitmap?>(null)
    override val healedOverlay = _healedOverlay.asStateFlow()

    private val _healDirty = MutableStateFlow(false)
    override val healDirty = _healDirty.asStateFlow()

    private val undoStack = mutableListOf<Bitmap?>()
    private val _healedOverlayPreEdit = MutableStateFlow<Bitmap?>(null)
    private val _healCountPreEdit = MutableStateFlow(0)
    override val healCountPreEdit = _healCountPreEdit.asStateFlow()

    override fun setHealRadius(radius: Float) { _healRadiusPx.value = radius }
    override fun setHealActive(active: Boolean) {
        _healActive.value = active
        if (active) {
            _healedOverlayPreEdit.value = _healedOverlay.value
            _healCountPreEdit.value = _healCount.value
        }
    }
    override fun setIsHealing(healing: Boolean) { _isHealing.value = healing }
    override fun setHealedOverlay(bitmap: Bitmap?) {
        undoStack.add(_healedOverlay.value)
        _healedOverlay.value = bitmap
        _healCount.value += 1
        _healDirty.value = true
    }
    override fun setHealCount(count: Int) { _healCount.value = count }
    override fun setHealDirty(dirty: Boolean) { _healDirty.value = dirty }

    override fun undoHeal() {
        if (undoStack.isNotEmpty()) {
            _healedOverlay.value = undoStack.removeAt(undoStack.size - 1)
            _healCount.value = Math.max(0, _healCount.value - 1)
            if (undoStack.isEmpty() && _healedOverlay.value == _healedOverlayPreEdit.value) {
                _healDirty.value = false
            }
        }
    }

    override fun applyHeal() {
        undoStack.clear()
        _healedOverlay.value = null
        _healedOverlayPreEdit.value = null
        _healCountPreEdit.value = _healCount.value
        _healDirty.value = false
        _healActive.value = false
    }

    override fun cancelHeal() {
        undoStack.clear()
        _healedOverlay.value = _healedOverlayPreEdit.value
        _healCount.value = _healCountPreEdit.value
        _healDirty.value = false
        _healActive.value = false
    }
}
