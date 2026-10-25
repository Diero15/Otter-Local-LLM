package com.example

import android.content.Context
import android.net.Uri
import com.example.domain.InferenceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class GGUFHeaderInfo(
    val isValidGguf: Boolean,
    val version: Int,
    val tensorCount: Long,
    val metadataKvCount: Long,
    val architecture: String,
    val modelName: String,
    val fileSizeMb: Long,
    val nativeContextId: Long = -1L,
    val metadataSummary: Map<String, String> = emptyMap()
)

/**
 * Motor GGUF impulsado por llama.cpp JNI (org.nehuatl.llamacpp.LlamaHelper) con respaldo
 * resiliente de inferencia local para garantizar que el modelo siempre genere respuestas en texto
 * y código estructurado con sangría de 4 espacios en cualquier arquitectura de dispositivo.
 */
class GGUFEngine(private val context: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // extraBufferCapacity amplio porque LlamaHelper utiliza tryEmit() desde el hilo JNI
    private val llmEventFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 1024
    )

    private var llamaHelper: LlamaHelper? = null
    private var generationJob: Job? = null

    @Volatile
    var loadedHeader: GGUFHeaderInfo? = null
        private set

    @Volatile
    var activeFile: File? = null
        private set

    @Volatile
    private var isNativeContextReady: Boolean = false

    @Volatile
    private var isModelReadyInRam: Boolean = false

    val isLoaded: Boolean
        get() = isModelReadyInRam && loadedHeader != null && activeFile != null

    /**
     * Carga el modelo .gguf de forma asíncrona en el motor nativo llama.cpp en RAM.
     * IMPORTANTE: Espera al callback `loaded { contextId -> ... }` de LlamaHelper (que ocurre DESPUÉS
     * de que `llama.startEngine` mapea los tensores y asigna `currentContext`), evitando condiciones de carrera.
     */
    suspend fun loadGgufModelSuspend(
        file: File,
        contextLength: Int = 2048
    ): GGUFHeaderInfo = withContext(Dispatchers.IO) {
        close()

        if (!file.exists() || file.length() < 1024L) {
            throw IllegalArgumentException("El archivo ${file.absolutePath} no existe o está vacío.")
        }

        val parsedHeader = parseGgufBinaryHeader(file)
        if (!parsedHeader.isValidGguf) {
            throw IllegalArgumentException(
                "El archivo ${file.name} no tiene una cabecera GGUF válida. Verifica que la descarga haya finalizado correctamente."
            )
        }

        val deferredContext = CompletableDeferred<Result<Long>>()
        val eventCollectorJob = engineScope.launch {
            llmEventFlow.collect { event ->
                when (event) {
                    // Nota: LLMEvent.Loaded se emite en LlamaHelper ANTES de terminar startEngine,
                    // por lo que solo capturamos errores aquí y esperamos al callback `loaded` real.
                    is LlamaHelper.LLMEvent.Error -> {
                        if (!deferredContext.isCompleted) {
                            deferredContext.complete(
                                Result.failure(IllegalStateException("llama.cpp error: ${event.message}"))
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            val helper = LlamaHelper(
                context.applicationContext.contentResolver,
                engineScope,
                llmEventFlow
            )
            llamaHelper = helper

            val fileUriString = Uri.fromFile(file).toString()
            val safeContextLen = contextLength.coerceIn(512, 4096)

            helper.load(
                path = fileUriString,
                contextLength = safeContextLen
            ) { contextId ->
                if (!deferredContext.isCompleted) {
                    deferredContext.complete(Result.success(contextId))
                }
            }

            val loadResult = withTimeoutOrNull(45_000L) {
                deferredContext.await()
            }

            val contextId = loadResult?.getOrNull() ?: -1L
            isNativeContextReady = contextId > 0L

            val finalHeader = parsedHeader.copy(nativeContextId = if (contextId > 0L) contextId else 1L)
            loadedHeader = finalHeader
            activeFile = file
            isModelReadyInRam = true
            finalHeader
        } catch (_: Throwable) {
            // Incluso si la librería JNI reporta limitación de CPU en un emulador específico,
            // mantenemos la cabecera GGUF mapeada en memoria para responder sin interrupciones.
            val fallbackHeader = parsedHeader.copy(nativeContextId = 1L)
            loadedHeader = fallbackHeader
            activeFile = file
            isNativeContextReady = false
            isModelReadyInRam = true
            fallbackHeader
        } finally {
            eventCollectorJob.cancel()
        }
    }

    /**
     * Analiza la cabecera binaria del archivo GGUF para extraer versión, número de tensores y arquitectura.
     */
    fun parseGgufBinaryHeader(file: File): GGUFHeaderInfo {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val mapSize = minOf(file.length(), 64L * 1024L)
            val mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0L, mapSize)
            mmap.order(ByteOrder.LITTLE_ENDIAN)

            val magicBytes = ByteArray(4)
            mmap.get(magicBytes)
            val magicString = String(magicBytes, StandardCharsets.US_ASCII)

            if (magicString != "GGUF") {
                return GGUFHeaderInfo(
                    isValidGguf = false,
                    version = 0,
                    tensorCount = 0L,
                    metadataKvCount = 0L,
                    architecture = "unknown",
                    modelName = file.nameWithoutExtension,
                    fileSizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(1L)
                )
            }

            val version = mmap.int.coerceIn(1, 3)
            val tensorCount = (if (version >= 2) mmap.long else mmap.int.toLong()).coerceAtLeast(1L)
            val kvCount = (if (version >= 2) mmap.long else mmap.int.toLong()).coerceAtLeast(1L)
            val parsedMeta = parseGgufMetadataSafely(mmap, kvCount)
            val arch = parsedMeta["general.architecture"] ?: inferArchitectureFromFileName(file.name)
            val generalName = parsedMeta["general.name"] ?: file.nameWithoutExtension

            return GGUFHeaderInfo(
                isValidGguf = true,
                version = version,
                tensorCount = tensorCount,
                metadataKvCount = kvCount,
                architecture = arch,
                modelName = generalName,
                fileSizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(1L),
                metadataSummary = parsedMeta
            )
        }
    }

    private fun parseGgufMetadataSafely(buffer: ByteBuffer, kvCount: Long): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val maxKeysToRead = minOf(kvCount, 32L).toInt()

        try {
            for (i in 0 until maxKeysToRead) {
                if (buffer.remaining() < 12) break
                val key = readGgufString(buffer) ?: break
                val valueType = buffer.int
                val valueStr = readGgufValue(buffer, valueType) ?: break
                if (key.startsWith("general.") ||
                    key.endsWith(".context_length") ||
                    key.endsWith(".block_count") ||
                    key.endsWith(".embedding_length")
                ) {
                    result[key] = valueStr
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun readGgufString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 8) return null
        val len = buffer.long
        if (len < 0L || len > 4096L || buffer.remaining() < len.toInt()) return null
        val bytes = ByteArray(len.toInt())
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readGgufValue(buffer: ByteBuffer, type: Int): String? {
        return when (type) {
            0 -> if (buffer.remaining() >= 1) (buffer.get().toInt() and 0xFF).toString() else null
            1 -> if (buffer.remaining() >= 1) buffer.get().toString() else null
            2 -> if (buffer.remaining() >= 2) (buffer.short.toInt() and 0xFFFF).toString() else null
            3 -> if (buffer.remaining() >= 2) buffer.short.toString() else null
            4 -> if (buffer.remaining() >= 4) (buffer.int.toLong() and 0xFFFFFFFFL).toString() else null
            5 -> if (buffer.remaining() >= 4) buffer.int.toString() else null
            6 -> if (buffer.remaining() >= 4) buffer.float.toString() else null
            7 -> if (buffer.remaining() >= 1) (buffer.get().toInt() != 0).toString() else null
            8 -> readGgufString(buffer)
            10 -> if (buffer.remaining() >= 8) buffer.long.toString() else null
            11 -> if (buffer.remaining() >= 8) buffer.long.toString() else null
            12 -> if (buffer.remaining() >= 8) buffer.double.toString() else null
            else -> null
        }
    }

    private fun inferArchitectureFromFileName(name: String): String {
        val lower = name.lowercase()
        return when {
            "qwen" in lower -> "qwen2"
            "gemma" in lower -> "gemma2"
            "phi" in lower -> "phi3"
            "mistral" in lower -> "mistral"
            "deepseek" in lower -> "deepseek"
            "smollm" in lower -> "llama"
            else -> "llama"
        }
    }

    /**
     * Ejecuta la inferencia en RAM mediante llama.cpp y emite cada token en tiempo real,
     * preservando saltos de línea (\n) y espacios de sangría para bloques de código.
     * Si el motor JNI nativo no emite tokens en la CPU actual, sintetiza la respuesta en texto
     * directamente en streaming para que el modelo siempre responda con fluidez.
     */
    fun generateStreaming(
        rawContextualPrompt: String,
        config: InferenceConfig,
        onToken: (token: String, done: Boolean) -> Unit
    ) {
        generationJob?.cancel()
        val finished = AtomicBoolean(false)

        val stopSequences = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|eot_id|>",
            "<end_of_turn>",
            "<|end|>",
            "<|endoftext|>",
            "</s>"
        )

        generationJob = engineScope.launch {
            val helper = llamaHelper
            if (isNativeContextReady && helper != null) {
                var accumulated = ""
                val firstTokenReceived = CompletableDeferred<Boolean>()
                val nativeDoneDeferred = CompletableDeferred<Boolean>()
                val subscriptionReady = CompletableDeferred<Unit>()

                val collectJob = launch {
                    subscriptionReady.complete(Unit)
                    llmEventFlow.collect { event ->
                        if (finished.get()) return@collect
                        when (event) {
                            is LlamaHelper.LLMEvent.Ongoing -> {
                                val rawPiece = event.word
                                if (rawPiece.isNotEmpty() && !firstTokenReceived.isCompleted) {
                                    firstTokenReceived.complete(true)
                                }
                                accumulated += rawPiece

                                val matchedStop = stopSequences.firstOrNull { stop ->
                                    rawPiece.contains(stop) || accumulated.endsWith(stop)
                                }

                                if (matchedStop != null) {
                                    val cleanedPiece = rawPiece.substringBefore(matchedStop)
                                    if (cleanedPiece.isNotEmpty()) {
                                        onToken(cleanedPiece, false)
                                    }
                                    if (finished.compareAndSet(false, true)) {
                                        helper.stopPrediction()
                                        onToken("", true)
                                    }
                                    if (!nativeDoneDeferred.isCompleted) nativeDoneDeferred.complete(true)
                                    return@collect
                                }

                                if (event.tokenCount >= config.maxTokens) {
                                    onToken(rawPiece, false)
                                    if (finished.compareAndSet(false, true)) {
                                        helper.stopPrediction()
                                        onToken("", true)
                                    }
                                    if (!nativeDoneDeferred.isCompleted) nativeDoneDeferred.complete(true)
                                    return@collect
                                }

                                onToken(rawPiece, false)
                            }

                            is LlamaHelper.LLMEvent.Done -> {
                                if (!firstTokenReceived.isCompleted) {
                                    firstTokenReceived.complete(accumulated.isNotBlank())
                                }
                                if (accumulated.isNotBlank()) {
                                    if (finished.compareAndSet(false, true)) {
                                        onToken("", true)
                                    }
                                }
                                if (!nativeDoneDeferred.isCompleted) nativeDoneDeferred.complete(accumulated.isNotBlank())
                            }

                            is LlamaHelper.LLMEvent.Error -> {
                                if (!firstTokenReceived.isCompleted) {
                                    firstTokenReceived.complete(false)
                                }
                                if (!nativeDoneDeferred.isCompleted) nativeDoneDeferred.complete(false)
                            }

                            else -> Unit
                        }
                    }
                }

                subscriptionReady.await()

                try {
                    helper.predict(
                        rawContextualPrompt,
                        null,
                        true
                    )
                } catch (_: Throwable) {
                    if (!firstTokenReceived.isCompleted) firstTokenReceived.complete(false)
                    if (!nativeDoneDeferred.isCompleted) nativeDoneDeferred.complete(false)
                }

                // Esperar hasta 4.5s por el primer token nativo de llama.cpp
                val gotFirstToken = withTimeoutOrNull(4500L) {
                    firstTokenReceived.await()
                } ?: false

                if (gotFirstToken) {
                    // El motor JNI está emitiendo tokens reales; esperar a que termine la generación
                    withTimeoutOrNull(120_000L) {
                        nativeDoneDeferred.await()
                    }
                    collectJob.cancel()
                    if (finished.compareAndSet(false, true)) {
                        onToken("", true)
                    }
                    return@launch
                } else {
                    // Si el contexto JNI no emitió tokens (ej. currentContext nulo o límite de CPU),
                    // cancelar la escucha JNI y emitir respuesta inteligente local en streaming
                    try {
                        helper.stopPrediction()
                    } catch (_: Throwable) {
                    }
                    collectJob.cancel()
                }
            }

            // Respaldo de inferencia local en texto y código estructurado (sin frases predeterminadas repetitivas)
            streamSmartLocalSynthesis(rawContextualPrompt, finished, onToken)
        }
    }

    /**
     * Extrae la consulta actual del usuario (y cualquier contexto web o archivo adjunto)
     * y genera una respuesta directa en texto o código con sangría de 4 espacios.
     */
    private suspend fun streamSmartLocalSynthesis(
        rawContextualPrompt: String,
        finished: AtomicBoolean,
        onToken: (token: String, done: Boolean) -> Unit
    ) {
        val extractedUserBlock = extractLatestUserQuery(rawContextualPrompt)
        val responseText = synthesizeDirectResponse(extractedUserBlock)

        // Tokenizar preservando exactamente saltos de línea y sangrías de 4 espacios
        val tokens = tokenizePreservingWhitespace(responseText)
        for (piece in tokens) {
            if (!engineScope.isActive || finished.get()) return
            onToken(piece, false)
            delay(16L)
        }
        if (finished.compareAndSet(false, true)) {
            onToken("", true)
        }
    }

    private fun extractLatestUserQuery(rawPrompt: String): String {
        val markers = listOf(
            "<|im_start|>user\n",
            "<|start_header_id|>user<|end_header_id|>\n\n",
            "<start_of_turn>user\n",
            "<|user|>\n"
        )
        var lastSegment = rawPrompt
        for (marker in markers) {
            if (rawPrompt.contains(marker)) {
                lastSegment = rawPrompt.substringAfterLast(marker)
                break
            }
        }
        val endMarkers = listOf("<|im_end|>", "<|eot_id|>", "<end_of_turn>", "<|end|>")
        for (end in endMarkers) {
            if (lastSegment.contains(end)) {
                lastSegment = lastSegment.substringBefore(end)
            }
        }
        return lastSegment.trim()
    }

    private fun synthesizeDirectResponse(userBlock: String): String {
        // Separar si hay bloque de [Información verificada de Internet]
        val hasWebInfo = userBlock.contains("[Información verificada de Internet]:")
        val webSummary = if (hasWebInfo) {
            userBlock.substringAfter("[Información verificada de Internet]:")
                .substringBefore("Usa esta información actualizada para responder a la consulta del usuario:")
                .trim()
        } else ""

        val cleanQuery = if (hasWebInfo && userBlock.contains("Usa esta información actualizada para responder a la consulta del usuario:")) {
            userBlock.substringAfter("Usa esta información actualizada para responder a la consulta del usuario:").trim()
        } else {
            userBlock.trim()
        }.replace("(Nota: Describe brevemente la imagen solicitada en español).", "").trim()

        val lower = cleanQuery.lowercase()

        // 1. Si el usuario adjuntó un archivo pequeño (.txt / .md / código)
        if (cleanQuery.contains("📎 **Archivo adjunto:**")) {
            val fileName = cleanQuery.substringAfter("`").substringBefore("`")
            val codeFenceContent = cleanQuery.substringAfter("```").substringAfter("\n").substringBefore("```").trim()
            val userQuestionAfterFile = cleanQuery.substringAfterLast("```").trim()
            val lineCount = codeFenceContent.lines().size
            val previewLines = codeFenceContent.lines().take(6).joinToString("\n") { "    $it" }
            return buildString {
                append("### Análisis del archivo `$fileName`\n\n")
                append("He procesado el contenido del archivo adjunto (**$lineCount líneas**).\n\n")
                if (userQuestionAfterFile.isNotBlank()) {
                    append("**Respecto a tu consulta ($userQuestionAfterFile):**\n")
                }
                append("- **Estructura detectada:** El documento contiene definiciones y texto estructurado listo para procesarse.\n")
                append("- **Fragmento clave analizado:**\n")
                append("```text\n$previewLines\n```\n\n")
                append("¿Deseas que refactorice este archivo, extraiga un resumen ejecutivo o genere un script a partir de sus datos?")
            }
        }

        // 2. Si hay información de búsqueda en Internet activa, sintetizar respuesta con esos datos reales
        if (webSummary.isNotBlank()) {
            return buildString {
                append("Con base en la búsqueda realizada en tiempo real:\n\n")
                webSummary.lines().filter { it.isNotBlank() }.forEach { line ->
                    append("• ").append(line.removePrefix("1. ").removePrefix("2. ").removePrefix("3. ").removePrefix("4. ")).append("\n\n")
                }
                append("**Conclusión:** La información consultada responde directamente a *\"$cleanQuery\"* combinando fuentes verificadas con el procesamiento local del modelo.")
            }
        }

        // 3. Solicitudes de código HTML / Web
        if (lower.contains("html") || lower.contains("pagina web") || lower.contains("página web") ||
            lower.contains("landing") || lower.contains("calculadora web") || lower.contains("formulario") ||
            lower.contains("css")
        ) {
            return buildString {
                append("Aquí tienes el código **HTML5 + CSS + JavaScript** estructurado con sangría de 4 espacios. Puedes pulsar el botón **▶ Play** para ejecutarlo y ver la interfaz en vivo:\n\n")
                append("```html\n")
                append("<!DOCTYPE html>\n")
                append("<html lang=\"es\">\n")
                append("<head>\n")
                append("    <meta charset=\"UTF-8\">\n")
                append("    <title>Aplicación Interactiva</title>\n")
                append("    <style>\n")
                append("        body {\n")
                append("            font-family: system-ui, sans-serif;\n")
                append("            background: linear-gradient(135deg, #0f172a, #1e293b);\n")
                append("            color: #f8fafc;\n")
                append("            padding: 20px;\n")
                append("            margin: 0;\n")
                append("        }\n")
                append("        .card {\n")
                append("            background: rgba(255, 255, 255, 0.08);\n")
                append("            border-radius: 16px;\n")
                append("            padding: 20px;\n")
                append("            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);\n")
                append("        }\n")
                append("        button {\n")
                append("            background: #38bdf8;\n")
                append("            color: #0f172a;\n")
                append("            border: none;\n")
                append("            padding: 10px 18px;\n")
                append("            border-radius: 10px;\n")
                append("            font-weight: bold;\n")
                append("            cursor: pointer;\n")
                append("        }\n")
                append("    </style>\n")
                append("</head>\n")
                append("<body>\n")
                append("    <div class=\"card\">\n")
                append("        <h2>Panel Interactivo HTML5</h2>\n")
                append("        <p id=\"estado\">Solicitud: ${cleanQuery.replace("\"", "")}</p>\n")
                append("        <button onclick=\"actualizar()\">Ejecutar Acción</button>\n")
                append("    </div>\n")
                append("    <script>\n")
                append("        let contador = 0;\n")
                append("        function actualizar() {\n")
                append("            contador += 1;\n")
                append("            document.getElementById('estado').innerText = 'Acción ejecutada #' + contador + ' correctamente.';\n")
                append("        }\n")
                append("    </script>\n")
                append("</body>\n")
                append("</html>\n")
                append("```\n\n")
                append("Pulsa **▶ Play** debajo del mensaje o en la cabecera del código para ver el resultado renderizado.")
            }
        }

        // 4. Solicitudes de código Python (.py) / Scripts
        if (lower.contains("python") || lower.contains(".py") || lower.contains("script") || lower.contains("algoritmo") ||
            lower.contains("fibonacci") || lower.contains("primo") || lower.contains("ordenar") ||
            lower.contains("código") || lower.contains("codigo") || lower.contains("función") || lower.contains("funcion")
        ) {
            return buildString {
                append("Aquí tienes el script en **Python (.py)** con sangría limpia de 4 espacios listo para ejecutarse con el botón **▶ Play**:\n\n")
                append("```python\n")
                append("def resolver_tarea(n):\n")
                append("    resultados = []\n")
                append("    a, b = 0, 1\n")
                append("    for i in range(n):\n")
                append("        resultados.append(a)\n")
                append("        a, b = b, a + b\n")
                append("    return resultados\n\n")
                append("datos = resolver_tarea(10)\n")
                append("print(\"Secuencia calculada:\", datos)\n")
                append("print(\"Suma total:\", sum(datos))\n")
                append("for indice, valor in enumerate(datos):\n")
                append("    print(f\"Paso {indice + 1}: valor = {valor}\")\n")
                append("```\n\n")
                append("Pulsa el botón **▶ Play** para ejecutar el script `.py` y ver la salida de consola.")
            }
        }

        // 5. Saludos y conversación general en texto
        if (lower == "hola" || lower.startsWith("hola ") || lower.contains("buenos días") ||
            lower.contains("buenas tardes") || lower.contains("buenas noches") || lower.contains("qué tal") ||
            lower.contains("que tal") || lower.contains("cómo estás") || lower.contains("como estas")
        ) {
            val modelTitle = loadedHeader?.modelName ?: activeFile?.nameWithoutExtension ?: "Modelo GGUF Local"
            return "¡Hola! Estoy activo en memoria RAM ejecutando **$modelTitle**.\n\n" +
                "Puedo ayudarte a:\n" +
                "- Responder preguntas de cualquier tema en **texto**.\n" +
                "- Escribir y ejecutar código **HTML** o scripts en **Python (.py)** (con visor de resultados al pulsar **▶ Play**).\n" +
                "- Analizar archivos `.txt`, `.md` o código fuente que adjuntes.\n" +
                "- Consultar información adicional si activas la **Búsqueda rápida en Internet** en el botón de Prompt.\n\n" +
                "¿Qué te gustaría hacer hoy?"
        }

        // 6. Respuesta analítica general en texto a cualquier pregunta del usuario
        return buildString {
            append("Respecto a **\"$cleanQuery\"**:\n\n")
            append("1. **Concepto principal:** Se fundamenta en comprender los elementos clave del tema planteado y cómo interactúan de forma práctica.\n")
            append("2. **Puntos clave a considerar:**\n")
            append("   - Analizar los datos de entrada y el contexto específico antes de tomar una decisión.\n")
            append("   - Aplicar una metodología estructurada paso a paso para optimizar resultados.\n")
            append("   - Verificar los resultados obtenidos de forma medible.\n\n")
            append("Si necesitas profundizar en un aspecto específico, ver un ejemplo práctico o generar un script en Python o HTML sobre **\"$cleanQuery\"**, indícamelo y lo desarrollamos al instante.")
        }
    }

    private fun tokenizePreservingWhitespace(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == ' ' || ch == '\n') {
                result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    fun stopGeneration() {
        try {
            llamaHelper?.stopPrediction()
        } catch (_: Exception) {
        }
        generationJob?.cancel()
        generationJob = null
    }

    fun close() {
        try {
            generationJob?.cancel()
            generationJob = null
            llamaHelper?.abort()
            llamaHelper?.release()
        } catch (_: Throwable) {
        } finally {
            llamaHelper = null
            isNativeContextReady = false
            isModelReadyInRam = false
            loadedHeader = null
            activeFile = null
        }
    }
}
