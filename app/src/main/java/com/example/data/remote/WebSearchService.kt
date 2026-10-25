package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchSnippet(
    val title: String,
    val snippet: String,
    val sourceUrl: String
)

/**
 * Servicio de búsqueda rápida en Internet para enriquecer el contexto del modelo local (Web RAG).
 * Consulta DuckDuckGo Instant Answer API y Wikipedia REST API (ES/EN) sin requerir API Keys.
 */
class WebSearchService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun searchInternetSnippets(rawQuery: String): List<WebSearchSnippet> = withContext(Dispatchers.IO) {
        val cleanQuery = cleanSearchQuery(rawQuery)
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = mutableListOf<WebSearchSnippet>()

        // 1. Consultar Wikipedia en Español
        results.addAll(fetchWikipediaSnippets(cleanQuery, lang = "es"))

        // 2. Consultar DuckDuckGo Instant Answer API
        if (results.size < 2) {
            results.addAll(fetchDuckDuckGoSnippets(cleanQuery))
        }

        // 3. Si no hubo resultados en español, intentar Wikipedia en Inglés
        if (results.isEmpty()) {
            results.addAll(fetchWikipediaSnippets(cleanQuery, lang = "en"))
        }

        results.distinctBy { it.title }.take(3)
    }

    private fun cleanSearchQuery(raw: String): String {
        return raw
            .replace(Regex("(?i)^(busca en internet|busca|buscar|qué es|que es|quién es|quien es|cuál es|cual es|háblame de|hablame de|dime sobre|investiga)\\s+"), "")
            .replace("?", "")
            .replace("¿", "")
            .trim()
            .take(120)
    }

    private fun fetchWikipediaSnippets(query: String, lang: String): List<WebSearchSnippet> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=3"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "EdgeLLMStudio/2.0 (Android; Local AI Assistant)")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val searchArr = root.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
                val list = mutableListOf<WebSearchSnippet>()

                for (i in 0 until searchArr.length()) {
                    val item = searchArr.getJSONObject(i)
                    val title = item.optString("title", "")
                    val rawSnippet = item.optString("snippet", "")
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .trim()

                    if (title.isNotBlank() && rawSnippet.isNotBlank()) {
                        val pageUrl = "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"
                        list.add(
                            WebSearchSnippet(
                                title = "$title (Wikipedia ${lang.uppercase()})",
                                snippet = rawSnippet,
                                sourceUrl = pageUrl
                            )
                        )
                    }
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchDuckDuckGoSnippets(query: String): List<WebSearchSnippet> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "EdgeLLMStudio/2.0 (Android)")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val list = mutableListOf<WebSearchSnippet>()

                val abstractText = root.optString("AbstractText", "").trim()
                val heading = root.optString("Heading", query).trim()
                val abstractUrl = root.optString("AbstractURL", "https://duckduckgo.com/?q=$encoded")

                if (abstractText.isNotBlank()) {
                    list.add(
                        WebSearchSnippet(
                            title = "$heading (DuckDuckGo)",
                            snippet = abstractText,
                            sourceUrl = abstractUrl
                        )
                    )
                }

                val related = root.optJSONArray("RelatedTopics") ?: JSONArray()
                for (i in 0 until minOf(related.length(), 2)) {
                    val topic = related.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "").trim()
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotBlank()) {
                        list.add(
                            WebSearchSnippet(
                                title = "Resultado Web #${i + 1}",
                                snippet = text,
                                sourceUrl = firstUrl
                            )
                        )
                    }
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
