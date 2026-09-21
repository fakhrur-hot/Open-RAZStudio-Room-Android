package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context

internal object AiDenoiseModelAssetLoader {
    private val manifestCandidates = listOf(
        "models/manifest.json",
        "models/denoise_scunet_int8_288.manifest.json",
    )
    private val modelCandidates = listOf(
        "models/denoise_scunet_int8_288.onnx",
    )

    data class LoadedModel(
        val manifestAsset: String,
        val modelAsset: String,
        val manifest: AiDenoiseManifest,
    ) {
        val adapterGamma: Float get() = manifest.adapterGamma
        val adapterAlpha: Float get() = manifest.adapterStrength
        val adapterMaxValue: Float get() = manifest.adapterMaxValue
    }

    fun load(context: Context): LoadedModel {
        val manifestAsset = manifestCandidates.firstOrNull { assetExists(context, it) }
            ?: error("AI denoise manifest not found under assets/models")
        val modelAsset = modelCandidates.firstOrNull { assetExists(context, it) }
            ?: error("AI denoise model asset not found under assets/models")
        val manifest = AiDenoiseManifestValidator.loadAndValidate(context, manifestAsset, modelAsset)
        return LoadedModel(
            manifestAsset = manifestAsset,
            modelAsset = modelAsset,
            manifest = manifest,
        )
    }

    fun adapterParams(context: Context): AiDenoiseAdapterParams {
        val loaded = load(context)
        return AiDenoiseAdapterParams(
            gamma = loaded.adapterGamma,
            alpha = loaded.adapterAlpha,
            maxVal = loaded.adapterMaxValue,
        )
    }

    private fun assetExists(context: Context, assetPath: String): Boolean =
        runCatching { context.assets.open(assetPath).close(); true }.getOrDefault(false)
}

internal data class AiDenoiseAdapterParams(
    val gamma: Float,
    val alpha: Float,
    val maxVal: Float,
)
