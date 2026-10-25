package com.example.data.remote

import com.example.domain.EdgeModelSpec
import com.example.domain.ModelCapabilityType
import com.example.domain.PromptTemplateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HfRepoSearchResult(
    val repoId: String,
    val author: String,
    val modelName: String,
    val downloads: Int,
    val likes: Int,
    val tags: List<String>,
    val isGated: Boolean,
    val capability: ModelCapabilityType = ModelCapabilityType.inferFromNameAndTags(repoId, tags)
)

data class HfGgufFileVariant(
    val repoId: String,
    val fileName: String,
    val downloadUrl: String,
    val quantization: String,
    val estimatedSizeMb: Int
)

class HfModelSearchService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Busca repositorios públicos con etiqueta 'gguf' en la API oficial de Hugging Face.
     */
    suspend fun searchGgufRepositories(
        query: String,
        hfToken: String = ""
    ): Result<List<HfRepoSearchResult>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (encodedQuery.isNotEmpty()) {
                "https://huggingface.co/api/models?filter=gguf&search=$encodedQuery&sort=downloads&direction=-1&limit=20"
            } else {
                "https://huggingface.co/api/models?filter=gguf&sort=downloads&direction=-1&limit=20"
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "EdgeLLMStudio-Android/2.0")

            if (hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Hugging Face API respondió HTTP ${response.code}")
                    )
                }

                val bodyStr = response.body?.string() ?: "[]"
                val jsonArray = JSONArray(bodyStr)
                val results = mutableListOf<HfRepoSearchResult>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", obj.optString("modelId", ""))
                    if (id.isBlank()) continue

                    val gatedVal = obj.opt("gated")
                    val isGated = gatedVal == true || (gatedVal is String && gatedVal != "false")

                    val parts = id.split("/")
                    val author = parts.firstOrNull() ?: "community"
                    val name = parts.getOrNull(1) ?: id
                    val downloads = obj.optInt("downloads", 0)
                    val likes = obj.optInt("likes", 0)

                    val tagsList = mutableListOf<String>()
                    val tagsArr = obj.optJSONArray("tags")
                    if (tagsArr != null) {
                        for (t in 0 until minOf(tagsArr.length(), 6)) {
                            tagsList.add(tagsArr.optString(t))
                        }
                    }

                    results.add(
                        HfRepoSearchResult(
                            repoId = id,
                            author = author,
                            modelName = name,
                            downloads = downloads,
                            likes = likes,
                            tags = tagsList,
                            isGated = isGated
                        )
                    )
                }
                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Consulta los archivos (.gguf) dentro de un repositorio específico usando el campo 'siblings'.
     */
    suspend fun fetchRepoGgufFiles(
        repoId: String,
        hfToken: String = ""
    ): Result<List<HfGgufFileVariant>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://huggingface.co/api/models/$repoId"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "EdgeLLMStudio-Android/2.0")

            if (hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Error HTTP ${response.code} al leer archivos de $repoId")
                    )
                }

                val bodyStr = response.body?.string() ?: "{}"
                val root = JSONObject(bodyStr)
                val siblings = root.optJSONArray("siblings") ?: JSONArray()
                val variants = mutableListOf<HfGgufFileVariant>()

                for (i in 0 until siblings.length()) {
                    val fileObj = siblings.getJSONObject(i)
                    val rfilename = fileObj.optString("rfilename", "")
                    if (!rfilename.endsWith(".gguf", ignoreCase = true)) continue
                    if (rfilename.contains("mmproj", ignoreCase = true) || rfilename.contains("vocab", ignoreCase = true)) continue

                    val sizeBytes = fileObj.optLong("size", 0L)
                    val quant = extractQuantizationTag(rfilename)
                    val estimatedMb = if (sizeBytes > 0L) {
                        (sizeBytes / (1024L * 1024L)).toInt()
                    } else {
                        estimateSizeFromRepoAndQuant(repoId, quant)
                    }

                    val directUrl = "https://huggingface.co/$repoId/resolve/main/$rfilename?download=true"
                    variants.add(
                        HfGgufFileVariant(
                            repoId = repoId,
                            fileName = rfilename.substringAfterLast('/'),
                            downloadUrl = directUrl,
                            quantization = quant,
                            estimatedSizeMb = estimatedMb
                        )
                    )
                }

                // Ordenar priorizando Q4_K_M, Q4_0, Q5_K_M, Q8_0 (los más recomendados en Android)
                val sorted = variants.sortedBy { variantPriority(it.quantization) }
                Result.success(sorted)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun toEdgeModelSpec(repo: HfRepoSearchResult, variant: HfGgufFileVariant): EdgeModelSpec {
        val lower = "${repo.repoId} ${variant.fileName}".lowercase()
        val format = when {
            "gemma" in lower -> PromptTemplateFormat.GEMMA
            "phi" in lower -> PromptTemplateFormat.PHI3
            "qwen" in lower || "smollm" in lower || "deepseek" in lower || "yi" in lower -> PromptTemplateFormat.CHATML
            else -> PromptTemplateFormat.LLAMA3
        }
        val family = when {
            "qwen" in lower -> "Qwen 2.5"
            "gemma" in lower -> "Gemma 2"
            "smollm" in lower -> "SmolLM2"
            "phi" in lower -> "Phi-3"
            "deepseek" in lower -> "DeepSeek"
            "mistral" in lower -> "Mistral"
            else -> "Llama 3"
        }

        val cleanId = "hf_${repo.repoId.replace("/", "_")}_${variant.quantization}".lowercase()
        val minRam = (variant.estimatedSizeMb * 1.45).toInt().coerceAtLeast(550)

        val capability = ModelCapabilityType.inferFromNameAndTags(
            "${repo.repoId} ${variant.fileName}",
            repo.tags
        )

        return EdgeModelSpec(
            id = cleanId,
            name = "${repo.modelName} (${variant.quantization})",
            provider = repo.author,
            family = "$family • GGUF",
            quantization = variant.quantization,
            accelerator = "llama.cpp JNI (RAM)",
            fileName = variant.fileName,
            downloadUrl = variant.downloadUrl,
            estimatedSizeMb = variant.estimatedSizeMb,
            minRamMb = minRam,
            description = "Repositorio Hugging Face: ${repo.repoId} (${repo.downloads} descargas) • ${capability.detailDescription}",
            promptFormat = format,
            capability = capability,
            isCustom = true
        )
    }

    private fun extractQuantizationTag(fileName: String): String {
        val regex = Regex("(Q[2-8]_[01KM](?:_[SML])?|IQ[1-4]_[A-Z]+|F16|BF16)", RegexOption.IGNORE_CASE)
        return regex.find(fileName)?.value?.uppercase() ?: "GGUF"
    }

    private fun estimateSizeFromRepoAndQuant(repoId: String, quant: String): Int {
        val lower = repoId.lowercase()
        val baseParamsB = when {
            "0.5b" in lower || "360m" in lower || "135m" in lower -> 0.5f
            "1b" in lower || "1.1b" in lower || "1.5b" in lower || "1.7b" in lower -> 1.4f
            "2b" in lower || "3b" in lower -> 2.4f
            "7b" in lower || "8b" in lower -> 5.2f
            else -> 1.5f
        }
        val multiplier = when {
            quant.startsWith("Q2") -> 420
            quant.startsWith("Q3") -> 520
            quant.startsWith("Q4") -> 680
            quant.startsWith("Q5") -> 790
            quant.startsWith("Q6") -> 890
            quant.startsWith("Q8") -> 1050
            else -> 700
        }
        return (baseParamsB * multiplier).toInt().coerceAtLeast(250)
    }

    private fun variantPriority(quant: String): Int {
        return when (quant.uppercase()) {
            "Q4_K_M" -> 1
            "Q4_K_S" -> 2
            "Q4_0" -> 3
            "Q5_K_M" -> 4
            "Q8_0" -> 5
            "Q3_K_M" -> 6
            "Q2_K" -> 7
            else -> 10
        }
    }
}
