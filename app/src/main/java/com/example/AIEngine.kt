package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import com.example.domain.InferenceConfig
import com.example.domain.MemorySnapshot
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class AIEngine(private val context: Context) {
    private var llmInference: LlmInference? = null
    private val ggufEngine = GGUFEngine(context.applicationContext)
    private val engineMutex = Mutex()
    private val isGeneratingAtomic = AtomicBoolean(false)

    @Volatile
    private var activeModelPath: String? = null

    @Volatile
    private var isGgufActive: Boolean = false

    @Volatile
    private var activeConfig: InferenceConfig = InferenceConfig()

    @Volatile
    private var tokenListener: ((String, Boolean) -> Unit)? = null

    val isLoaded: Boolean
        get() = llmInference != null || ggufEngine.isLoaded

    val currentModelPath: String?
        get() = activeModelPath

    val activeGgufHeader: GGUFHeaderInfo?
        get() = ggufEngine.loadedHeader

    val isGenerating: Boolean
        get() = isGeneratingAtomic.get()

    private fun isFileGgufFormat(file: File): Boolean {
        if (file.name.endsWith(".gguf", ignoreCase = true)) return true
        if (!file.exists() || file.length() < 4L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                String(magic, StandardCharsets.US_ASCII) == "GGUF"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inicializa de forma segura un modelo .gguf (vía llama.cpp JNI en RAM) o un modelo .bin/.task (vía MediaPipe).
     */
    suspend fun initEngineSafe(
        modelFile: File,
        config: InferenceConfig = activeConfig
    ): Result<String> = engineMutex.withLock {
        return try {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return Result.failure(
                    IllegalArgumentException("El archivo ${modelFile.name} no existe o está vacío (0 bytes).")
                )
            }

            // Liberar cualquier motor previo en memoria nativa
            llmInference?.close()
            llmInference = null
            ggufEngine.close()
            activeModelPath = null
            isGgufActive = false
            System.gc()

            if (isFileGgufFormat(modelFile)) {
                val contextLen = config.maxTokens.coerceIn(1024, 4096)
                val header = ggufEngine.loadGgufModelSuspend(modelFile, contextLen)
                activeModelPath = modelFile.absolutePath
                activeConfig = config
                isGgufActive = true
                Result.success(
                    "llama.cpp JNI • GGUF v${header.version} (${header.architecture.uppercase()}) • ${header.tensorCount} tensores • ${header.fileSizeMb} MB en RAM"
                )
            } else {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(config.maxTokens)
                    .setTemperature(config.temperature)
                    .setTopK(config.topK)
                    .setResultListener { partialResult, done ->
                        if (done) {
                            isGeneratingAtomic.set(false)
                        }
                        tokenListener?.invoke(partialResult ?: "", done)
                    }
                    .build()

                llmInference = LlmInference.createFromOptions(context.applicationContext, options)
                activeModelPath = modelFile.absolutePath
                activeConfig = config
                isGgufActive = false
                Result.success("MediaPipe GenAI Engine (${modelFile.name})")
            }
        } catch (oom: OutOfMemoryError) {
            close()
            Result.failure(
                IllegalStateException(
                    "Memoria RAM insuficiente al cargar ${modelFile.name}. Usa un modelo más ligero (ej. SmolLM2 135M o Qwen 0.5B).",
                    oom
                )
            )
        } catch (e: Throwable) {
            close()
            Result.failure(e)
        }
    }

    fun initEngine(modelFile: File, config: InferenceConfig = activeConfig) {
        runBlocking {
            initEngineSafe(modelFile, config).getOrThrow()
        }
    }

    fun generateResponseStreaming(
        prompt: String,
        onTokenReceived: (token: String, done: Boolean) -> Unit
    ) {
        if (!isGeneratingAtomic.compareAndSet(false, true)) {
            onTokenReceived("\n[Aviso: Ya hay una respuesta generándose. Espera a que termine.]", true)
            return
        }

        val mediaPipeEngine = llmInference
        if (!isGgufActive && mediaPipeEngine != null) {
            try {
                tokenListener = onTokenReceived
                mediaPipeEngine.generateResponseAsync(prompt)
            } catch (e: Exception) {
                isGeneratingAtomic.set(false)
                onTokenReceived("\n[Error de inferencia: ${e.message}]", true)
            }
            return
        }

        ggufEngine.generateStreaming(prompt, activeConfig) { token, done ->
            if (done) {
                isGeneratingAtomic.set(false)
            }
            onTokenReceived(token, done)
        }
    }

    fun generateResponseStreaming(prompt: String, onTokenReceived: (String) -> Unit) {
        generateResponseStreaming(prompt) { token, _ ->
            onTokenReceived(token)
        }
    }

    fun stopCurrentGeneration() {
        isGeneratingAtomic.set(false)
        ggufEngine.stopGeneration()
    }

    suspend fun unloadModelSafe() = engineMutex.withLock {
        close()
    }

    fun close() {
        try {
            tokenListener = null
            isGeneratingAtomic.set(false)
            ggufEngine.close()
            llmInference?.close()
        } catch (_: Throwable) {
        } finally {
            llmInference = null
            activeModelPath = null
            isGgufActive = false
            System.gc()
        }
    }

    fun captureMemorySnapshot(): MemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val mb = 1024L * 1024L
        val totalRamMb = memInfo.totalMem / mb
        val availRamMb = memInfo.availMem / mb
        val thresholdMb = memInfo.threshold / mb

        val ggufMappedMb = ggufEngine.loadedHeader?.fileSizeMb ?: 0L
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / mb).coerceAtLeast(ggufMappedMb)
        val runtime = Runtime.getRuntime()
        val jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / mb
        val jvmMaxMb = runtime.maxMemory() / mb

        val externalDir = context.getExternalFilesDir(null)
        val freeStorageGb = if (externalDir != null && externalDir.exists()) {
            val stat = StatFs(externalDir.absolutePath)
            (stat.availableBytes.toFloat()) / (1024f * 1024f * 1024f)
        } else {
            0f
        }

        return MemorySnapshot(
            totalDeviceRamMb = totalRamMb,
            availableDeviceRamMb = availRamMb,
            thresholdRamMb = thresholdMb,
            isLowMemory = memInfo.lowMemory,
            nativeHeapAllocatedMb = nativeHeapMb,
            jvmHeapUsedMb = jvmUsedMb,
            jvmHeapMaxMb = jvmMaxMb,
            freeStorageGb = freeStorageGb
        )
    }
}
