package com.example.data.remote

import com.example.data.local.ChatMessageEntity
import com.example.domain.AttachedImageData
import com.example.domain.OpenCodeModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class OpenCodeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    @Volatile
    private var activeCall: Call? = null

    fun cancelActiveCall() {
        activeCall?.cancel()
        activeCall = null
    }

    /**
     * Envía una solicitud de streaming al endpoint de OpenCode / OpenAI-compatible.
     * Soporta texto puro y multimodal (imágenes en base64 para Visión / OCR).
     */
    suspend fun streamChatCompletion(
        apiKey: String,
        baseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
        model: OpenCodeModelSpec,
        systemPrompt: String,
        contextHistory: List<ChatMessageEntity>,
        currentUserText: String,
        attachedImage: AttachedImageData?,
        temperature: Float = 0.7f,
        maxTokens: Int = 2048,
        onToken: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val effectiveUrl = if (baseUrl.isBlank()) "https://openrouter.ai/api/v1/chat/completions" else baseUrl.trim()

        val jsonBody = JSONObject().apply {
            put("model", model.id)
            put("stream", true)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)

            val messagesArray = JSONArray()

            // 1. Mensaje de Sistema con instrucciones de código y visión
            if (systemPrompt.isNotBlank()) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt.trim())
                    }
                )
            }

            // 2. Historial de mensajes recientes
            val filteredContext = contextHistory.filter { it.content.isNotBlank() && !it.content.startsWith("¡Hola!") }
            for (msg in filteredContext) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", if (msg.isUser) "user" else "assistant")
                        put("content", msg.content.trim())
                    }
                )
            }

            // 3. Mensaje actual del usuario (con imagen multimodal si está presente)
            val currentUserMsg = JSONObject().apply {
                put("role", "user")

                if (attachedImage != null) {
                    val contentParts = JSONArray()

                    // Texto del usuario (con indicación de análisis de imagen si no especificó otra cosa)
                    val textInstruction = if (currentUserText.isNotBlank()) {
                        currentUserText
                    } else {
                        "Analiza detalladamente esta imagen. Si contiene texto, transcríbelo (OCR). Describe todos los elementos visuales clave."
                    }

                    contentParts.put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", textInstruction)
                        }
                    )

                    // Objeto de imagen en formato data URI (base64)
                    val mime = if (attachedImage.mimeType.isNotBlank()) attachedImage.mimeType else "image/jpeg"
                    val dataUri = "data:$mime;base64,${attachedImage.base64Data}"

                    contentParts.put(
                        JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().apply {
                                    put("url", dataUri)
                                    put("detail", "auto")
                                }
                            )
                        }
                    )

                    put("content", contentParts)
                } else {
                    put("content", currentUserText)
                }
            }

            messagesArray.put(currentUserMsg)
            put("messages", messagesArray)
        }

        val requestBuilder = Request.Builder()
            .url(effectiveUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/diero777/Otter-Local-LLM")
            .addHeader("X-Title", "Otter Local LLM")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (cleanKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $cleanKey")
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                val parsedError = try {
                    val errJson = JSONObject(errBody)
                    if (errJson.has("error")) {
                        val inner = errJson.getJSONObject("error")
                        inner.optString("message", errBody)
                    } else {
                        errBody
                    }
                } catch (e: Exception) {
                    errBody.ifBlank { "Código HTTP ${response.code}" }
                }

                val userFriendly = when (response.code) {
                    401 -> "Error 401: API Key de OpenCode no válida o no configurada. Ingresa tu API Key en los ajustes de OpenCode Cloud."
                    402 -> "Error 402: Créditos insuficientes en tu cuenta de OpenCode para este modelo. Prueba un modelo del nivel 'Gratis (Free)'."
                    429 -> "Error 429: Límite de tasa alcanzado. Espera unos segundos o usa un modelo alternativo."
                    else -> "Error OpenCode (${response.code}): $parsedError"
                }

                onError(userFriendly)
                return@withContext
            }

            val body = response.body
            if (body == null) {
                onError("Respuesta vacía del servidor OpenCode Cloud.")
                return@withContext
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                val trimmed = currentLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue

                if (trimmed.startsWith("data: ")) {
                    val dataPayload = trimmed.removePrefix("data: ").trim()
                    if (dataPayload == "[DONE]") {
                        break
                    }

                    try {
                        val json = JSONObject(dataPayload)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val firstChoice = choices.getJSONObject(0)
                            val delta = firstChoice.optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                onToken(content)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar líneas no JSON o de heartbeat
                    }
                }
            }

            onDone()
        } catch (e: IOException) {
            if (call.isCanceled()) {
                // Cancelado intencionalmente por el usuario (Botón Stop)
                onDone()
            } else {
                onError("Error de conexión con OpenCode Cloud: ${e.message ?: "Sin conexión a Internet"}")
            }
        } catch (e: Exception) {
            if (call.isCanceled()) {
                onDone()
            } else {
                onError("Error inesperado en OpenCode Cloud: ${e.message}")
            }
        } finally {
            if (activeCall == call) {
                activeCall = null
            }
        }
    }
}
