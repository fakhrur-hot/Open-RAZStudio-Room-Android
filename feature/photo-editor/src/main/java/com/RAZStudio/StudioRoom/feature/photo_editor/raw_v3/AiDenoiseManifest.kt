package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

internal data class AiDenoiseManifest(
    val modelId: String,
    val modelVersion: String,
    val asset: String,
    val sha256: String,
    val license: String,
    val inputName: String,
    val outputName: String,
    val inputShape: LongArray,
    val outputShape: LongArray,
    val inputColorSpace: String,
    val outputColorSpace: String,
    val inputRange: FloatArray,
    val outputRange: FloatArray,
    val defaultProvider: String,
    val gpuProvider: String,
    val nnapiProvider: String,
    val adapterType: String?,
    val adapterGamma: Float,
    val adapterStrength: Float,
    val adapterMaxValue: Float,
) {
    companion object {
        fun parse(json: String): AiDenoiseManifest {
            val root = JSONObject(json)
            val input = root.getJSONObject("input")
            val output = root.getJSONObject("output")
            return AiDenoiseManifest(
                modelId = root.getString("modelId"),
                modelVersion = root.getString("modelVersion"),
                asset = root.getString("asset"),
                sha256 = root.getString("sha256").uppercase(),
                license = root.getString("license"),
                inputName = input.getString("name"),
                outputName = output.getString("name"),
                inputShape = input.getJSONArray("shape").toLongArray(),
                outputShape = output.getJSONArray("shape").toLongArray(),
                inputColorSpace = input.getString("colorSpace"),
                outputColorSpace = output.getString("colorSpace"),
                inputRange = input.getJSONArray("range").toFloatArray(),
                outputRange = output.getJSONArray("range").toFloatArray(),
                defaultProvider = root.getJSONObject("provider").getString("default"),
                gpuProvider = root.getJSONObject("provider").getString("gpu"),
                nnapiProvider = root.getJSONObject("provider").getString("nnapi"),
                adapterType = root.optJSONObject("adapter")?.getString("type"),
                adapterGamma = root.optJSONObject("adapter")?.optDouble("gamma", 2.2)?.toFloat() ?: 2.2f,
                adapterStrength = root.optJSONObject("adapter")?.optDouble("strength", 0.0)?.toFloat() ?: 0f,
                adapterMaxValue = root.optJSONObject("adapter")?.optDouble("maxValue", 65535.0)?.toFloat() ?: 65535f,
            )
        }
    }
}

internal object AiDenoiseManifestValidator {
    private const val EXPECTED_COLOR_SPACE = "RGB_FP32_NORMALIZED_0_1"
    private val expectedShape = longArrayOf(1L, 3L, 288L, 288L)

    fun loadAndValidate(context: Context, manifestAsset: String, modelAsset: String): AiDenoiseManifest {
        val manifest = context.assets.open(manifestAsset).use { AiDenoiseManifest.parse(it.reader().readText()) }
        require(manifest.asset == modelAsset) { "manifest asset mismatch" }
        require(manifest.modelId.isNotBlank() && manifest.modelVersion.isNotBlank()) { "missing model identity" }
        require(manifest.sha256.matches(Regex("[0-9A-F]{64}"))) { "invalid model checksum" }
        require(manifest.license.isNotBlank()) { "missing model license" }
        require(manifest.defaultProvider == "CPU") { "unsupported default provider" }
        require(manifest.gpuProvider == "disabled") { "GPU provider must be disabled" }
        require(manifest.nnapiProvider.isNotBlank()) { "missing NNAPI validation status" }
        require(manifest.inputName.isNotBlank() && manifest.outputName.isNotBlank()) { "missing tensor names" }
        require(manifest.inputShape.contentEquals(expectedShape)) { "unsupported input shape" }
        require(manifest.outputShape.contentEquals(expectedShape)) { "unsupported output shape" }
        require(
            manifest.inputColorSpace == EXPECTED_COLOR_SPACE ||
                manifest.inputColorSpace == "${EXPECTED_COLOR_SPACE}_LINEAR_ADAPTER",
        ) { "unsupported input color space" }
        require(
            manifest.outputColorSpace == EXPECTED_COLOR_SPACE ||
                manifest.outputColorSpace == "${EXPECTED_COLOR_SPACE}_LINEAR_ADAPTER",
        ) { "unsupported output color space" }
        require(manifest.inputRange.contentEquals(floatArrayOf(0f, 1f))) { "unsupported input range" }
        require(manifest.outputRange.contentEquals(floatArrayOf(0f, 1f))) { "unsupported output range" }
        if (manifest.adapterType != null) {
            require(manifest.adapterType == "SRGB_CLEAN_OUTPUT_BLEND") { "unsupported adapter" }
            require(manifest.adapterGamma > 1f) { "invalid adapter gamma" }
            require(manifest.adapterStrength in 0f..1f) { "invalid adapter strength" }
            require(manifest.adapterMaxValue == 65535f) { "unsupported adapter range" }
        }

        val modelBytes = context.assets.open(modelAsset).use(InputStream::readBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(modelBytes).toHex()
        require(digest == manifest.sha256) { "model checksum mismatch" }

        val session = OrtEnvironment.getEnvironment().createSession(modelBytes)
        session.use {
            val input = it.inputInfo[manifest.inputName]?.info as? TensorInfo
            val output = it.outputInfo[manifest.outputName]?.info as? TensorInfo
            require(input?.type == OnnxJavaType.FLOAT) { "unsupported input type" }
            require(output?.type == OnnxJavaType.FLOAT) { "unsupported output type" }
            require(input.shape.contentEquals(expectedShape)) { "graph input shape mismatch" }
            require(output.shape.contentEquals(expectedShape)) { "graph output shape mismatch" }
        }
        return manifest
    }
}

private fun org.json.JSONArray.toLongArray(): LongArray = LongArray(length()) { getLong(it) }
private fun org.json.JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
