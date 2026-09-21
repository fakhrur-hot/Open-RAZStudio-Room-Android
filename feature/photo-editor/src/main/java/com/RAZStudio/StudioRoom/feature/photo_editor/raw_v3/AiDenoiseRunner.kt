package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.buildAiSessionOptions
import java.nio.FloatBuffer
import kotlin.math.pow

internal class AiDenoiseRunner(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : AutoCloseable {
    private var session: OrtSession? = null
    private var manifest: AiDenoiseManifest? = null

    fun load(context: android.content.Context, manifestAsset: String, modelAsset: String) {
        closeSession()
        val loadedManifest = AiDenoiseManifestValidator.loadAndValidate(context, manifestAsset, modelAsset)
        val modelBytes = context.assets.open(modelAsset).use { it.readBytes() }
        session = environment.createSession(modelBytes, buildAiSessionOptions())
        manifest = loadedManifest
        AppLog.i(TAG, "AI denoise model loaded: modelId=${loadedManifest.modelId} version=${loadedManifest.modelVersion} provider=${loadedManifest.defaultProvider} input=${loadedManifest.inputShape.contentToString()}")
    }

    fun runRgbTile(inputRgb: FloatArray): FloatArray {
        return runRgbTileWithDiagnostics(inputRgb).output
    }

    fun runRgbTileWithDiagnostics(inputRgb: FloatArray): AiDenoiseRunResult {
        val activeManifest = requireNotNull(manifest) { "AI denoise model is not loaded" }
        val runtime = Runtime.getRuntime()
        val freeBefore = runtime.freeMemory()
        val startedAt = System.nanoTime()
        AppLog.i(TAG, "AI denoise tile start: provider=${activeManifest.defaultProvider} inputSize=${inputRgb.size} gamma=${activeManifest.adapterGamma} strength=${activeManifest.adapterStrength}")
        val adapterInput = applyForwardAdapter(inputRgb, activeManifest)
        val nchw = AiDenoiseTensorContract.packRgbToNchw(adapterInput)
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(nchw), activeManifest.inputShape)
        input.use {
            val activeSession = requireNotNull(session) { "AI denoise model is not loaded" }
            activeSession.run(mapOf(activeManifest.inputName to input)).use { result ->
                val tensor = result[0] as OnnxTensor
                val output = tensor.floatBuffer
                val outputNchw = FloatArray(AiDenoiseTensorContract.VALUES)
                output.get(outputNchw)
                val modelOutput = AiDenoiseTensorContract.unpackNchwToRgb(outputNchw)
                val outputRgb = applyInverseAdapter(inputRgb, modelOutput, activeManifest)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                AppLog.i(TAG, "AI denoise tile complete: provider=${activeManifest.defaultProvider} elapsedMs=$elapsedMs freeBefore=$freeBefore freeAfter=${runtime.freeMemory()}")
                return AiDenoiseRunResult(
                    output = outputRgb,
                    diagnostics = AiDenoiseDiagnostics(
                        modelId = activeManifest.modelId,
                        modelVersion = activeManifest.modelVersion,
                        provider = activeManifest.defaultProvider,
                        tileWidth = AiDenoiseTensorContract.TILE_SIZE,
                        tileHeight = AiDenoiseTensorContract.TILE_SIZE,
                        coreWidth = 256,
                        coreHeight = 256,
                        tileCount = 1,
                        elapsedMs = elapsedMs,
                        freeMemoryBeforeBytes = freeBefore,
                        freeMemoryAfterBytes = runtime.freeMemory(),
                        fallbackUsed = false,
                    ),
                )
            }
        }
    }

    fun runRgbTile16(inputRgb: IntArray): IntArray {
        val normalized = AiDenoiseTensorContract.uint16ToNormalized(inputRgb)
        return AiDenoiseTensorContract.normalizedToUint16(runRgbTile(normalized))
    }

    private companion object {
        const val TAG = "AiDenoise"
    }

    override fun close() {
        closeSession()
        manifest = null
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun applyForwardAdapter(
        inputRgb: FloatArray,
        activeManifest: AiDenoiseManifest,
    ): FloatArray {
        if (activeManifest.adapterType == null) return inputRgb
        val gamma = activeManifest.adapterGamma
        return FloatArray(inputRgb.size) { inputRgb[it].coerceIn(0f, 1f).pow(1f / gamma) }
    }

    private fun applyInverseAdapter(
        inputRgb: FloatArray,
        modelOutput: FloatArray,
        activeManifest: AiDenoiseManifest,
    ): FloatArray {
        if (activeManifest.adapterType == null) return modelOutput
        val strength = activeManifest.adapterStrength
        val gamma = activeManifest.adapterGamma
        return FloatArray(modelOutput.size) { index ->
            val inputSrgb = inputRgb[index].coerceIn(0f, 1f).pow(1f / gamma)
            val blended = (inputSrgb + strength * (modelOutput[index] - inputSrgb)).coerceIn(0f, 1f)
            blended.pow(gamma)
        }
    }
}

internal data class AiDenoiseRunResult(
    val output: FloatArray,
    val diagnostics: AiDenoiseDiagnostics,
)
